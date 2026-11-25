package com.erp.ocr.service;

import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import com.erp.ocr.dto.CreateSettlementDto;
import com.erp.ocr.dto.MonthlySettlementResponseDto;
import com.erp.ocr.model.CollectionEntry;
import com.erp.ocr.model.MonthlySettlement;
import com.erp.ocr.model.Supplier;
import com.erp.ocr.repository.CollectionEntryRepository;
import com.erp.ocr.repository.MonthlySettlementRepository;
import com.erp.ocr.repository.SupplierRepository;
import com.erp.ocr.util.DateRangeUtil;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MonthlySettlementServiceTest {

    @InjectMocks
    private MonthlySettlementService fechamentoService;

    @Mock
    private MonthlySettlementRepository fechamentoRepository;

    @Mock
    private CollectionEntryRepository coletaRepository;

    @Mock
    private SupplierRepository produtorRepository;

    // Contexto de organização em modo ADMIN (organizacaoId() = null => sem
    // filtro). Os testes desta classe não são de isolamento; para isso existe
    // OrganizationIsolationTest, que sobe o contexto Spring inteiro.
    @Mock
    private com.erp.ocr.config.OrganizationContext organizacaoContexto;

    private final Long orgId = null;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(organizacaoContexto.organizacaoId()).thenReturn(orgId);
    }

    @Test
    void criarFechamento_DeveCalcularValoresCorretamente() {
        Long produtorId = 1L;
        LocalDate start = LocalDate.of(2023, 10, 1);
        LocalDate end = LocalDate.of(2023, 10, 31);
        BigDecimal precoLitro = new BigDecimal("2.50");
        CreateSettlementDto dto = new CreateSettlementDto(produtorId, start, end, precoLitro,
                null, null, null, null, null, null);

        Supplier produtor = new Supplier();
        produtor.setId(produtorId);

        CollectionEntry c1 = new CollectionEntry(1L, LocalDate.of(2023, 10, 1), 100, produtor);
        CollectionEntry c2 = new CollectionEntry(2L, LocalDate.of(2023, 10, 2), 200, produtor);

        when(produtorRepository.findVisivelById(produtorId, orgId)).thenReturn(Optional.of(produtor));
        when(fechamentoRepository.existsBySupplierAndPeriodoOverlap(produtorId, start, end, orgId)).thenReturn(false);
        when(coletaRepository.findVisiveisPorSupplierEPeriodo(produtorId, start, end, orgId)).thenReturn(Arrays.asList(c1, c2));

        when(fechamentoRepository.save(any(MonthlySettlement.class))).thenAnswer(invocation -> {
            MonthlySettlement f = invocation.getArgument(0);
            f.setId(100L);
            f.setListaDeductions(new java.util.ArrayList<>());
            return f;
        });

        MonthlySettlementResponseDto response = fechamentoService.criarFechamento(dto);

        assertNotNull(response);
        assertEquals(300, response.totalLitros());
        assertEquals(new BigDecimal("750.00"), response.totalBruto()); // 300 * 2.50
        assertEquals(new BigDecimal("750.00"), response.totalLiquido()); // sem desconto
        assertEquals("Pendente", response.statusPagamento());
    }

    @Test
    void criarFechamento_DeveAplicarBonusVolumeEFidelidade() {
        Long produtorId = 1L;
        LocalDate start = LocalDate.of(2023, 10, 1);
        LocalDate end = LocalDate.of(2023, 10, 31);
        BigDecimal precoLitro = new BigDecimal("2.50");
        BigDecimal bonusVolume = new BigDecimal("0.10");
        BigDecimal bonusFidelidade = new BigDecimal("0.05");
        CreateSettlementDto dto = new CreateSettlementDto(produtorId, start, end, precoLitro,
                null, null, null, null, bonusVolume, bonusFidelidade);

        Supplier produtor = new Supplier();
        produtor.setId(produtorId);
        produtor.setUsaPagamentoPorQualidade(true); // Ativa estratégia de qualidade

        CollectionEntry c1 = new CollectionEntry(1L, LocalDate.of(2023, 10, 1), 100, produtor);

        when(produtorRepository.findVisivelById(produtorId, orgId)).thenReturn(Optional.of(produtor));
        when(fechamentoRepository.existsBySupplierAndPeriodoOverlap(produtorId, start, end, orgId)).thenReturn(false);
        when(coletaRepository.findVisiveisPorSupplierEPeriodo(produtorId, start, end, orgId)).thenReturn(Arrays.asList(c1));

        when(fechamentoRepository.save(any(MonthlySettlement.class))).thenAnswer(invocation -> {
            MonthlySettlement f = invocation.getArgument(0);
            f.setId(100L);
            f.setListaDeductions(new java.util.ArrayList<>());
            return f;
        });

        MonthlySettlementResponseDto response = fechamentoService.criarFechamento(dto);

        assertNotNull(response);
        assertEquals(bonusVolume, response.bonusVolume());
        assertEquals(bonusFidelidade, response.bonusFidelidade());
        // Preço final: 2.50 + 0.10 + 0.05 = 2.65, rounded to the 4-decimal
        // per-liter price scale (MoneyUtil.roundPricePerLiter).
        assertEquals(new BigDecimal("2.6500"), response.precoFinal());
    }

    @Test
    void criarFechamento_DeveLancarExcecao_QuandoSemColetas() {
        Long produtorId = 1L;
        LocalDate start = LocalDate.of(2023, 10, 1);
        LocalDate end = LocalDate.of(2023, 10, 31);
        CreateSettlementDto dto = new CreateSettlementDto(produtorId, start, end, BigDecimal.TEN,
                null, null, null, null, null, null);
        Supplier produtor = new Supplier();
        produtor.setId(produtorId);

        when(produtorRepository.findVisivelById(produtorId, orgId)).thenReturn(Optional.of(produtor));
        when(fechamentoRepository.existsBySupplierAndPeriodoOverlap(produtorId, start, end, orgId)).thenReturn(false);
        when(coletaRepository.findVisiveisPorSupplierEPeriodo(anyLong(), any(), any(), any())).thenReturn(Collections.emptyList());

        assertThrows(IllegalStateException.class, () -> fechamentoService.criarFechamento(dto));
    }

    @Test
    void atualizarPagamento_DeveAtualizarDeductions() {
        Long fechamentoId = 100L;
        Long produtorId = 1L;
        MonthlySettlement fechamento = new MonthlySettlement();
        fechamento.setId(fechamentoId);
        fechamento.setTotalLitros(100);
        fechamento.setPrecoFinal(new BigDecimal("2.00")); // Total Bruto = 200.00
        fechamento.setTotalBruto(new BigDecimal("200.00"));
        fechamento.setDeductions(BigDecimal.ZERO);
        fechamento.setTotalLiquido(new BigDecimal("200.00"));

        Supplier produtor = new Supplier();
        produtor.setId(produtorId);
        produtor.setUsaPagamentoPorQualidade(false); // Add this to avoid NPE in toResponseDto
        fechamento.setSupplier(produtor);

        fechamento.setListaDeductions(new java.util.ArrayList<>());
        // Initial dates setup to avoid NPE in toResponseDto
        fechamento.setDataInicio(LocalDate.now());
        fechamento.setDataFim(LocalDate.now());

        com.erp.ocr.dto.DeductionDto desc1 = new com.erp.ocr.dto.DeductionDto(null,
                "Adiantamento", new BigDecimal("50.00"));
        com.erp.ocr.dto.UpdateSettlementDto dto = new com.erp.ocr.dto.UpdateSettlementDto(
                null, Arrays.asList(desc1), null, null, null, null, null, null, null, null, null);

        when(fechamentoRepository.findVisivelById(fechamentoId, orgId)).thenReturn(Optional.of(fechamento));
        when(fechamentoRepository.save(any(MonthlySettlement.class))).thenAnswer(i -> i.getArgument(0));

        MonthlySettlementResponseDto response = fechamentoService.atualizarPagamento(fechamentoId, dto);

        assertNotNull(response);
        assertEquals(1, response.listaDeductions().size());
        assertEquals("Adiantamento", response.listaDeductions().get(0).descricao());
        assertEquals(new BigDecimal("50.00"), response.descontos());
        assertEquals(new BigDecimal("150.00"), response.totalLiquido());
    }

    @Test
    void listarComFiltros_SemFiltros_DeveChamarFindAll() {
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.Pageable.unpaged();
        // Sem filtro de período, a query recebe os limites sentinela: o
        // "filtro opcional" via `(:param IS NULL OR ...)` quebra no
        // CockroachDB quando o parâmetro é LocalDate (issue #47).
        when(fechamentoRepository.findVisiveisComFiltros(orgId, null,
                DateRangeUtil.SEM_LIMITE_INICIAL, DateRangeUtil.SEM_LIMITE_FINAL, pageable))
                .thenReturn(org.springframework.data.domain.Page.empty());

        fechamentoService.listarComFiltros(null, null, null, pageable);

        verify(fechamentoRepository).findVisiveisComFiltros(orgId, null,
                DateRangeUtil.SEM_LIMITE_INICIAL, DateRangeUtil.SEM_LIMITE_FINAL, pageable);
    }

    @Test
    void listarComFiltros_ApenasSupplierId_DeveChamarFindBySupplierId() {
        Long produtorId = 1L;
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.Pageable.unpaged();
        when(fechamentoRepository.findVisiveisComFiltros(orgId, produtorId,
                DateRangeUtil.SEM_LIMITE_INICIAL, DateRangeUtil.SEM_LIMITE_FINAL, pageable))
                .thenReturn(org.springframework.data.domain.Page.empty());

        fechamentoService.listarComFiltros(produtorId, null, null, pageable);

        verify(fechamentoRepository).findVisiveisComFiltros(orgId, produtorId,
                DateRangeUtil.SEM_LIMITE_INICIAL, DateRangeUtil.SEM_LIMITE_FINAL, pageable);
    }

    @Test
    void listarComFiltros_ApenasPeriodo_DeveChamarFindByPeriodo() {
        LocalDate inicio = LocalDate.of(2023, 10, 1);
        LocalDate fim = LocalDate.of(2023, 10, 31);
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.Pageable.unpaged();
        when(fechamentoRepository.findVisiveisComFiltros(orgId, null, inicio, fim, pageable))
                .thenReturn(org.springframework.data.domain.Page.empty());

        fechamentoService.listarComFiltros(null, inicio, fim, pageable);

        verify(fechamentoRepository).findVisiveisComFiltros(orgId, null, inicio, fim, pageable);
    }

    @Test
    void listarComFiltros_SupplierIdEPeriodo_DeveChamarFindBySupplierIdAndPeriodo() {
        Long produtorId = 1L;
        LocalDate inicio = LocalDate.of(2023, 10, 1);
        LocalDate fim = LocalDate.of(2023, 10, 31);
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.Pageable.unpaged();
        when(fechamentoRepository.findVisiveisComFiltros(orgId, produtorId, inicio, fim, pageable))
                .thenReturn(org.springframework.data.domain.Page.empty());

        fechamentoService.listarComFiltros(produtorId, inicio, fim, pageable);

        verify(fechamentoRepository).findVisiveisComFiltros(orgId, produtorId, inicio, fim, pageable);
    }

    // --- Invariantes financeiras: sem elas, um campo sem limite no DTO virava
    // fechamento com preço ou total negativo gravado em producao. ---

    @Test
    void criarFechamento_DeveRejeitar_PeriodoInvertido() {
        Long produtorId = 1L;
        // dataInicio depois de dataFim: antes caia no findBySupplierAndPeriodo
        // vazio e devolvia "nao ha coletas no periodo", escondendo o erro real.
        CreateSettlementDto dto = new CreateSettlementDto(produtorId,
                LocalDate.of(2023, 10, 31), LocalDate.of(2023, 10, 1), new BigDecimal("2.50"),
                null, null, null, null, null, null);

        Supplier produtor = new Supplier();
        produtor.setId(produtorId);
        when(produtorRepository.findVisivelById(produtorId, orgId)).thenReturn(Optional.of(produtor));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> fechamentoService.criarFechamento(dto));
        assertTrue(ex.getMessage().contains("início"));

        // Nao deve nem chegar a consultar sobreposicao/coletas.
        verify(fechamentoRepository, never()).existsBySupplierAndPeriodoOverlap(anyLong(), any(), any(), any());
    }

    @Test
    void criarFechamento_DeveRejeitar_PrecoFinalNaoPositivo() {
        Long produtorId = 1L;
        LocalDate start = LocalDate.of(2023, 10, 1);
        LocalDate end = LocalDate.of(2023, 10, 31);
        //(precoBase 1.00 + bonusVolume -10 + bonusFidelidade -10) = -19.00
        CreateSettlementDto dto = new CreateSettlementDto(produtorId, start, end, new BigDecimal("1.00"),
                null, null, null, null, new BigDecimal("-10.0000"), new BigDecimal("-10.0000"));

        Supplier produtor = new Supplier();
        produtor.setId(produtorId);
        // So a estrategia de qualidade soma os bonuses; a padrao ignora e
        // devolveria precoBase inalterado, mascarando o teste.
        produtor.setUsaPagamentoPorQualidade(true);

        CollectionEntry c1 = new CollectionEntry(1L, start, 100, produtor);

        when(produtorRepository.findVisivelById(produtorId, orgId)).thenReturn(Optional.of(produtor));
        when(fechamentoRepository.existsBySupplierAndPeriodoOverlap(produtorId, start, end, orgId)).thenReturn(false);
        when(coletaRepository.findVisiveisPorSupplierEPeriodo(produtorId, start, end, orgId)).thenReturn(Arrays.asList(c1));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> fechamentoService.criarFechamento(dto));
        assertTrue(ex.getMessage().contains("preço final"));

        verify(fechamentoRepository, never()).save(any(MonthlySettlement.class));
    }

    @Test
    void criarFechamento_DeveSomarLitrosEmLong_EvitarOverflowDeInt() {
        Long produtorId = 1L;
        LocalDate start = LocalDate.of(2023, 10, 1);
        LocalDate end = LocalDate.of(2023, 10, 31);
        CreateSettlementDto dto = new CreateSettlementDto(produtorId, start, end, new BigDecimal("2.50"),
                null, null, null, null, null, null);

        Supplier produtor = new Supplier();
        produtor.setId(produtorId);

        // 30 coletas de 100.000 L = 3.000.000 L. Em int nao estouraria, mas a
        // soma em long e o clamp final garantem que o valor persistido nunca
        // vira negativo por estouro.
        List<CollectionEntry> coletas = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            coletas.add(new CollectionEntry((long) (i + 1), start.plusDays(i), 100_000, produtor));
        }

        when(produtorRepository.findVisivelById(produtorId, orgId)).thenReturn(Optional.of(produtor));
        when(fechamentoRepository.existsBySupplierAndPeriodoOverlap(produtorId, start, end, orgId)).thenReturn(false);
        when(coletaRepository.findVisiveisPorSupplierEPeriodo(produtorId, start, end, orgId)).thenReturn(coletas);
        when(fechamentoRepository.save(any(MonthlySettlement.class))).thenAnswer(invocation -> {
            MonthlySettlement f = invocation.getArgument(0);
            f.setId(100L);
            f.setListaDeductions(new java.util.ArrayList<>());
            return f;
        });

        MonthlySettlementResponseDto response = fechamentoService.criarFechamento(dto);

        assertEquals(3_000_000, response.totalLitros());
        assertTrue(response.totalBruto().signum() > 0, "total bruto nunca deve ser negativo");
    }

    @Test
    void atualizarPagamento_DeveRejeitar_DeductionNegativo() {
        MonthlySettlement fechamento = fechamentoComTotais(new BigDecimal("200.00"));
        // Deduction negativo faria totalLiquido > totalBruto: o produtor seria
        // creditado com dinheiro que nunca foi descontado.
        com.erp.ocr.dto.UpdateSettlementDto dto = new com.erp.ocr.dto.UpdateSettlementDto(
                new BigDecimal("-50.00"), null, null, null, null, null, null, null, null, null, null);

        when(fechamentoRepository.findVisivelById(100L, orgId)).thenReturn(Optional.of(fechamento));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> fechamentoService.atualizarPagamento(100L, dto));
        assertTrue(ex.getMessage().contains("negativo"));

        verify(fechamentoRepository, never()).save(any(MonthlySettlement.class));
    }

    @Test
    void atualizarPagamento_DeveRejeitar_DeductionMaiorQueTotalBruto() {
        MonthlySettlement fechamento = fechamentoComTotais(new BigDecimal("200.00"));
        com.erp.ocr.dto.UpdateSettlementDto dto = new com.erp.ocr.dto.UpdateSettlementDto(
                new BigDecimal("500.00"), null, null, null, null, null, null, null, null, null, null);

        when(fechamentoRepository.findVisivelById(100L, orgId)).thenReturn(Optional.of(fechamento));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> fechamentoService.atualizarPagamento(100L, dto));
        assertTrue(ex.getMessage().contains("descontos"));

        verify(fechamentoRepository, never()).save(any(MonthlySettlement.class));
    }

    // --- Bônus comerciais valem para QUALQUER produtor (R1): o
    // StandardPricingStrategy recebia bonusVolume/bonusFidelidade na assinatura e
    // simplesmente os ignorava, então o valor nunca chegava ao produtor. ---

    @Test
    void criarFechamento_SupplierNaoQuality_DeveAplicarBonusVolumeEFidelidade() {
        Long produtorId = 1L;
        LocalDate start = LocalDate.of(2026, 6, 20);
        LocalDate end = LocalDate.of(2026, 7, 20);
        // 2.50 base + 0.15 volume + 0.10 fidelidade = 2.75
        CreateSettlementDto dto = new CreateSettlementDto(produtorId, start, end, new BigDecimal("2.50"),
                null, null, null, null, new BigDecimal("0.1500"), new BigDecimal("0.1000"));

        Supplier produtor = new Supplier();
        produtor.setId(produtorId);
        produtor.setUsaPagamentoPorQualidade(false);

        CollectionEntry c1 = new CollectionEntry(1L, start, 1000, produtor);

        when(produtorRepository.findVisivelById(produtorId, orgId)).thenReturn(Optional.of(produtor));
        when(fechamentoRepository.existsBySupplierAndPeriodoOverlap(produtorId, start, end, orgId)).thenReturn(false);
        when(coletaRepository.findVisiveisPorSupplierEPeriodo(produtorId, start, end, orgId)).thenReturn(Arrays.asList(c1));
        when(fechamentoRepository.save(any(MonthlySettlement.class))).thenAnswer(invocation -> {
            MonthlySettlement f = invocation.getArgument(0);
            f.setId(100L);
            f.setListaDeductions(new java.util.ArrayList<>());
            return f;
        });

        MonthlySettlementResponseDto response = fechamentoService.criarFechamento(dto);

        assertEquals(new BigDecimal("2.7500"), response.precoFinal());
        assertEquals(new BigDecimal("0.1500"), response.bonusVolume());
        assertEquals(new BigDecimal("0.1000"), response.bonusFidelidade());
        // 1000 L * 2.75 = 2.750,00
        assertEquals(new BigDecimal("2750.00"), response.totalBruto());
    }

    @Test
    void criarFechamento_SupplierNaoQuality_DeveAceitarBonusNegativoComoPenalidade() {
        Long produtorId = 1L;
        LocalDate start = LocalDate.of(2026, 6, 20);
        LocalDate end = LocalDate.of(2026, 7, 20);
        // 2.50 - 0.30 penalidade = 2.20
        CreateSettlementDto dto = new CreateSettlementDto(produtorId, start, end, new BigDecimal("2.50"),
                null, null, null, null, new BigDecimal("-0.3000"), null);

        Supplier produtor = new Supplier();
        produtor.setId(produtorId);
        produtor.setUsaPagamentoPorQualidade(false);

        CollectionEntry c1 = new CollectionEntry(1L, start, 100, produtor);

        when(produtorRepository.findVisivelById(produtorId, orgId)).thenReturn(Optional.of(produtor));
        when(fechamentoRepository.existsBySupplierAndPeriodoOverlap(produtorId, start, end, orgId)).thenReturn(false);
        when(coletaRepository.findVisiveisPorSupplierEPeriodo(produtorId, start, end, orgId)).thenReturn(Arrays.asList(c1));
        when(fechamentoRepository.save(any(MonthlySettlement.class))).thenAnswer(invocation -> {
            MonthlySettlement f = invocation.getArgument(0);
            f.setId(100L);
            f.setListaDeductions(new java.util.ArrayList<>());
            return f;
        });

        MonthlySettlementResponseDto response = fechamentoService.criarFechamento(dto);

        assertEquals(new BigDecimal("2.2000"), response.precoFinal());
        assertTrue(response.totalBruto().signum() > 0);
    }

    @Test
    void criarFechamento_SupplierNaoQuality_NaoDeveGravarMetricasDeQualidade() {
        Long produtorId = 1L;
        LocalDate start = LocalDate.of(2026, 6, 20);
        LocalDate end = LocalDate.of(2026, 7, 20);
        // O cliente pode mandar métricas mesmo assim; o backend não deve
        // persistir, senão o itemizado impresso calcula bônus de qualidade
        // sobre um zero (getBonusCBT(0) = 0.08).
        CreateSettlementDto dto = new CreateSettlementDto(produtorId, start, end, new BigDecimal("2.50"),
                new BigDecimal("0"), new BigDecimal("0"), new BigDecimal("0"), new BigDecimal("0"),
                null, null);

        Supplier produtor = new Supplier();
        produtor.setId(produtorId);
        produtor.setUsaPagamentoPorQualidade(false);

        CollectionEntry c1 = new CollectionEntry(1L, start, 100, produtor);

        when(produtorRepository.findVisivelById(produtorId, orgId)).thenReturn(Optional.of(produtor));
        when(fechamentoRepository.existsBySupplierAndPeriodoOverlap(produtorId, start, end, orgId)).thenReturn(false);
        when(coletaRepository.findVisiveisPorSupplierEPeriodo(produtorId, start, end, orgId)).thenReturn(Arrays.asList(c1));
        when(fechamentoRepository.save(any(MonthlySettlement.class))).thenAnswer(invocation -> {
            MonthlySettlement f = invocation.getArgument(0);
            f.setId(100L);
            f.setListaDeductions(new java.util.ArrayList<>());
            return f;
        });

        MonthlySettlementResponseDto response = fechamentoService.criarFechamento(dto);

        assertNull(response.cbt());
        assertNull(response.ccs());
        assertNull(response.gordura());
        assertNull(response.proteina());
    }

    @Test
    void atualizarPagamento_SupplierNaoQuality_NaoDeveGravarMetricasDeQualidade() {
        MonthlySettlement fechamento = fechamentoComTotais(new BigDecimal("200.00"));
        // B1: a edição mandava cbt/ccs/gordura/proteina sempre, o que gravava
        // bônus de qualidade num produtor que nunca foi avaliado.
        com.erp.ocr.dto.UpdateSettlementDto dto = new com.erp.ocr.dto.UpdateSettlementDto(
                null, null, null, null, new BigDecimal("2.50"),
                new BigDecimal("0"), new BigDecimal("0"), new BigDecimal("0"), new BigDecimal("0"),
                new BigDecimal("0.1000"), null);

        when(fechamentoRepository.findVisivelById(100L, orgId)).thenReturn(Optional.of(fechamento));
        when(fechamentoRepository.save(any(MonthlySettlement.class))).thenAnswer(i -> i.getArgument(0));

        MonthlySettlementResponseDto response = fechamentoService.atualizarPagamento(100L, dto);

        assertNull(response.cbt());
        assertNull(response.gordura());
        // O bônus comercial DEVE ser aplicado no caminho padrão:
        // preço base 2.50 + volume 0.10 = 2.60
        assertEquals(new BigDecimal("0.1000"), response.bonusVolume());
        assertEquals(new BigDecimal("2.6000"), response.precoFinal());
    }

    @Test
    void criarFechamento_DeveAncorarMesAnoNaDataFim_DePeriodoQueAtravessaMeses() {
        Long produtorId = 1L;
        LocalDate start = LocalDate.of(2026, 6, 20);
        LocalDate end = LocalDate.of(2026, 7, 20);
        CreateSettlementDto dto = new CreateSettlementDto(produtorId, start, end, new BigDecimal("2.50"),
                null, null, null, null, null, null);

        Supplier produtor = new Supplier();
        produtor.setId(produtorId);

        CollectionEntry c1 = new CollectionEntry(1L, start, 100, produtor);

        when(produtorRepository.findVisivelById(produtorId, orgId)).thenReturn(Optional.of(produtor));
        when(fechamentoRepository.existsBySupplierAndPeriodoOverlap(produtorId, start, end, orgId)).thenReturn(false);
        when(coletaRepository.findVisiveisPorSupplierEPeriodo(produtorId, start, end, orgId)).thenReturn(Arrays.asList(c1));
        when(fechamentoRepository.save(any(MonthlySettlement.class))).thenAnswer(invocation -> {
            MonthlySettlement f = invocation.getArgument(0);
            f.setId(100L);
            f.setListaDeductions(new java.util.ArrayList<>());
            return f;
        });

        MonthlySettlementResponseDto response = fechamentoService.criarFechamento(dto);

        // July, não June: o produtor é pago no fim do período, e é isso que o
        // Dashboard e os relatórios ancoram.
        assertEquals(7, response.mes());
        assertEquals(2026, response.ano());
    }

    // --- BT-2 Achado 2: a recomputação do preço só rodava quando precoLitro
    // vinha no payload. Editar apenas bônus/métrica gravava o novo valor mas
    // deixava precoFinal/totalBruto antigos. ---

    @Test
    void atualizarPagamento_DeveRecalcular_QuandoApenasBonusVolumeMuda() {
        MonthlySettlement fechamento = fechamentoComTotais(new BigDecimal("200.00"));
        // Sem precoLitro no payload: antes o preço/total ficavam intocados e o
        // bônus gravado (2.00 + 0.50) nunca chegava ao totalBruto.
        com.erp.ocr.dto.UpdateSettlementDto dto = new com.erp.ocr.dto.UpdateSettlementDto(
                null, null, null, null, null, null, null, null, null,
                new BigDecimal("0.5000"), null);

        when(fechamentoRepository.findVisivelById(100L, orgId)).thenReturn(Optional.of(fechamento));
        when(fechamentoRepository.save(any(MonthlySettlement.class))).thenAnswer(i -> i.getArgument(0));

        MonthlySettlementResponseDto response = fechamentoService.atualizarPagamento(100L, dto);

        assertEquals(new BigDecimal("0.5000"), response.bonusVolume());
        assertEquals(new BigDecimal("2.5000"), response.precoFinal());
        // 100 L * 2.50 = 250.00
        assertEquals(new BigDecimal("250.00"), response.totalBruto());
        assertEquals(new BigDecimal("250.00"), response.totalLiquido());
    }

    @Test
    void atualizarPagamento_DeveRecalcular_QuandoApenasMetricaDeQualidadeMuda() {
        MonthlySettlement fechamento = fechamentoComTotais(new BigDecimal("200.00"));
        fechamento.getSupplier().setUsaPagamentoPorQualidade(true);
        // gordura 4.20 => bônus de qualidade +0.04; sem precoLitro no payload.
        com.erp.ocr.dto.UpdateSettlementDto dto = new com.erp.ocr.dto.UpdateSettlementDto(
                null, null, null, null, null, null, null, new BigDecimal("4.20"), null, null, null);

        when(fechamentoRepository.findVisivelById(100L, orgId)).thenReturn(Optional.of(fechamento));
        when(fechamentoRepository.save(any(MonthlySettlement.class))).thenAnswer(i -> i.getArgument(0));

        MonthlySettlementResponseDto response = fechamentoService.atualizarPagamento(100L, dto);

        assertEquals(new BigDecimal("4.20"), response.gordura());
        assertEquals(new BigDecimal("2.0400"), response.precoFinal());
        assertEquals(new BigDecimal("204.00"), response.totalBruto());
    }

    @Test
    void atualizarPagamento_DeveRejeitar_PrecoFinalNaoPositivo_QuandoBonusNegativo() {
        MonthlySettlement fechamento = fechamentoComTotais(new BigDecimal("200.00"));
        fechamento.getSupplier().setUsaPagamentoPorQualidade(true);
        // precoBase 2.00 - 10.00 = -8.00: o bônus sozinho não pode zerar o preço.
        com.erp.ocr.dto.UpdateSettlementDto dto = new com.erp.ocr.dto.UpdateSettlementDto(
                null, null, null, null, null, null, null, null, null,
                new BigDecimal("-10.0000"), null);

        when(fechamentoRepository.findVisivelById(100L, orgId)).thenReturn(Optional.of(fechamento));

        assertThrows(IllegalArgumentException.class,
                () -> fechamentoService.atualizarPagamento(100L, dto));
        verify(fechamentoRepository, never()).save(any(MonthlySettlement.class));
    }

    // --- BT-2 Achado 1: deletar sem escopo de organização. ---

    @Test
    void deletar_DeveLancarEntityNotFound_QuandoFechamentoDeOutraOrganization() {
        // O finder escopado devolve vazio para id de outra organização; antes o
        // existsById não filtrava e o deleteById apagava, devolvendo 204.
        when(fechamentoRepository.findVisivelById(100L, orgId)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> fechamentoService.deletar(100L));
        verify(fechamentoRepository, never()).deleteById(anyLong());
        verify(fechamentoRepository, never()).delete(any(MonthlySettlement.class));
    }

    @Test
    void deletar_AdminSemEscopo_DeveDeletar() {
        MonthlySettlement fechamento = fechamentoComTotais(new BigDecimal("200.00"));
        when(fechamentoRepository.findVisivelById(100L, orgId)).thenReturn(Optional.of(fechamento));

        fechamentoService.deletar(100L);

        verify(fechamentoRepository).delete(fechamento);
    }

    private MonthlySettlement fechamentoComTotais(BigDecimal totalBruto) {
        MonthlySettlement fechamento = new MonthlySettlement();
        fechamento.setId(100L);
        fechamento.setTotalLitros(100);
        fechamento.setPrecoLitro(new BigDecimal("2.00"));
        fechamento.setPrecoFinal(new BigDecimal("2.00"));
        fechamento.setTotalBruto(totalBruto);
        fechamento.setDeductions(BigDecimal.ZERO);
        fechamento.setTotalLiquido(totalBruto);
        fechamento.setDataInicio(LocalDate.of(2023, 10, 1));
        fechamento.setDataFim(LocalDate.of(2023, 10, 31));

        Supplier produtor = new Supplier();
        produtor.setId(1L);
        produtor.setUsaPagamentoPorQualidade(false);
        fechamento.setSupplier(produtor);
        fechamento.setListaDeductions(new java.util.ArrayList<>());
        return fechamento;
    }
}
