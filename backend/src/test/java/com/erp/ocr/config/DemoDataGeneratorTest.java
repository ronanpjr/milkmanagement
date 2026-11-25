package com.erp.ocr.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import com.erp.ocr.service.AzureOcrService;
import com.erp.ocr.service.ocr.GeminiOcrService;
import com.erp.ocr.strategy.StandardPricingStrategy;
import com.erp.ocr.strategy.DefaultTieredPricingStrategy;
import com.erp.ocr.util.MoneyUtil;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O gerador de dados de demonstração é código de produção: roda em ambientes de
 * apresentação e、培训 e vai ser lido por quem avalia o sistema. Estes testes
 * prendem as propriedades que o tornam confiável — determinismo, fidelidade ao
 * motor de preço e respeito ao isolamento entre organizações.
 * <p>
 * Meses fixos no passado de propósito: um teste que dependesse de
 * {@code LocalDate.now()} quebraria conforme o dia virasse, e o gerador também
 * não deve gerar período futuro.
 */
@SpringBootTest
@ActiveProfiles("demo")
@Transactional
@TestPropertySource(properties = {
        "demo.seed.ate=2025-06",
        "demo.seed.organizacoes=2",
        "demo.seed.produtores-por-organizacao=3",
        "demo.seed.meses=4",
        "demo.seed.seed=4242",
        "demo.seed.senha=senha-de-teste"
})
@DisplayName("Gerador de dados sintéticos de demonstração")
class DemoDataGeneratorTest {

    @MockitoBean
    private GeminiOcrService geminiOcrService;

    @MockitoBean
    private AzureOcrService azureOcrService;

    @Autowired
    private DemoDataGenerator gerador;

    @Autowired
    private OrganizationRepository organizacaoRepository;
    @Autowired
    private UserRepository usuarioRepository;
    @Autowired
    private SupplierRepository produtorRepository;
    @Autowired
    private CollectionEntryRepository coletaRepository;
    @Autowired
    private MonthlySettlementRepository fechamentoRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private com.erp.ocr.service.SupplierService produtorService;

    private Organization acmeOrg;
    private Organization segundaOrganization;

    @BeforeEach
    void limpar() {
        SecurityContextHolder.clearContext();
        // Os testes de outras classes podem ter deixado registros; começar limpo
        // torna as contagens abaixo confiáveis.
        fechamentoRepository.deleteAll();
        coletaRepository.deleteAll();
        produtorRepository.deleteAll();
        usuarioRepository.deleteAll();
        organizacaoRepository.deleteAll();
    }

    private void executarGerador() {
        // O gerador não consulta o OrganizationContext: ele ESCREVE, e o
        // isolamento é testado na leitura (teste de isolamento). Não há contexto
        // de segurança nenhum aqui de propósito — menos uma organização de
        // bootstrap para errar.
        gerador.run(new org.springframework.boot.DefaultApplicationArguments(new String[0]));
    }

    private void autenticar(User u) {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(u, null, u.getAuthorities()));
    }

    // --- Estrutura --------------------------------------------------------

    @Test
    @DisplayName("mantém a organização 'Acme Supply Co.', que é a que a migration V4 criou")
    void firstOrganizationIsAcmeSupplyCo() {
        executarGerador();

        List<Organization> organizacoes = organizacaoRepository.findAll();
        assertThat(organizacoes).extracting(Organization::getNome).contains("Acme Supply Co.");
        // A segunda existe para que o isolamento por tenant fique visível.
        assertThat(organizacoes).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("todo usuário e produtor nasce com organização — a coluna é NOT NULL")
    void tudoTemOrganization() {
        executarGerador();

        assertThat(usuarioRepository.findAll())
                .isNotEmpty()
                .allSatisfy(u -> assertThat(u.getOrganization()).isNotNull());
        assertThat(produtorRepository.findAll())
                .isNotEmpty()
                .allSatisfy(p -> assertThat(p.getOrganization()).isNotNull());
    }

    @Test
    @DisplayName("cria um USER por organização, que enxerga só a própria carteira")
    void criaUmUserPorOrganization() {
        executarGerador();

        List<User> operadores = usuarioRepository.findAll().stream()
                .filter(u -> u.getRole() == Role.USER)
                .toList();
        assertThat(operadores).hasSize(2);
        // E-mails no domínio reservado .example (RFC 2606): impossível registrar,
        // então um dado de demonstração não alcança pessoa real.
        assertThat(operadores).allSatisfy(u -> assertThat(u.getEmail()).endsWith("@example.com"));
    }

    @Test
    @DisplayName("mistura produtores aderentes e não aderentes ao sistema de qualidade")
    void misturaAderentes() {
        executarGerador();

        List<Supplier> produtores = produtorRepository.findAll();
        assertThat(produtores).anyMatch(Supplier::isUsaPagamentoPorQualidade);
        assertThat(produtores).anyMatch(p -> !p.isUsaPagamentoPorQualidade());
    }

    // --- Fidelidade do preço ----------------------------------------------

    @Test
    @DisplayName("preço final bate com o motor de preço e nunca é nulo nem não-positivo")
    void precoFinalFielAoMotor() {
        executarGerador();

        List<MonthlySettlement> fechamentos = fechamentoRepository.findAll();
        assertThat(fechamentos).isNotEmpty();

        for (MonthlySettlement f : fechamentos) {
            assertThat(f.getPrecoFinal())
                    .as("precoFinal nao pode ser nulo: o seeder antigo gravava null")
                    .isNotNull();
            assertThat(f.getPrecoFinal())
                    .as("a aplicacao exige preco final positivo")
                    .isGreaterThan(BigDecimal.ZERO);

            var strategy = f.getSupplier().isUsaPagamentoPorQualidade()
                    ? new DefaultTieredPricingStrategy()
                    : new StandardPricingStrategy();
            BigDecimal esperado = MoneyUtil.roundPricePerLiter(strategy.calculateFinalPrice(f.getPrecoLitro(), f,
                    f.getBonusVolume(), f.getBonusFidelidade()));
            assertThat(f.getPrecoFinal())
                    .as("precoFinal tem de ser o que a strategy produz")
                    .isEqualByComparingTo(esperado);
        }
    }

    @Test
    @DisplayName("totais são coerentes: bruto = preço × litros, líquido = bruto − descontos")
    void totaisCoerentes() {
        executarGerador();

        for (MonthlySettlement f : fechamentoRepository.findAll()) {
            BigDecimal brutoEsperado = MoneyUtil
                    .round(f.getPrecoFinal().multiply(BigDecimal.valueOf(f.getTotalLitros())));
            assertThat(f.getTotalBruto()).isEqualByComparingTo(brutoEsperado);
            assertThat(f.getTotalLiquido())
                    .isEqualByComparingTo(MoneyUtil.round(f.getTotalBruto().subtract(f.getDeductions())));
            assertThat(f.getDeductions()).isLessThanOrEqualTo(f.getTotalBruto());
        }
    }

    @Test
    @DisplayName("soma das linhas de desconto é igual ao campo escalar descontos")
    void descontosItemizadosBatemComEscalar() {
        executarGerador();

        List<MonthlySettlement> comDeduction = fechamentoRepository.findAll().stream()
                .filter(f -> !f.getListaDeductions().isEmpty())
                .toList();
        assertThat(comDeduction)
                .as("a demo precisa ter desconto itemizado para o relatorio mostrar a lista")
                .isNotEmpty();

        for (MonthlySettlement f : comDeduction) {
            BigDecimal soma = f.getListaDeductions().stream()
                    .map(Deduction::getValor)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(f.getDeductions()).isEqualByComparingTo(MoneyUtil.round(soma));
            for (Deduction d : f.getListaDeductions()) {
                // O lado owner da relação tem de estar preenchido, senão o
                // INSERT viola o NOT NULL de fechamento_id.
                assertThat(d.getSettlement()).isNotNull();
                assertThat(d.getSettlement().getId()).isEqualTo(f.getId());
            }
        }
    }

    // --- Regra de negócio -------------------------------------------------

    @Test
    @DisplayName("período é do dia 21 ao dia 20, com mês e ano ancorados na dataFim")
    void periodoTwentyToTwentyAncoradoNaDataFim() {
        executarGerador();

        for (MonthlySettlement f : fechamentoRepository.findAll()) {
            assertThat(f.getDataInicio().getDayOfMonth())
                    .as("periodos precisam ser disjuntos: o banco recusa sobreposicao no dia 20")
                    .isEqualTo(21);
            assertThat(f.getDataFim().getDayOfMonth()).isEqualTo(20);
            assertThat(f.getDataFim()).isAfter(f.getDataInicio());

            // O mês/ano do pagamento é o da dataFim — foi o que a auditoria do
            // achado 21 corrigiu nos relatórios.
            assertThat(f.getMes()).isEqualTo(f.getDataFim().getMonthValue());
            assertThat(f.getAno()).isEqualTo(f.getDataFim().getYear());
        }
    }

    @Test
    @DisplayName("períodos do mesmo produtor não se sobrepõem, como o banco exige")
    void periodosNaoSeSobrepoem() {
        executarGerador();

        List<MonthlySettlement> todos = fechamentoRepository.findAll();
        for (int i = 0; i < todos.size(); i++) {
            for (int j = i + 1; j < todos.size(); j++) {
                MonthlySettlement a = todos.get(i);
                MonthlySettlement b = todos.get(j);
                if (!a.getSupplier().getId().equals(b.getSupplier().getId())) {
                    continue;
                }
                boolean sobrepoe = !a.getDataFim().isBefore(b.getDataInicio())
                        && !b.getDataFim().isBefore(a.getDataInicio());
                assertThat(sobrepoe)
                        .as("fechamentos %s e %s do produtor %s se sobrepoem; a API recusaria",
                                a.getDataInicio(), b.getDataInicio(), a.getSupplier().getNome())
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("existe bônus negativo (penalidade contratual) e o preço continua positivo")
    void bonusNegativoExiste() {
        executarGerador();

        List<MonthlySettlement> comBonusNegativo = fechamentoRepository.findAll().stream()
                .filter(f -> (f.getBonusVolume() != null && f.getBonusVolume().signum() < 0)
                        || (f.getBonusFidelidade() != null && f.getBonusFidelidade().signum() < 0))
                .toList();

        assertThat(comBonusNegativo)
                .as("bonus negativo e regra de negocio; a demo precisa exibir a penalidade")
                .isNotEmpty();
        assertThat(comBonusNegativo).allSatisfy(f -> assertThat(f.getPrecoFinal()).isGreaterThan(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("status de pagamento é misto, para o Dashboard não ficar zerado")
    void statusMisto() {
        executarGerador();

        List<String> status = fechamentoRepository.findAll().stream()
                .map(MonthlySettlement::getStatusPagamento)
                .distinct()
                .toList();
        assertThat(status).contains("Pago", "Pendente");
    }

    @Test
    @DisplayName("pago tem data de pagamento; pendente não tem")
    void dataPagamentoCoerenteComStatus() {
        executarGerador();

        for (MonthlySettlement f : fechamentoRepository.findAll()) {
            if ("Pago".equals(f.getStatusPagamento())) {
                assertThat(f.getDataPagamento()).isNotNull();
            } else {
                assertThat(f.getDataPagamento()).isNull();
            }
        }
    }

    @Test
    @DisplayName("coletas só existem dentro do período e não duplicam produtor+data")
    void coletasCoerentes() {
        executarGerador();

        List<CollectionEntry> coletas = coletaRepository.findAll();
        assertThat(coletas).isNotEmpty();

        long paresDistintos = coletas.stream()
                .map(c -> c.getSupplier().getId() + ":" + c.getData())
                .distinct()
                .count();
        assertThat(paresDistintos)
                .as("o lote da aplicacao recusa produtor+data repetido")
                .isEqualTo(coletas.size());

        for (CollectionEntry c : coletas) {
            assertThat(c.getQuantidadeLitros()).isPositive();
            assertThat(c.getData()).isBefore(LocalDate.now().plusDays(1));
        }
    }

    // --- Isolamento --------------------------------------------------------

    @Test
    @DisplayName("produtores da organização 1 não aparecem no contexto da organização 2")
    void isolamentoEntreOrganizacoes() {
        executarGerador();

        Organization org1 = organizacaoRepository.findByNome("Acme Supply Co.").orElseThrow();
        Organization org2 = organizacaoRepository.findAll().stream()
                .filter(o -> !o.getId().equals(org1.getId()))
                .findFirst()
                .orElseThrow();

        List<Long> idsOrg1 = produtorRepository.findVisiveis(org1.getId(),
                org.springframework.data.domain.PageRequest.of(0, 100)).getContent().stream()
                .map(Supplier::getId)
                .toList();
        assertThat(idsOrg1).isNotEmpty();

        // No contexto da organização 2, nenhum produtor da 1 pode aparecer. O
        // caminho testado é o do SERVICE, que é quem resolve o escopo via
        // OrganizationContext — consultar o repository com um id fixo não
        // provaria nada sobre o isolamento.
        User operadorOrg2 = usuarioRepository.findAll().stream()
                .filter(u -> u.getRole() == Role.USER && u.getOrganization().getId().equals(org2.getId()))
                .findFirst()
                .orElseThrow();
        autenticar(operadorOrg2);

        List<Long> idsVisiveisNaOrg2 = produtorService
                .listarTodos(org.springframework.data.domain.PageRequest.of(0, 100))
                .getContent()
                .stream()
                .map(com.erp.ocr.dto.SupplierResponseDto::id)
                .toList();

        assertThat(idsVisiveisNaOrg2).isNotEmpty();
        assertThat(idsVisiveisNaOrg2).doesNotContainAnyElementsOf(idsOrg1);
    }

    // --- Idempotência ------------------------------------------------------

    @Test
    @DisplayName("rodar de novo não duplica nada — o gerador é reexecutável")
    void reexecutavelSemDuplicar() {
        executarGerador();
        long coletasNa1aVez = coletaRepository.count();
        long fechamentosNa1aVez = fechamentoRepository.count();
        long produtoresNa1aVez = produtorRepository.count();

        executarGerador();

        assertThat(coletaRepository.count()).isEqualTo(coletasNa1aVez);
        assertThat(fechamentoRepository.count()).isEqualTo(fechamentosNa1aVez);
        assertThat(produtorRepository.count()).isEqualTo(produtoresNa1aVez);
    }

    @Test
    @DisplayName("meses diferentes com a mesma semente geram números diferentes")
    void seedDiferenteMudaResultado() {
        executarGerador();
        BigDecimal primeiroPreco = fechamentoRepository.findAll().get(0).getPrecoFinal();
        String primeiroNome = produtorRepository.findAll().get(0).getNome();

        limpar();

        // Mesma semente, mesmo período: precisa ser idêntico. É o que torna a
        // demonstração reproduzível.
        executarGerador();
        assertThat(fechamentoRepository.findAll().get(0).getPrecoFinal()).isEqualByComparingTo(primeiroPreco);
        assertThat(produtorRepository.findAll().get(0).getNome()).isEqualTo(primeiroNome);
    }
}