package com.erp.ocr.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.erp.ocr.model.CollectionEntry;
import com.erp.ocr.model.Deduction;
import com.erp.ocr.model.MonthlySettlement;
import com.erp.ocr.model.Organization;
import com.erp.ocr.model.Supplier;
import com.erp.ocr.model.Role;
import com.erp.ocr.model.User;
import com.erp.ocr.repository.CollectionEntryRepository;
import com.erp.ocr.repository.MonthlySettlementRepository;
import com.erp.ocr.repository.OrganizationRepository;
import com.erp.ocr.repository.SupplierRepository;
import com.erp.ocr.repository.UserRepository;
import com.erp.ocr.strategy.StandardPricingStrategy;
import com.erp.ocr.strategy.DefaultTieredPricingStrategy;
import com.erp.ocr.strategy.PricingStrategy;
import com.erp.ocr.util.MoneyUtil;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Gera um conjunto de dados SINTÉTICOS para demonstrar o sistema.
 * <p>
 * ⚠ <strong>Nenhum dado aqui vem de cliente, produtor ou sistema real.</strong>
 * São números, nomes e cadastros inventados, criados só para que as telas
 * tenham o que mostrar. E-mails e chaves Pix usam o TLD reservado
 * {@code .example} (RFC 2606), que <strong>ninguém pode registrar</strong> — é o
 * que garante que um dado de demonstração não possa alcançar uma pessoa real.
 * <p>
 * <strong>Por que está fora do boot.</strong> O {@link DataSeeder} original gerava
 * isso a cada subida da aplicação, o que é inadequado por três motivos: o volume
 * (~35 mil registros) atrasaria o boot; os dados entravam em qualquer ambiente em
 * que a variável {@code SEED_DEMO_DATA} estivesse ligada, inclusive produção; e
 * um CommandLineRunner não pode ser executado "uma vez só" sob comando. Aqui o
 * gerador só existe com o perfil Spring {@code demo} ativo — {@code @Profile}
 * garante que, sem esse perfil, a classe sequer é registrada no contexto.
 * <p>
 * <strong>O que mudou em relação ao seeder antigo</strong> (este é o motivo de
 * o gerador ter sido reescrito em vez de reaproveitado):
 * <ul>
 * <li><strong>Determinístico.</strong> O antigo usava {@code new Random()} sem
 * semente, então cada banco recriado ficava diferente — impossível de
 * reproduzir uma demonstração ou comparar dois estados.</li>
 * <li><strong>Preço fiel ao motor.</strong> O antigo sorteava {@code 2,50 ±
 * 0,20} e gravava {@code precoFinal = null}: números que a aplicação jamais
 * produziria, porque {@code precoFinal} sai da strategy de preço e do
 * {@link MoneyUtil}. Agora o cálculo é o mesmo do
 * {@code MonthlySettlementService}.</li>
 * <li><strong>Período 20 → 20.</strong> O antigo fechava do dia 1 ao último dia
 * do mês, contrariando a prática real de pagar o leite produzido entre o dia 20
 * e o dia 20 seguinte.</li>
 * <li><strong>Status misto.</strong> O antigo marcava tudo como "Pago", o que
 * zerava os cards "Pagamentos Pendentes" e "Fechamentos Pendentes" do
 * Dashboard — justamente as telas que a demonstração precisa mostrar.</li>
 * <li><strong>Deductions coerentes.</strong> O antigo preenchia o campo escalar
 * {@code descontos} sem criar as linhas de {@code listaDeductions}, deixando as
 * duas representações divergentes.</li>
 * <li><strong>Múltiplas organizações.</strong> Só havia uma, o que tornava o
 * isolamento por tenant invisível na demonstração.</li>
 * </ul>
 *
 * <strong>Períodos disjuntos.</strong> Os períodos são
 * {@code [dia 21 do mês anterior .. dia 20 do mês corrente]}, e não
 * {@code [dia 20 .. dia 20]}. O motivo é o próprio banco recusando a
 * sobreposição: {@code existsBySupplierAndPeriodoOverlap} compara de forma
 * inclusiva ({@code dataInicio <= :dataFim AND dataFim >= :dataInicio}), então
 * dois períodos 20→20 consecutivos dividiriam o dia 20 e seriam rejeitados como
 * sobrepostos. Começar no dia 21 mantém o pagamento no dia 20 — que é o que o
 * {@code mes}/{@code ano} ancorado na {@code dataFim} precisa expressar — e
 * garante que nenhum dia de leite seja contado duas vezes.
 */
@Component
@Profile("demo")
public class DemoDataGenerator implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(DemoDataGenerator.class);

    /**
     * Nome da primeira organização. CASADO com o backfill da migration
     * {@code V4__tenancy.sql}: se este nome divergir daquele, o gerador aponta
     * para uma organização diferente da que a migration criou.
     */
    private static final String ORGANIZACAO_INICIAL = "Acme Supply Co.";

    /** Organizações adicionais, quando {@code demo.seed.organizacoes > 1}. */
    private static final List<String> ORGANIZACOES_EXTRAS = List.of("Green Valley Co-op", "Oakridge Partners");

    /**
     * Nomes de demonstração. Deliberadamente <strong>distintos</strong> da lista
     * do seeder antigo (Silva, Santos, Oliveira, Souza, Rodrigues, Ferreira,
     * Alves, Pereira, Lima, Gomes): este conjunto é criado do zero para a demo
     * de entrevista e não deve se confundir com aquela série anterior.
     */
    private static final String[] PRENOMES = {
            "Adriana", "Bernardo", "Camila", "Diego", "Eduarda", "Felipe", "Gabriela", "Henrique",
            "Isabela", "Joaquim", "Karina", "Leonardo", "Mariana", "Nicolas", "Olívia", "Paulo",
            "Queila", "Rafael", "Sabrina", "Thiago", "Ursula", "Vicente", "Wanda", "Xavier",
            "Yasmin", "Zilda"
    };

    private static final String[] SOBRENOMES = {
            "Assunção", "Bittencourt", "Carvalho", "Delatorre", "Escobar", "Furtado", "Gonçalves",
            "Hakimi", "Inocêncio", "Junqueira", "Klein", "Lacerda", "Meireles", "Nogueira",
            "Oiticica", "Padilha", "Queirós", "Rezende", "Sampaio", "Tavares", "Uchôa", "Valadares",
            "Winter", "Zanetti"
    };

    private static final String[] LINHAS = { "North Route", "East Route", "South Route" };

    private static final String[] FORMAS_PAGAMENTO = { "ACH Transfer", "Wire", "Check" };

    /** Synthetic itemized deduction descriptions. */
    private static final String[] DESCRICOES_DESCONTO = {
            "Packaging supplies", "Lab test kits", "Equipment rental",
            "Prior balance", "Freight adjustment"
    };

    /** Domínio reservado pela RFC 2606: impossível registrar. */
    private static final String DOMINIO_DEMO = "example";

    @Value("${demo.seed.ate:}")
    private String mesAlvoConfig;

    @Value("${demo.seed.organizacoes:2}")
    private int totalOrganizacoes;

    @Value("${demo.seed.produtores-por-organizacao:20}")
    private int produtoresPorOrganization;

    @Value("${demo.seed.meses:30}")
    private int totalMeses;

    @Value("${demo.seed.seed:20261018}")
    private long semente;

    @Value("${demo.seed.senha:demo-senha-local}")
    private String senhaDemo;

    private final OrganizationRepository organizacaoRepository;
    private final UserRepository usuarioRepository;
    private final SupplierRepository produtorRepository;
    private final CollectionEntryRepository coletaRepository;
    private final MonthlySettlementRepository fechamentoRepository;
    private final PasswordEncoder passwordEncoder;

    public DemoDataGenerator(OrganizationRepository organizacaoRepository,
            UserRepository usuarioRepository,
            SupplierRepository produtorRepository,
            CollectionEntryRepository coletaRepository,
            MonthlySettlementRepository fechamentoRepository,
            PasswordEncoder passwordEncoder) {
        this.organizacaoRepository = organizacaoRepository;
        this.usuarioRepository = usuarioRepository;
        this.produtorRepository = produtorRepository;
        this.coletaRepository = coletaRepository;
        this.fechamentoRepository = fechamentoRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        YearMonth mesAlvo = resolverMesAlvo();
        Random random = new Random(semente);

        logger.warn("=========================================================================");
        logger.warn("  DADOS SINTETICOS DE DEMONSTRACAO — nenhum dado e real.");
        logger.warn("  Nomes e cadastros sao inventados; e-mails usam o dominio");
        logger.warn("  reservado .{} (RFC 2606), que ninguem pode registrar.".format(DOMINIO_DEMO));
        logger.warn("  ate={}  organizacoes={}  produtores/org={}  meses={}  semente={}",
                mesAlvo, totalOrganizacoes, produtoresPorOrganization, totalMeses, semente);
        logger.warn("=========================================================================");

        int totalSupplieres = 0;
        int totalFechamentos = 0;

        for (int indice = 0; indice < totalOrganizacoes; indice++) {
            Organization organizacao = resolverOrganization(indice);
            logger.info("[{}] organizacao \"{}\" (id={})", indice + 1, organizacao.getNome(), organizacao.getId());

            criarUsersDemo(organizacao, indice);

            List<Supplier> produtores = criarSupplieres(organizacao, indice, random);
            totalSupplieres += produtores.size();

            totalFechamentos += gerarHistorico(produtores, mesAlvo, random);
        }

        logger.warn("DADOS SINTETICOS GERADOS: {} produtores e {} fechamentos. ate={}", totalSupplieres,
                totalFechamentos, mesAlvo);
    }

    // --- Configuração -----------------------------------------------------

    private YearMonth resolverMesAlvo() {
        if (mesAlvoConfig == null || mesAlvoConfig.isBlank()) {
            return YearMonth.now();
        }
        try {
            return YearMonth.parse(mesAlvoConfig.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalStateException(
                    "demo.seed.ate invalido: \"" + mesAlvoConfig + "\". Use o formato YYYY-MM, ex.: 2026-10.", e);
        }
    }

    /**
     * A primeira organização é sempre {@value #ORGANIZACAO_INICIAL}, pelo motivo
     * documentado no campo. As demais vêm da lista de extras.
     */
    private Organization resolverOrganization(int indice) {
        String nome = indice == 0 ? ORGANIZACAO_INICIAL
                : ORGANIZACOES_EXTRAS.get((indice - 1) % ORGANIZACOES_EXTRAS.size());
        return organizacaoRepository.findByNome(nome)
                .orElseGet(() -> organizacaoRepository.save(new Organization(nome)));
    }

    // --- Usuários ---------------------------------------------------------

    /**
     * Um {@link Role#USER} por organização (o login que enxerga só a própria
     * carteira) e um {@link Role#ADMIN} na primeira.
     * <p>
     * A senha vem de {@code demo.seed.senha} e é um valor de demonstração
     * público, não um segredo — mesmo assim o código não embute nada: o default
     * é obviamente local e o valor real deve chegar por variável de ambiente.
     */
    private void criarUsersDemo(Organization organizacao, int indice) {
        String slug = organizacao.getNome().toLowerCase().replaceAll("[^a-z0-9]+", "-");

        String emailOperador = "operador." + slug + "@" + DOMINIO_DEMO + ".com";
        if (usuarioRepository.findByEmail(emailOperador).isEmpty()) {
            User operador = new User();
            operador.setEmail(emailOperador);
            operador.setNome("Operação " + organizacao.getNome());
            operador.setSenha(passwordEncoder.encode(senhaDemo));
            operador.setRole(Role.USER);
            operador.setOrganization(organizacao);
            usuarioRepository.save(operador);
            logger.info("      USER   {} (so enxerga esta organizacao)", emailOperador);
        }

        if (indice == 0) {
            String emailAdmin = "admin.demo@" + DOMINIO_DEMO + ".com";
            if (usuarioRepository.findByEmail(emailAdmin).isEmpty()) {
                User admin = new User();
                admin.setEmail(emailAdmin);
                admin.setNome("Administrador (demo)");
                admin.setSenha(passwordEncoder.encode(senhaDemo));
                admin.setRole(Role.ADMIN);
                admin.setOrganization(organizacao);
                usuarioRepository.save(admin);
                logger.info("      ADMIN  {} (enxerga todas as organizacoes)", emailAdmin);
            }
        }
    }

    // --- Supplieres -------------------------------------------------------

    private List<Supplier> criarSupplieres(Organization organizacao, int indiceOrganization, Random random) {
        List<Supplier> produtores = new ArrayList<>();
        for (int i = 0; i < produtoresPorOrganization; i++) {
            String nome = nomeSupplier(indiceOrganization, i);
            // Idempotência: se já existe um produtor com esse nome na organização,
            // reaproveita em vez de duplicar.
            Supplier existente = produtorRepository
                    .findVisiveisPorNome(nome, organizacao.getId())
                    .stream()
                    .findFirst()
                    .orElse(null);
            if (existente != null) {
                produtores.add(existente);
                continue;
            }

            Supplier produtor = new Supplier();
            produtor.setNome(nome);
            produtor.setLinha(LINHAS[random.nextInt(LINHAS.length)]);
            produtor.setChavePix(i % 7 == 0 ? null : "pix." + slug(nome) + "@" + DOMINIO_DEMO + ".com");
            produtor.setFormaPagamento(FORMAS_PAGAMENTO[random.nextInt(FORMAS_PAGAMENTO.length)]);
            produtor.setInscricaoEstadual(i % 3 == 0 ? null : "IE" + (100000000 + random.nextInt(899999999)));
            produtor.setAtivo(i % 11 != 0);
            // Um terço adere ao sistema de qualidade: é o que exercita as métricas
            // (cbt/ccs/gordura/proteína) e o itemizado de bônus no relatório.
            produtor.setUsaPagamentoPorQualidade(i % 3 == 0);
            produtor.setOrganization(organizacao);
            produtores.add(produtor);
        }
        produtorRepository.saveAll(produtores);
        logger.info("      {} produtores", produtores.size());
        return produtores;
    }

    private String nomeSupplier(int indiceOrganization, int i) {
        String prenome = PRENOMES[(i + indiceOrganization * 7) % PRENOMES.length];
        String sobrenome = SOBRENOMES[(i * 5 + indiceOrganization * 3) % SOBRENOMES.length];
        return prenome + " " + sobrenome;
    }

    private static String slug(String texto) {
        return texto.toLowerCase()
                .replaceAll("[^a-z0-9]+", ".")
                .replaceAll("^\\.|\\.$", "");
    }

    // --- Histórico --------------------------------------------------------

    private int gerarHistorico(List<Supplier> produtores, YearMonth mesAlvo, Random random) {
        int fechamentosCriados = 0;
        int totalSupplieres = produtores.size();

        for (int indice = 0; indice < totalSupplieres; indice++) {
            Supplier produtor = produtores.get(indice);
            // Volume individual por produtor, para a carteira ficar desigual como
            // numa cooperativa real: alguns entregam o dobro de outros.
            int litrosBase = 60 + indice % 5 * 40;

            for (int recuo = totalMeses - 1; recuo >= 0; recuo--) {
                YearMonth mesFim = mesAlvo.minusMonths(recuo);
                LocalDate dataInicio = mesFim.minusMonths(1).atDay(21);
                LocalDate dataFim = mesFim.atDay(20);

                if (dataFim.isAfter(YearMonth.now().atEndOfMonth())) {
                    break;
                }

                List<CollectionEntry> coletas = gerarColetas(produtor, dataInicio, dataFim, litrosBase, random);
                if (coletas.isEmpty()) {
                    continue;
                }
                coletaRepository.saveAll(coletas);

                // O período mais recente fica incompleto de propósito: sem
                // fechamento para parte da carteira, os cards "Pendentes" do
                // Dashboard têm o que mostrar. Com tudo fechado, eles ficariam
                // zerados e a demo esconderia a tela.
                boolean ultimoPeriodo = recuo == 0;
                boolean fechar = !ultimoPeriodo || (indice % 5) < 3;
                if (fechar) {
                    criarFechamento(produtor, dataFim, random, recuo);
                    fechamentosCriados++;
                }

                if ((indice + 1) % 5 == 0 || ultimoPeriodo) {
                    logger.info("      produtor {}/{} — mes {}", indice + 1, totalSupplieres, mesFim);
                }
            }
        }
        return fechamentosCriados;
    }

    /**
     * Coletas do período, pulando o período se já houver coleta gravada — é o que
     * torna o gerador reexecutável sem duplicar e o que permite estender o
     * histórico depois.
     */
    private List<CollectionEntry> gerarColetas(Supplier produtor, LocalDate inicio, LocalDate fim, int litrosBase,
            Random random) {
        List<CollectionEntry> existentes = coletaRepository
                .findVisiveisPorSupplierEPeriodo(produtor.getId(), inicio, fim, produtor.getOrganization().getId());
        if (!existentes.isEmpty()) {
            return List.of();
        }

        List<CollectionEntry> coletas = new ArrayList<>();
        for (LocalDate dia = inicio; !dia.isAfter(fim); dia = dia.plusDays(1)) {
            // Variação com tendência suave: o filtro de coleta embota um pouco a
            // dispersão, e números puramente aleatórios parecem ruído.
            int variacao = random.nextInt(41) - 20;
            int litros = Math.max(10, litrosBase + variacao);
            CollectionEntry coleta = new CollectionEntry();
            coleta.setData(dia);
            coleta.setQuantidadeLitros(litros);
            coleta.setSupplier(produtor);
            coletas.add(coleta);
        }
        return coletas;
    }

    /**
     * Cria o fechamento chamando o MESMO motor de preço do
     * {@code MonthlySettlementService} e o mesmo {@link MoneyUtil}. Gerar o preço
     * por conta própria produziria números que a aplicação jamais exibiria.
     */
    private void criarFechamento(Supplier produtor, LocalDate dataFim, Random random, int recuo) {
        // Coletas anteriores podem ter sido geradas e não fechadas; o total tem de
        // vir do que existe de fato, não de um cálculo presumido.
        List<CollectionEntry> doPeriodo = coletaRepository
                .findVisiveisPorSupplierEPeriodo(produtor.getId(), dataFim.minusMonths(1).withDayOfMonth(21), dataFim,
                        produtor.getOrganization().getId());
        if (doPeriodo.isEmpty()) {
            return;
        }

        long soma = doPeriodo.stream().mapToLong(c -> (long) Math.max(c.getQuantidadeLitros(), 0)).sum();
        int totalLitros = (int) Math.min(soma, Integer.MAX_VALUE);
        if (totalLitros <= 0) {
            return;
        }

        MonthlySettlement fechamento = new MonthlySettlement();
        fechamento.setSupplier(produtor);
        fechamento.setDataInicio(dataFim.minusMonths(1).withDayOfMonth(21));
        fechamento.setDataFim(dataFim);
        // Ancora no mês em que o produtor é EFETIVAMENTE pago, que é a dataFim.
        fechamento.setMes(dataFim.getMonthValue());
        fechamento.setAno(dataFim.getYear());
        fechamento.setTotalLitros(totalLitros);

        BigDecimal precoBase = precoBase(recuo, random);
        if (produtor.isUsaPagamentoPorQualidade()) {
            fechamento.setCbt(BigDecimal.valueOf(20 + random.nextInt(900)));
            fechamento.setCcs(BigDecimal.valueOf(60 + random.nextInt(1400)));
            fechamento.setGordura(BigDecimal.valueOf(260 + random.nextInt(180)).divide(BigDecimal.TEN, 2,
                    java.math.RoundingMode.HALF_UP));
            fechamento.setProteina(BigDecimal.valueOf(240 + random.nextInt(170)).divide(BigDecimal.TEN, 2,
                    java.math.RoundingMode.HALF_UP));
        }

        // Bônus comercial é digitado no lançamento e pode ser NEGATIVO
        // (penalidade contratual). Gerar só valores positivos esconderia a regra.
        fechamento.setBonusVolume(bonusComSinal(random));
        fechamento.setBonusFidelidade(bonusComSinal(random));

        PricingStrategy strategy = produtor.isUsaPagamentoPorQualidade()
                ? new DefaultTieredPricingStrategy()
                : new StandardPricingStrategy();
        BigDecimal precoFinal = MoneyUtil.roundPricePerLiter(strategy.calculateFinalPrice(precoBase, fechamento,
                fechamento.getBonusVolume(), fechamento.getBonusFidelidade()));

        // Mesma exigência da aplicação: preço final tem de ser positivo. Se um
        // sorteio PRODuzisse preço <= 0, o dado seria rejeitado pela própria API,
        // e a demo quebraria. Nunca deixe o acaso gerar isso.
        if (precoFinal.compareTo(BigDecimal.ZERO) <= 0) {
            fechamento.setBonusVolume(BigDecimal.ZERO);
            fechamento.setBonusFidelidade(BigDecimal.ZERO);
            precoFinal = MoneyUtil.roundPricePerLiter(strategy.calculateFinalPrice(precoBase, fechamento, null, null));
        }
        fechamento.setPrecoLitro(MoneyUtil.roundPricePerLiter(precoBase));
        fechamento.setPrecoFinal(precoFinal);

        BigDecimal totalBruto = MoneyUtil.round(precoFinal.multiply(BigDecimal.valueOf(totalLitros)));
        fechamento.setTotalBruto(totalBruto);

        // Deductions itemizados: as LINHAS e o escalar precisam concordar, senão o
        // relatório imprime um total que não bate com o que a lista mostra.
        List<Deduction> descontos = gerarDeductions(random, totalBruto, fechamento);
        fechamento.getListaDeductions().addAll(descontos);
        BigDecimal somaDeductions = descontos.stream()
                .map(Deduction::getValor)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        fechamento.setDeductions(MoneyUtil.round(somaDeductions));
        fechamento.setTotalLiquido(MoneyUtil.round(totalBruto.subtract(fechamento.getDeductions())));

        // Status misto: tudo "Pago" zeraria os cards de pendência do Dashboard.
        boolean pago = random.nextInt(100) < 65;
        fechamento.setStatusPagamento(pago ? "Pago" : "Pendente");
        fechamento.setDataPagamento(pago ? dataFim.plusDays(random.nextInt(10)) : null);

        fechamentoRepository.save(fechamento);
    }

    private List<Deduction> gerarDeductions(Random random, BigDecimal totalBruto, MonthlySettlement fechamento) {
        List<Deduction> descontos = new ArrayList<>();
        if (random.nextInt(100) >= 45) {
            return descontos;
        }
        int quantidade = 1 + random.nextInt(2);
        BigDecimal acumulado = BigDecimal.ZERO;
        for (int i = 0; i < quantidade; i++) {
            // Cada desconto é uma fração do bruto, e o acumulado nunca passa de
            // 40% — abaixo do limite de "descontos maiores que o bruto" que a
            // aplicação rejeita com exigirDeductionsValidos.
            BigDecimal teto = totalBruto.multiply(new BigDecimal("0.15")).divide(BigDecimal.valueOf(i + 1),
                    MoneyUtil.CURRENCY_SCALE, java.math.RoundingMode.HALF_UP);
            BigDecimal valor = MoneyUtil.round(BigDecimal.valueOf(random.nextInt(60000) + 2000).divide(BigDecimal.TEN,
                    MoneyUtil.CURRENCY_SCALE, java.math.RoundingMode.HALF_UP));
            if (valor.compareTo(teto) > 0) {
                valor = teto;
            }
            if (valor.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            acumulado = acumulado.add(valor);

            Deduction desconto = new Deduction();
            desconto.setDescricao(DESCRICOES_DESCONTO[random.nextInt(DESCRICOES_DESCONTO.length)]);
            desconto.setValor(valor);
            // O lado owner da relação: sem isto o INSERT sai com
            // fechamento_id nulo e viola o NOT NULL da coluna.
            desconto.setSettlement(fechamento);
            descontos.add(desconto);
        }
        return descontos;
    }

    /** Preço base com caminhada lenta ao longo dos meses (tendência de mercado). */
    private BigDecimal precoBase(int recuo, Random random) {
        BigDecimal tendencia = BigDecimal.valueOf(recuo * 0.012);
        BigDecimal ruido = BigDecimal.valueOf(random.nextInt(41) - 20).divide(BigDecimal.valueOf(1000), 4,
                java.math.RoundingMode.HALF_UP);
        return MoneyUtil.roundPricePerLiter(new BigDecimal("2.40").add(tendencia).add(ruido));
    }

    /** Bônus em R$/litro: positivo, zero ou negativo (penalidade contratual). */
    private BigDecimal bonusComSinal(Random random) {
        int sorteio = random.nextInt(100);
        if (sorteio < 20) {
            return null;
        }
        if (sorteio < 50) {
            return MoneyUtil.roundPricePerLiter(BigDecimal.valueOf(random.nextInt(21) + 5)
                    .divide(BigDecimal.TEN, 4, java.math.RoundingMode.HALF_UP));
        }
        if (sorteio < 75) {
            return BigDecimal.ZERO;
        }
        return MoneyUtil.roundPricePerLiter(BigDecimal.valueOf(-(random.nextInt(21) + 5))
                .divide(BigDecimal.TEN, 4, java.math.RoundingMode.HALF_UP));
    }
}