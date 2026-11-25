package com.erp.ocr.service;

import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import com.erp.ocr.dto.CollectionEntryDto;
import com.erp.ocr.dto.CollectionEntryResponseDto;
import com.erp.ocr.dto.CollectionBatchItemDto;
import com.erp.ocr.model.CollectionEntry;
import com.erp.ocr.model.MonthlySettlement;
import com.erp.ocr.model.Supplier;
import com.erp.ocr.repository.CollectionEntryRepository;
import com.erp.ocr.repository.MonthlySettlementRepository;
import com.erp.ocr.repository.SupplierRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CollectionEntryServiceTest {

    @InjectMocks
    private CollectionEntryService coletaService;

    @Mock
    private CollectionEntryRepository coletaRepository;

    @Mock
    private SupplierRepository produtorRepository;

    // Contexto de organização em modo ADMIN (organizacaoId() = null => sem
    // filtro). Isolamento entre organizações é testado em
    // OrganizationIsolationTest, com o contexto Spring inteiro.
    @Mock
    private com.erp.ocr.config.OrganizationContext organizacaoContexto;

    private final Long orgId = null;


    @Mock
    private MonthlySettlementRepository fechamentoRepository;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(organizacaoContexto.organizacaoId()).thenReturn(orgId);
    }

    @Test
    void criar_DeveSalvar_QuandoMesAberto() {
        Long produtorId = 1L;
        LocalDate data = LocalDate.of(2023, 10, 15);
        int quantidade = 100;
        CollectionEntryDto dto = new CollectionEntryDto(data, quantidade, produtorId);

        Supplier produtor = new Supplier();
        produtor.setId(produtorId);

        when(fechamentoRepository.existsBySupplierAndPeriodoContemData(produtorId, data)).thenReturn(false);
        when(produtorRepository.findVisivelById(produtorId, orgId)).thenReturn(Optional.of(produtor));
        when(coletaRepository.save(any(CollectionEntry.class))).thenAnswer(invocation -> {
            CollectionEntry c = invocation.getArgument(0);
            c.setId(10L);
            return c;
        });

        CollectionEntryResponseDto response = coletaService.criar(dto);

        assertNotNull(response);
        assertEquals(10L, response.id());
        assertEquals(quantidade, response.quantidadeLitros());
        verify(coletaRepository).save(any(CollectionEntry.class));
    }

    @Test
    void criar_DeveSalvar_QuandoQuantidadeZero() {
        Long produtorId = 1L;
        LocalDate data = LocalDate.of(2023, 10, 15);
        int quantidade = 0; // Test 0 value
        CollectionEntryDto dto = new CollectionEntryDto(data, quantidade, produtorId);

        Supplier produtor = new Supplier();
        produtor.setId(produtorId);

        when(fechamentoRepository.existsBySupplierAndPeriodoContemData(produtorId, data)).thenReturn(false);
        when(produtorRepository.findVisivelById(produtorId, orgId)).thenReturn(Optional.of(produtor));
        when(coletaRepository.save(any(CollectionEntry.class))).thenAnswer(invocation -> {
            CollectionEntry c = invocation.getArgument(0);
            c.setId(11L);
            return c;
        });

        CollectionEntryResponseDto response = coletaService.criar(dto);

        assertNotNull(response);
        assertEquals(0, response.quantidadeLitros());
        verify(coletaRepository).save(any(CollectionEntry.class));
    }

    @Test
    void criar_DeveLancarExcecao_QuandoMesFechado() {
        Long produtorId = 1L;
        LocalDate data = LocalDate.of(2023, 11, 10);
        CollectionEntryDto dto = new CollectionEntryDto(data, 50, produtorId);

        Supplier produtor = new Supplier();
        produtor.setId(produtorId);

        // O produtor é resolvido (escopado) antes da checagem de período: um
        // produtor do próprio escopo com período fechado continua dando 400.
        when(produtorRepository.findVisivelById(produtorId, orgId)).thenReturn(Optional.of(produtor));
        when(fechamentoRepository.existsBySupplierAndPeriodoContemData(produtorId, data)).thenReturn(true);

        assertThrows(IllegalStateException.class, () -> coletaService.criar(dto));
        verify(coletaRepository, never()).save(any());
    }

    @Test
    void criar_DeveLancar404_Nao400_QuandoSupplierDeOutraOrganizationEmPeriodoFechado() {
        Long produtorId = 999L;
        LocalDate data = LocalDate.of(2023, 11, 10);
        CollectionEntryDto dto = new CollectionEntryDto(data, 50, produtorId);

        // Cenário de outra organização: finder escopado devolve vazio. Mesmo
        // que houvesse período fechado para esse id, a resposta tem de ser 404
        // (não revela existência do produtor) e nunca o 400 do lock.
        when(produtorRepository.findVisivelById(produtorId, orgId)).thenReturn(Optional.empty());
        when(fechamentoRepository.existsBySupplierAndPeriodoContemData(produtorId, data)).thenReturn(true);

        assertThrows(EntityNotFoundException.class, () -> coletaService.criar(dto));

        verify(fechamentoRepository, never()).existsBySupplierAndPeriodoContemData(produtorId, data);
        verify(coletaRepository, never()).save(any());
    }

    @Test
    void atualizar_DeveAtualizar_QuandoMesAberto() {
        Long coletaId = 5L;
        Long produtorId = 1L;
        LocalDate dataNova = LocalDate.of(2023, 12, 5);
        int quantidadeNova = 120;
        CollectionEntryDto dto = new CollectionEntryDto(dataNova, quantidadeNova, produtorId);

        Supplier produtor = new Supplier();
        produtor.setId(produtorId);

        CollectionEntry coletaExistente = new CollectionEntry(coletaId, LocalDate.of(2023, 12, 1), 100, produtor);

        // A trava agora é por PERÍODO fechado, não por mês/ano: tanto a data
        // nova quanto a data antiga da coleta são checadas individualmente.
        when(fechamentoRepository.existsBySupplierAndPeriodoContemData(produtorId, dataNova)).thenReturn(false);
        when(fechamentoRepository.existsBySupplierAndPeriodoContemData(produtorId, coletaExistente.getData()))
                .thenReturn(false);

        when(coletaRepository.findVisivelById(coletaId, orgId)).thenReturn(Optional.of(coletaExistente));
        when(produtorRepository.findVisivelById(produtorId, orgId)).thenReturn(Optional.of(produtor));
        when(coletaRepository.save(any(CollectionEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CollectionEntryResponseDto response = coletaService.atualizar(coletaId, dto);

        assertEquals(quantidadeNova, response.quantidadeLitros());
        assertEquals(dataNova, response.data());
    }

    @Test
    void atualizar_DeveLancarExcecao_QuandoMesFechadoParaNovaData() {
        Long coletaId = 5L;
        Long produtorId = 1L;
        LocalDate dataFechada = LocalDate.of(2023, 11, 10); // Closed
        CollectionEntryDto dto = new CollectionEntryDto(dataFechada, 50, produtorId);

        Supplier produtor = new Supplier();
        produtor.setId(produtorId);
        CollectionEntry coletaExistente = new CollectionEntry(coletaId, LocalDate.of(2023, 12, 1), 100, produtor);

        // Coleta e produtor escopados antes da checagem de período; o período
        // fechado na data nova continua disparando 400.
        when(coletaRepository.findVisivelById(coletaId, orgId)).thenReturn(Optional.of(coletaExistente));
        when(produtorRepository.findVisivelById(produtorId, orgId)).thenReturn(Optional.of(produtor));
        when(fechamentoRepository.existsBySupplierAndPeriodoContemData(produtorId, dataFechada)).thenReturn(true);

        assertThrows(IllegalStateException.class, () -> coletaService.atualizar(coletaId, dto));
        verify(coletaRepository, never()).save(any());
    }

    @Test
    void deletar_DeveLancarExcecao_QuandoMesFechado() {
        Long coletaId = 5L;
        Long produtorId = 1L;
        Supplier produtor = new Supplier();
        produtor.setId(produtorId);
        CollectionEntry coleta = new CollectionEntry(coletaId, LocalDate.of(2023, 11, 15), 100, produtor);

        when(coletaRepository.findVisivelById(coletaId, orgId)).thenReturn(Optional.of(coleta));
        when(fechamentoRepository.existsBySupplierAndPeriodoContemData(produtorId, coleta.getData())).thenReturn(true);

        assertThrows(IllegalStateException.class, () -> coletaService.deletar(coletaId));
        verify(coletaRepository, never()).delete(any());
    }

    @Test
    void listarComFiltros_DeveChamarRepositoryCorreto() {
        Long produtorId = 1L;
        LocalDate inicio = LocalDate.now();
        LocalDate fim = LocalDate.now();
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.Pageable.unpaged();

        when(coletaRepository.findVisiveisComFiltros(orgId, produtorId, inicio, fim, pageable))
                .thenReturn(org.springframework.data.domain.Page.empty());

        coletaService.listarComFiltros(produtorId, inicio, fim, pageable);

        verify(coletaRepository).findVisiveisComFiltros(orgId, produtorId, inicio, fim, pageable);
    }

    /**
     * Regra de negócio: há produtores pagos sobre o leite produzido entre o
     * dia 20 e o dia 20 do mês seguinte, então o período do fechamento
     * atravessa meses.
     * <p>
     * A trava de edição usava apenas o mês gravado a partir do dataInicio
     * (junho, para um fechamento 20/06–20/07), o que produzia o resultado
     * invertido: coletas de julho, JÁ PAGAS, ficavam editáveis. Estes testes
     * travam a consulta pelo período real e falham com a lógica antiga.
     */
    @Test
    void criar_ComFechamentoQueAtravessaMeses_DeveTravarColetaJaPagaDeJulho() {
        Long produtorId = 1L;
        // Fechamento 20/06 a 20/07: julho está dentro do período pago.
        LocalDate dataPaga = LocalDate.of(2026, 7, 1);
        CollectionEntryDto dto = new CollectionEntryDto(dataPaga, 50, produtorId);

        Supplier produtor = new Supplier();
        produtor.setId(produtorId);

        when(produtorRepository.findVisivelById(produtorId, orgId)).thenReturn(Optional.of(produtor));
        when(fechamentoRepository.existsBySupplierAndPeriodoContemData(produtorId, dataPaga)).thenReturn(true);

        assertThrows(IllegalStateException.class, () -> coletaService.criar(dto));
        verify(coletaRepository, never()).save(any());
    }

    @Test
    void criar_ComFechamentoQueAtravessaMeses_DeveLiberarColetaDoCicloSeguinte() {
        Long produtorId = 1L;
        // 25/07 está DEPOIS do dataFim (20/07): pertence ao ciclo seguinte,
        // ainda aberto, e precisa poder ser lançada.
        LocalDate dataAberta = LocalDate.of(2026, 7, 25);
        CollectionEntryDto dto = new CollectionEntryDto(dataAberta, 50, produtorId);

        Supplier produtor = new Supplier();
        produtor.setId(produtorId);

        when(fechamentoRepository.existsBySupplierAndPeriodoContemData(produtorId, dataAberta)).thenReturn(false);
        when(produtorRepository.findVisivelById(produtorId, orgId)).thenReturn(Optional.of(produtor));
        when(coletaRepository.save(any(CollectionEntry.class))).thenAnswer(i -> i.getArgument(0));

        CollectionEntryResponseDto response = coletaService.criar(dto);

        assertEquals(dataAberta, response.data());
        verify(coletaRepository).save(any(CollectionEntry.class));
    }

    @Test
    void criar_ComFechamentoQueAtravessaMeses_DeveLiberarColetaAnteriorAoInicio() {
        Long produtorId = 1L;
        // 19/06 é antes do dataInicio (20/06): pertence ao ciclo anterior,
        // ainda aberto.
        LocalDate dataAberta = LocalDate.of(2026, 6, 19);
        CollectionEntryDto dto = new CollectionEntryDto(dataAberta, 50, produtorId);

        Supplier produtor = new Supplier();
        produtor.setId(produtorId);

        when(fechamentoRepository.existsBySupplierAndPeriodoContemData(produtorId, dataAberta)).thenReturn(false);
        when(produtorRepository.findVisivelById(produtorId, orgId)).thenReturn(Optional.of(produtor));
        when(coletaRepository.save(any(CollectionEntry.class))).thenAnswer(i -> i.getArgument(0));

        CollectionEntryResponseDto response = coletaService.criar(dto);

        assertEquals(dataAberta, response.data());
    }

    // Achado 8: o lote não pode aceitar duas linhas para o mesmo (produtor,
    // data). Hoje isso virava 409 no meio da transação; o esperado é 400 com a
    // lista das duplicatas, antes de qualquer escrita.
    @Test
    void salvarColetasEmLote_DeveLancar400_ComListaDeDuplicatas() {
        Long produtorId = 1L;
        LocalDate data = LocalDate.of(2026, 7, 1);
        List<CollectionBatchItemDto> lote = List.of(
                new CollectionBatchItemDto(produtorId, data, 100),
                new CollectionBatchItemDto(produtorId, data, 150),
                new CollectionBatchItemDto(2L, LocalDate.of(2026, 7, 2), 200));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> coletaService.salvarColetasEmLote(lote));

        assertTrue(ex.getMessage().contains("produtor 1 em 2026-07-01"),
                "a mensagem deve listar a duplicata, foi: " + ex.getMessage());
        verify(coletaRepository, never()).saveAll(any());
        verify(produtorRepository, never()).findAllById(any());
    }

    // Achado 7 (via lote): produtor fora do escopo, mesmo em período fechado,
    // devolve 404 — e o lock de fechamento nem chega a ser consultado.
    @Test
    void salvarColetasEmLote_DeveLancar404_QuandoSupplierDeOutraOrganizationEmPeriodoFechado() {
        Long orgDoUser = 1L;
        Long produtorDeOutraOrg = 999L;
        LocalDate data = LocalDate.of(2026, 7, 1);
        when(organizacaoContexto.organizacaoId()).thenReturn(orgDoUser);

        List<CollectionBatchItemDto> lote = List.of(new CollectionBatchItemDto(produtorDeOutraOrg, data, 100));

        when(produtorRepository.findVisiveisByIdIn(List.of(produtorDeOutraOrg), orgDoUser))
                .thenReturn(List.of());
        when(fechamentoRepository.findVisiveisPorSupplierIdsEPeriodo(any(), any(), any(), eq(orgDoUser)))
                .thenReturn(List.of());

        assertThrows(EntityNotFoundException.class, () -> coletaService.salvarColetasEmLote(lote));

        verify(produtorRepository).findVisiveisByIdIn(List.of(produtorDeOutraOrg), orgDoUser);
        verify(fechamentoRepository, never()).findVisiveisPorSupplierIdsEPeriodo(any(), any(), any(), any());
        verify(coletaRepository, never()).saveAll(any());
    }

    // Achado 9 + caminho feliz: produtores resolvidos por finder escopado e o
    // lote válido continua sendo gravado.
    @Test
    void salvarColetasEmLote_DeveSalvar_QuandoLoteValido() {
        Long produtor1 = 1L;
        Long produtor2 = 2L;
        LocalDate data1 = LocalDate.of(2026, 7, 1);
        LocalDate data2 = LocalDate.of(2026, 7, 2);
        List<CollectionBatchItemDto> lote = List.of(
                new CollectionBatchItemDto(produtor1, data1, 100),
                new CollectionBatchItemDto(produtor2, data2, 200));

        Supplier p1 = new Supplier();
        p1.setId(produtor1);
        Supplier p2 = new Supplier();
        p2.setId(produtor2);

        when(produtorRepository.findVisiveisByIdIn(List.of(produtor1, produtor2), orgId))
                .thenReturn(List.of(p1, p2));
        when(fechamentoRepository.findVisiveisPorSupplierIdsEPeriodo(any(), any(), any(), eq(orgId)))
                .thenReturn(List.of());
        when(coletaRepository.findVisiveisPorSupplierIdsEPeriodo(any(), any(), any(), eq(orgId)))
                .thenReturn(List.of());
        when(coletaRepository.saveAll(any())).thenAnswer(invocation -> {
            List<CollectionEntry> recebidas = invocation.getArgument(0);
            long id = 100L;
            for (CollectionEntry coleta : recebidas) {
                coleta.setId(id++);
            }
            return recebidas;
        });

        List<CollectionEntryResponseDto> response = coletaService.salvarColetasEmLote(lote);

        assertEquals(2, response.size());
        assertEquals(100, response.get(0).quantidadeLitros());
        assertEquals(200, response.get(1).quantidadeLitros());
        verify(coletaRepository).saveAll(any());
        verify(produtorRepository, never()).findAllById(any());
    }

    // Issue #31: o lote resolve os produtores em UMA consulta escopada, e a
    // consulta de existentes é uma só (por intervalo, não uma por item).
    @Test
    void salvarColetasEmLote_DeveResolverSupplieresEmUmaConsulta() {
        Supplier p1 = new Supplier();
        p1.setId(1L);
        Supplier p2 = new Supplier();
        p2.setId(2L);

        List<CollectionBatchItemDto> lote = List.of(
                new CollectionBatchItemDto(1L, LocalDate.of(2026, 7, 1), 100),
                new CollectionBatchItemDto(2L, LocalDate.of(2026, 7, 2), 200));

        when(produtorRepository.findVisiveisByIdIn(List.of(1L, 2L), orgId)).thenReturn(List.of(p1, p2));
        when(fechamentoRepository.findVisiveisPorSupplierIdsEPeriodo(any(), any(), any(), eq(orgId)))
                .thenReturn(List.of());
        when(coletaRepository.findVisiveisPorSupplierIdsEPeriodo(any(), any(), any(), eq(orgId)))
                .thenReturn(List.of());
        when(coletaRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));

        coletaService.salvarColetasEmLote(lote);

        verify(produtorRepository, times(1)).findVisiveisByIdIn(List.of(1L, 2L), orgId);
        verify(produtorRepository, never()).findVisivelById(any(), any());
        verify(fechamentoRepository, times(1)).findVisiveisPorSupplierIdsEPeriodo(any(), any(), any(), eq(orgId));
        verify(fechamentoRepository, never()).existsBySupplierAndPeriodoContemData(any(), any());
        verify(coletaRepository, times(1)).findVisiveisPorSupplierIdsEPeriodo(any(), any(), any(), eq(orgId));
    }

    // Issue #31: período fechado é detectado em memória a partir da consulta
    // única de fechamentos, mesmo quando outro item do lote está em aberto.
    @Test
    void salvarColetasEmLote_DeveTravarItemEmPeriodoFechado() {
        Supplier p1 = new Supplier();
        p1.setId(1L);
        Supplier p2 = new Supplier();
        p2.setId(2L);

        MonthlySettlement fechado = new MonthlySettlement();
        fechado.setSupplier(p1);
        fechado.setDataInicio(LocalDate.of(2026, 6, 20));
        fechado.setDataFim(LocalDate.of(2026, 7, 20));

        List<CollectionBatchItemDto> lote = List.of(
                new CollectionBatchItemDto(1L, LocalDate.of(2026, 7, 1), 100),
                new CollectionBatchItemDto(2L, LocalDate.of(2026, 7, 25), 200));

        when(produtorRepository.findVisiveisByIdIn(List.of(1L, 2L), orgId)).thenReturn(List.of(p1, p2));
        when(fechamentoRepository.findVisiveisPorSupplierIdsEPeriodo(any(), any(), any(), eq(orgId)))
                .thenReturn(List.of(fechado));

        assertThrows(IllegalStateException.class, () -> coletaService.salvarColetasEmLote(lote));
        verify(coletaRepository, never()).saveAll(any());
    }

    // Issue #31: editar só a quantidade não revalida o mesmo par (produtor,
    // data) uma segunda vez.
    @Test
    void atualizar_QuandoSoQuantidadeMuda_DeveValidarFechamentoUmaVez() {
        Long coletaId = 5L;
        Long produtorId = 1L;
        LocalDate data = LocalDate.of(2026, 7, 1);
        CollectionEntryDto dto = new CollectionEntryDto(data, 150, produtorId);

        Supplier produtor = new Supplier();
        produtor.setId(produtorId);
        CollectionEntry coletaExistente = new CollectionEntry(coletaId, data, 100, produtor);

        when(coletaRepository.findVisivelById(coletaId, orgId)).thenReturn(Optional.of(coletaExistente));
        when(produtorRepository.findVisivelById(produtorId, orgId)).thenReturn(Optional.of(produtor));
        when(fechamentoRepository.existsBySupplierAndPeriodoContemData(produtorId, data)).thenReturn(false);
        when(coletaRepository.save(any(CollectionEntry.class))).thenAnswer(i -> i.getArgument(0));

        coletaService.atualizar(coletaId, dto);

        verify(fechamentoRepository, times(1)).existsBySupplierAndPeriodoContemData(produtorId, data);
    }

    // Issue #31: a leitura por id passa pelo finder escopado (tenancy na
    // query), não por findById + filtro em memória.
    @Test
    void buscarPorId_DeveUsarFinderEscopado() {
        Supplier produtor = new Supplier();
        produtor.setId(1L);
        CollectionEntry coleta = new CollectionEntry(5L, LocalDate.of(2026, 7, 1), 100, produtor);

        when(coletaRepository.findVisivelById(5L, orgId)).thenReturn(Optional.of(coleta));

        CollectionEntryResponseDto response = coletaService.buscarPorId(5L);

        assertEquals(5L, response.id());
        verify(coletaRepository).findVisivelById(5L, orgId);
        verify(coletaRepository, never()).findById(any());
    }
}
