package com.erp.ocr.service;

import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.erp.ocr.dto.CreateSettlementDto;
import com.erp.ocr.dto.UpdateSettlementDto;
import com.erp.ocr.dto.MonthlySettlementResponseDto;
import com.erp.ocr.model.CollectionEntry;
import com.erp.ocr.model.Deduction; // Import
import com.erp.ocr.dto.DeductionDto; // Import
import com.erp.ocr.model.MonthlySettlement;
import com.erp.ocr.model.Supplier;
import com.erp.ocr.repository.CollectionEntryRepository;
import com.erp.ocr.repository.MonthlySettlementRepository;
import com.erp.ocr.repository.SupplierRepository;
import com.erp.ocr.util.DateRangeUtil;
import com.erp.ocr.util.MoneyUtil;
import jakarta.persistence.EntityNotFoundException;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class MonthlySettlementService {

    @Autowired
    private MonthlySettlementRepository fechamentoRepository;

    @Autowired
    private CollectionEntryRepository coletaRepository;

    @Autowired
    private SupplierRepository produtorRepository;

    /**
     * Organização do usuário autenticado. Fechamento herda a organização do
     * produtor; toda listagem e todo acesso por id passam por aqui.
     */
    @Autowired
    private com.erp.ocr.config.OrganizationContext organizacaoContexto;

    @Transactional
    public MonthlySettlementResponseDto criarFechamento(CreateSettlementDto dto) {
        Supplier produtor = findSupplierById(dto.produtorId());

        // Sem esta checagem um periodo invertido (dataInicio > dataFim) caia no
        // findBySupplierAndPeriodo vazio e o usuario recebia "nao ha coletas no
        // periodo", mensagem enganosa que escondia o erro real.
        if (dto.dataInicio().isAfter(dto.dataFim())) {
            throw new IllegalArgumentException("A data de início não pode ser posterior à data de fim.");
        }

        if (fechamentoRepository.existsBySupplierAndPeriodoOverlap(dto.produtorId(), dto.dataInicio(),
                dto.dataFim(), organizacaoContexto.organizacaoId())) {
            throw new IllegalArgumentException("Já existe um fechamento para este produtor neste período.");
        }

        List<CollectionEntry> coletasDoPeriodo = coletaRepository.findVisiveisPorSupplierEPeriodo(
                dto.produtorId(), dto.dataInicio(), dto.dataFim(), organizacaoContexto.organizacaoId());
        // long em vez de int: mesmo com o teto de 100.000 L/dia, a soma em int
        // ainda podia estourar num periodo longo e virar total negativo.
        long totalLitrosLong = coletasDoPeriodo.stream()
                .mapToLong(c -> (long) Math.max(c.getQuantidadeLitros(), 0))
                .sum();
        if (totalLitrosLong <= 0) {
            throw new IllegalStateException("Não há coletas para este produtor no período especificado.");
        }
        int totalLitros = Math.toIntExact(Math.min(totalLitrosLong, Integer.MAX_VALUE));

        MonthlySettlement fechamento = new MonthlySettlement();
        fechamento.setSupplier(produtor);
        fechamento.setDataInicio(dto.dataInicio());
        fechamento.setDataFim(dto.dataFim());
        // Ancora o fechamento no mes em que o produtor e EFFECTIVAMENTE pago,
        // que e a dataFim. Periodos que atravessam meses sao normais aqui (ex.:
        // leite produzido entre o dia 20 e o dia 20 do mes seguinte) e ancorar
        // no dataInicio deslocava o registro em ~20 dias nos relatorios e no
        // card de fechamentos pendentes do Dashboard. Afeta apenas registros
        // novos: os ja gravados mantem o mes/ano antigos.
        fechamento.setMes(dto.dataFim().getMonthValue());
        fechamento.setAno(dto.dataFim().getYear());
        fechamento.setTotalLitros(totalLitros);

        // Métricas de qualidade só existem para quem adere ao sistema. Gravar
        // 0 (ou qualquer valor) para produtor não-quality produzia registro com
        // medição que nunca houve, e o itemizado do relatório imprimia bônus
        // calculados sobre esse zero (a primeira faixa das tabelas).
        if (produtor.isUsaPagamentoPorQualidade()) {
            fechamento.setCbt(dto.cbt());
            fechamento.setCcs(dto.ccs());
            fechamento.setGordura(dto.gordura());
            fechamento.setProteina(dto.proteina());
        }

        // Bônus comerciais (volume/fidelidade) valem para qualquer produtor:
        // são decisão do usuário no lançamento, não tabela por qualidade.
        fechamento.setBonusVolume(dto.bonusVolume());
        fechamento.setBonusFidelidade(dto.bonusFidelidade());

        // Estratégia de Preço
        com.erp.ocr.strategy.PricingStrategy strategy;
        if (produtor.isUsaPagamentoPorQualidade()) {
            strategy = new com.erp.ocr.strategy.DefaultTieredPricingStrategy();
        } else {
            strategy = new com.erp.ocr.strategy.StandardPricingStrategy();
        }

        BigDecimal precoFinal = MoneyUtil
                .roundPricePerLiter(strategy.calculateFinalPrice(dto.precoLitro(), fechamento,
                        dto.bonusVolume(), dto.bonusFidelidade()));
        exigirPrecoPositivo(precoFinal);
        fechamento.setPrecoLitro(dto.precoLitro()); // Preço base informado
        fechamento.setPrecoFinal(precoFinal); // Preço com bônus aplicados

        BigDecimal totalBruto = MoneyUtil.round(precoFinal.multiply(new BigDecimal(totalLitros)));
        fechamento.setTotalBruto(totalBruto);
        fechamento.setDeductions(MoneyUtil.round(BigDecimal.ZERO));
        fechamento.setTotalLiquido(totalBruto);
        fechamento.setStatusPagamento("Pendente");

        MonthlySettlement fechamentoSalvo = fechamentoRepository.save(fechamento);
        return toResponseDto(fechamentoSalvo);
    }

    @Transactional
    public MonthlySettlementResponseDto atualizarPagamento(Long id, UpdateSettlementDto dto) {
        MonthlySettlement fechamento = findFechamentoById(id);
        Supplier produtor = fechamento.getSupplier();

        // Qualquer campo que componha o preço (preço base, bônus de qualidade,
        // volume ou fidelidade) muda o preço final e, por consequência, o total
        // bruto. Antes a recomputação só ocorria quando precoLitro vinha no
        // payload: editar só o bônus de volume gravava o bônus mas deixava
        // precoFinal/totalBruto com os valores antigos, então o produtor não
        // recebia o valor ajustado.
        boolean precoAfetado = false;

        // Métricas de qualidade só existem para quem adere ao sistema. Sem este
        // guard, uma edição de produtor não-quality gravava cbt/ccs/gordura/
        // proteina (o formulárioHidden os mandava com || 0) — bônus gravado que
        // StandardPricingStrategy nunca aplica e que o itemizado impresso mostrava
        // como se fossem reais.
        if (produtor.isUsaPagamentoPorQualidade()) {
            if (dto.cbt() != null) {
                fechamento.setCbt(dto.cbt());
                precoAfetado = true;
            }
            if (dto.ccs() != null) {
                fechamento.setCcs(dto.ccs());
                precoAfetado = true;
            }
            if (dto.gordura() != null) {
                fechamento.setGordura(dto.gordura());
                precoAfetado = true;
            }
            if (dto.proteina() != null) {
                fechamento.setProteina(dto.proteina());
                precoAfetado = true;
            }
        }

        // Bônus comerciais (volume/fidelidade) valem para qualquer produtor.
        if (dto.bonusVolume() != null) {
            fechamento.setBonusVolume(dto.bonusVolume());
            precoAfetado = true;
        }
        if (dto.bonusFidelidade() != null) {
            fechamento.setBonusFidelidade(dto.bonusFidelidade());
            precoAfetado = true;
        }
        if (dto.precoLitro() != null) {
            fechamento.setPrecoLitro(dto.precoLitro());
            precoAfetado = true;
        }

        if (precoAfetado) {
            recomputarPrecoETotalBruto(fechamento, produtor);
        }

        aplicarDeductions(fechamento, dto);

        BigDecimal totalBruto = fechamento.getTotalBruto();
        BigDecimal descontos = fechamento.getDeductions();
        exigirDeductionsValidos(totalBruto, descontos);
        fechamento.setTotalLiquido(MoneyUtil.round(totalBruto.subtract(descontos)));

        // Atualizar status de pagamento
        if (dto.statusPagamento() != null) {
            fechamento.setStatusPagamento(dto.statusPagamento());
        }
        if (dto.dataPagamento() != null) {
            fechamento.setDataPagamento(dto.dataPagamento());
        }

        MonthlySettlement fechamentoAtualizado = fechamentoRepository.save(fechamento);
        return toResponseDto(fechamentoAtualizado);
    }

    /**
     * Recalcula precoFinal e totalBruto a partir do precoLitro, dos bônus e das
     * métricas já gravados no fechamento, escolhendo a estratégia pela adesão
     * do produtor ao sistema de qualidade.
     */
    private void recomputarPrecoETotalBruto(MonthlySettlement fechamento, Supplier produtor) {
        com.erp.ocr.strategy.PricingStrategy strategy;
        if (produtor.isUsaPagamentoPorQualidade()) {
            strategy = new com.erp.ocr.strategy.DefaultTieredPricingStrategy();
        } else {
            strategy = new com.erp.ocr.strategy.StandardPricingStrategy();
        }

        BigDecimal precoFinal = MoneyUtil.roundPricePerLiter(strategy.calculateFinalPrice(
                fechamento.getPrecoLitro(),
                fechamento,
                fechamento.getBonusVolume(),
                fechamento.getBonusFidelidade()));
        exigirPrecoPositivo(precoFinal);
        fechamento.setPrecoFinal(precoFinal);

        BigDecimal totalBruto = MoneyUtil.round(precoFinal.multiply(new BigDecimal(fechamento.getTotalLitros())));
        fechamento.setTotalBruto(totalBruto);
    }

    /**
     * Aplica a lista detalhada de descontos quando enviada; caso contrário usa o
     * valor total (legado/simples). Não recalcula totalLiquido — quem chama
     * valida os descontos e deriva o líquido do bruto já atualizado.
     */
    private void aplicarDeductions(MonthlySettlement fechamento, UpdateSettlementDto dto) {
        if (dto.listaDeductions() != null) {
            fechamento.getListaDeductions().clear();
            BigDecimal totalDeductions = BigDecimal.ZERO;
            for (DeductionDto descontoDto : dto.listaDeductions()) {
                Deduction desconto = new Deduction();
                desconto.setDescricao(descontoDto.descricao());
                desconto.setValor(descontoDto.valor());
                desconto.setSettlement(fechamento);
                fechamento.getListaDeductions().add(desconto);
                totalDeductions = totalDeductions.add(descontoDto.valor());
            }
            fechamento.setDeductions(MoneyUtil.round(totalDeductions));
        } else if (dto.descontos() != null) {
            // Fallback para caso envie apenas o valor total (legado/simples)
            fechamento.setDeductions(MoneyUtil.round(dto.descontos()));
        }
    }

    public org.springframework.data.domain.Page<MonthlySettlementResponseDto> listarComFiltros(
            Long produtorId, java.time.LocalDate inicio, java.time.LocalDate fim,
            org.springframework.data.domain.Pageable pageable) {

        // Filtro de organização e os demais filtros na MESMA query: não há
        // caminho "sem escopo" separado que possa esquecer de filtrar.
        //
        // O período vira sempre um intervalo fechado (limites sentinela no
        // lugar de null): o "filtro opcional" via `(:param IS NULL OR ...)`
        // quebra no CockroachDB quando o parâmetro é LocalDate. Ver
        // DateRangeUtil e a issue #47.
        return fechamentoRepository
                .findVisiveisComFiltros(organizacaoContexto.organizacaoId(), produtorId,
                        DateRangeUtil.inicio(inicio), DateRangeUtil.fim(fim), pageable)
                .map(this::toResponseDto);
    }

    public MonthlySettlementResponseDto buscarPorId(Long id) {
        MonthlySettlement fechamento = findFechamentoById(id);
        return toResponseDto(fechamento);
    }

    @Transactional
    public void deletar(Long id) {
        // findFechamentoById é escopado por organização: um id de outra
        // organização devolve EntityNotFoundException (404), que é o que esconde
        // a existência do registro. Com existsById/deleteById, o USER apagava o
        // fechamento alheio e recebia 204, confirmando o id.
        MonthlySettlement fechamento = findFechamentoById(id);
        fechamentoRepository.delete(fechamento);
    }

    // --- Métodos Auxiliares ---

    /**
     * Impede gravar fechamento com preço por litro negativo ou zerado. Os
     * bônus são somados sem clamp em {@code DefaultTieredPricingStrategy}, então um
     * bônus muito negativo deixaria precoFinal negativo e, derivando dele,
     * totalBruto e totalLiquido negativos num fechamento real.
     */
    private void exigirPrecoPositivo(BigDecimal precoFinal) {
        if (precoFinal == null || precoFinal.signum() <= 0) {
            throw new IllegalArgumentException(
                    "O preço final por litro calculado ficou em " + precoFinal
                            + ". Revise os bônus de qualidade/volume/fidelidade informados.");
        }
    }

    /**
     * Impede descontos negativos (que creditariam o produtor com dinheiro
     * nunca descontado, deixando totalLiquido maior que totalBruto) e
     * descontos acima do total bruto.
     */
    private void exigirDeductionsValidos(BigDecimal totalBruto, BigDecimal descontos) {
        if (descontos == null) {
            return;
        }
        if (descontos.signum() < 0) {
            throw new IllegalArgumentException("O total de descontos não pode ser negativo.");
        }
        if (totalBruto != null && descontos.compareTo(totalBruto) > 0) {
            throw new IllegalArgumentException(
                    "O total de descontos (" + MoneyUtil.round(descontos)
                            + ") não pode ser maior que o total bruto (" + MoneyUtil.round(totalBruto) + ").");
        }
    }

    private MonthlySettlement findFechamentoById(Long id) {
        return fechamentoRepository.findVisivelById(id, organizacaoContexto.organizacaoId())
                .orElseThrow(() -> new EntityNotFoundException("Fechamento não encontrado com o ID: " + id));
    }

    private Supplier findSupplierById(Long id) {
        return produtorRepository.findVisivelById(id, organizacaoContexto.organizacaoId())
                .orElseThrow(() -> new EntityNotFoundException("Supplier não encontrado com o ID: " + id));
    }

    private MonthlySettlementResponseDto toResponseDto(MonthlySettlement fechamento) {
        List<DeductionDto> descontosDto = fechamento.getListaDeductions().stream()
                .map(d -> new DeductionDto(d.getId(), d.getDescricao(), d.getValor()))
                .collect(Collectors.toList());

        return new MonthlySettlementResponseDto(
                fechamento.getId(),
                fechamento.getMes(),
                fechamento.getAno(),
                fechamento.getDataInicio(),
                fechamento.getDataFim(),
                fechamento.getTotalLitros(),
                fechamento.getPrecoLitro(),
                fechamento.getTotalBruto(),
                fechamento.getDeductions(),
                descontosDto, // Passando a lista de descontos
                fechamento.getTotalLiquido(),
                fechamento.getStatusPagamento(),
                fechamento.getDataPagamento(),
                fechamento.getSupplier().getId(),
                fechamento.getSupplier().getNome(),
                fechamento.getCbt(),
                fechamento.getCcs(),
                fechamento.getGordura(),
                fechamento.getProteina(),
                fechamento.getBonusVolume(),
                fechamento.getBonusFidelidade(),
                fechamento.getPrecoFinal(),
                (fechamento.getSupplier().isUsaPagamentoPorQualidade() ? "Qualidade" : "Padrão"));
    }
}