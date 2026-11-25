package com.erp.ocr.service;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import com.erp.ocr.dto.CollectionEntryDto;
import com.erp.ocr.dto.CollectionEntryResponseDto;
import com.erp.ocr.dto.CollectionBatchItemDto;
import com.erp.ocr.model.CollectionEntry;
import com.erp.ocr.model.MonthlySettlement;
import com.erp.ocr.model.Supplier;
import com.erp.ocr.repository.CollectionEntryRepository;
import com.erp.ocr.repository.SupplierRepository;
import com.erp.ocr.util.DateRangeUtil;
import jakarta.persistence.EntityNotFoundException;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class CollectionEntryService {

    private static final String MSG_PERIODO_FECHADO =
            "Não é possível alterar coletas de um período que já possui fechamento pago.";

    @Autowired
    private CollectionEntryRepository coletaRepository;

    @Autowired
    private SupplierRepository produtorRepository;

    @Autowired
    private com.erp.ocr.repository.MonthlySettlementRepository fechamentoRepository;

    /**
     * Organização do usuário autenticado. Toda leitura e escrita de coleta
     * passa por ela — a coleta herda a organização do produtor, e é o produtor
     * que é escopado.
     */
    @Autowired
    private com.erp.ocr.config.OrganizationContext organizacaoContexto;

    // Retorna DTO de Resposta
    public org.springframework.data.domain.Page<CollectionEntryResponseDto> listarTodas(
            org.springframework.data.domain.Pageable pageable) {
        return coletaRepository.findVisiveis(organizacaoContexto.organizacaoId(), pageable)
                .map(this::toResponseDto);
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<CollectionEntryResponseDto> listarComFiltros(
            Long produtorId, java.time.LocalDate inicio, java.time.LocalDate fim,
            org.springframework.data.domain.Pageable pageable) {

        // O período vira sempre um intervalo fechado (limites sentinela no
        // lugar de null): o "filtro opcional" via `(:param IS NULL OR ...)`
        // quebra no CockroachDB quando o parâmetro é LocalDate. Ver
        // DateRangeUtil e a issue #47.
        return coletaRepository
                .findVisiveisComFiltros(organizacaoContexto.organizacaoId(), produtorId,
                        DateRangeUtil.inicio(inicio), DateRangeUtil.fim(fim), pageable)
                .map(this::toResponseDto);
    }

    // Retorna DTO de Resposta
    public CollectionEntryResponseDto buscarPorId(Long id) {
        CollectionEntry coleta = findColetaById(id);
        return toResponseDto(coleta);
    }

    // Retorna DTO de Resposta
    @Transactional
    public CollectionEntryResponseDto criar(CollectionEntryDto dto) {
        // Achado 7: resolve o produtor ESCOPADO antes de validar o fechamento.
        // Se validássemos o período primeiro, um id de outra organização num
        // período fechado devolveria 400 (oráculo: revela que existe fechamento
        // para aquele produtor) em vez do 404 que o escopo determina.
        Supplier produtor = findSupplierById(dto.getSupplierId());
        validarFechamento(dto.getSupplierId(), dto.getData());

        CollectionEntry novaColeta = new CollectionEntry();
        novaColeta.setData(dto.getData());
        novaColeta.setQuantidadeLitros(dto.getQuantidadeLitros());
        novaColeta.setSupplier(produtor);

        CollectionEntry coletaSalva = coletaRepository.save(novaColeta);
        return toResponseDto(coletaSalva);
    }

    // Retorna DTO de Resposta
    @Transactional
    public CollectionEntryResponseDto atualizar(Long id, CollectionEntryDto dto) {
        // Achado 7: coleta e produtor são resolvidos com escopo ANTES de
        // qualquer validarFechamento — id de outra organização devolve 404, e
        // não o 400 que o lock de período devolveria (oráculo).
        CollectionEntry coletaExistente = findColetaById(id);
        Supplier produtor = findSupplierById(dto.getSupplierId());

        validarFechamento(dto.getSupplierId(), dto.getData());
        // O par (produtor, data) ANTIGO só precisa ser revalidado quando ele
        // muda: editar apenas a quantidade verificava o mesmo par duas vezes
        // (um COUNT extra por edição).
        boolean parMudou = !coletaExistente.getSupplier().getId().equals(dto.getSupplierId())
                || !coletaExistente.getData().equals(dto.getData());
        if (parMudou) {
            validarFechamento(coletaExistente.getSupplier().getId(), coletaExistente.getData());
        }

        coletaExistente.setData(dto.getData());
        coletaExistente.setQuantidadeLitros(dto.getQuantidadeLitros());
        coletaExistente.setSupplier(produtor);

        CollectionEntry coletaAtualizada = coletaRepository.save(coletaExistente);
        return toResponseDto(coletaAtualizada);
    }

    @Transactional
    public void deletar(Long id) {
        CollectionEntry coleta = findColetaById(id);
        validarFechamento(coleta.getSupplier().getId(), coleta.getData());
        coletaRepository.delete(coleta);
    }

    public List<CollectionEntryResponseDto> buscarPorSupplierEMes(Long produtorId, int ano, int mes) {
        return coletaRepository
                .findVisiveisPorSupplierEMes(produtorId, ano, mes, organizacaoContexto.organizacaoId()).stream()
                .map(this::toResponseDto)
                .collect(Collectors.toList());
    }

    // B20/B18: the batch loop used to call findBySupplierIdAndData once per item
    // (N+1). Retrying the whole batch on a transient DataAccessException is now
    // cheap (a handful of bulk queries, not N), and is actually a good fit for
    // CockroachDB - which is SERIALIZABLE-only and expects clients to retry on
    // serialization/contention conflicts - so @Retryable is kept.
    @Transactional
    @Retryable(retryFor = DataAccessException.class, maxAttempts = 5, backoff = @Backoff(delay = 100, multiplier = 2))
    public List<CollectionEntryResponseDto> salvarColetasEmLote(List<CollectionBatchItemDto> coletasDto) {
        // Achado 8: duplicata de (produtorId, data) DENTRO do payload é barrada
        // antes de qualquer escrita, com 400 e a lista de duplicatas. Sem isso,
        // as duas linhas viram coletas novas, o saveAll estourava a constraint
        // de unicidade no meio da transação e o cliente recebia um 409 opaco.
        validarDuplicatasNoLote(coletasDto);

        if (coletasDto.isEmpty()) {
            return List.of();
        }

        Long orgId = organizacaoContexto.organizacaoId();

        List<Long> produtorIds = coletasDto.stream()
                .map(CollectionBatchItemDto::produtorId)
                .distinct()
                .collect(Collectors.toList());
        LocalDate dataMin = coletasDto.stream().map(CollectionBatchItemDto::data).min(LocalDate::compareTo).orElseThrow();
        LocalDate dataMax = coletasDto.stream().map(CollectionBatchItemDto::data).max(LocalDate::compareTo).orElseThrow();

        // Achado 9: resolve os produtores em UMA consulta escopada, em vez de
        // um findVisivelById por id (até 1000 consultas). Ids de outra
        // organização simplesmente não voltam — vira 404.
        Map<Long, Supplier> produtoresPorId = produtorRepository.findVisiveisByIdIn(produtorIds, orgId).stream()
                .collect(Collectors.toMap(Supplier::getId, p -> p));

        // Achado 7: o produtor precisa existir E estar no escopo ANTES de
        // validar o fechamento. Um id de outra organização num período fechado
        // recebe 404, e não o 400 (oráculo) que a trava de período daria.
        coletasDto.forEach(dto -> {
            if (!produtoresPorId.containsKey(dto.produtorId())) {
                throw new EntityNotFoundException("Supplier não encontrado com o ID: " + dto.produtorId());
            }
        });

        // Uma consulta de fechamentos para o intervalo do lote, testada em
        // memória, em vez de um COUNT por item.
        validarFechamentosEmLote(coletasDto, produtorIds, orgId, dataMin, dataMax);

        // Uma consulta por intervalo de datas (não produto cartesiano ids ×
        // datas); os pares exatos são filtrados ao montar a chave.
        Map<String, CollectionEntry> existentesPorChave = coletaRepository
                .findVisiveisPorSupplierIdsEPeriodo(produtorIds, dataMin, dataMax, orgId).stream()
                .collect(Collectors.toMap(c -> chave(c.getSupplier().getId(), c.getData()), c -> c));

        List<CollectionEntry> coletasParaSalvar = coletasDto.stream().map(dto -> {
            Supplier produtor = produtoresPorId.get(dto.produtorId());

            CollectionEntry existente = existentesPorChave.get(chave(dto.produtorId(), dto.data()));
            if (existente != null) {
                existente.setQuantidadeLitros(dto.quantidadeLitros());
                return existente;
            }

            CollectionEntry novaColeta = new CollectionEntry();
            novaColeta.setSupplier(produtor);
            novaColeta.setData(dto.data());
            novaColeta.setQuantidadeLitros(dto.quantidadeLitros());
            return novaColeta;
        }).collect(Collectors.toList());

        List<CollectionEntry> coletasSalvas = coletaRepository.saveAll(coletasParaSalvar);

        return coletasSalvas.stream()
                .map(this::toResponseDto)
                .collect(Collectors.toList());
    }

    private String chave(Long produtorId, LocalDate data) {
        return produtorId + "|" + data;
    }

    /**
     * Carrega UMA vez os fechamentos que intersectam o intervalo do lote e
     * testa cada par {@code (produtor, data)} em memória. Substitui o COUNT
     * por item, que podia repetir 1000 consultas (e 5× isso sob o
     * {@code @Retryable}).
     */
    private void validarFechamentosEmLote(List<CollectionBatchItemDto> coletasDto, List<Long> produtorIds,
            Long orgId, LocalDate dataMin, LocalDate dataMax) {
        List<MonthlySettlement> fechamentos = fechamentoRepository
                .findVisiveisPorSupplierIdsEPeriodo(produtorIds, dataMin, dataMax, orgId);
        if (fechamentos.isEmpty()) {
            return;
        }

        Map<Long, List<MonthlySettlement>> porSupplier = fechamentos.stream()
                .collect(Collectors.groupingBy(f -> f.getSupplier().getId()));

        for (CollectionBatchItemDto dto : coletasDto) {
            boolean fechado = porSupplier.getOrDefault(dto.produtorId(), List.of()).stream()
                    .anyMatch(f -> !dto.data().isBefore(f.getDataInicio())
                            && !dto.data().isAfter(f.getDataFim()));
            if (fechado) {
                throw new IllegalStateException(MSG_PERIODO_FECHADO);
            }
        }
    }

    /**
     * Barra, com 400, um lote que repita {@code (produtorId, data)}. A chave é
     * a mesma do upsert, então a duplicata não é "dois lançamentos válidos": o
     * segundo sobrescreveria o primeiro (ou estouraria a constraint). Erro de
     * entrada, portanto — 400 listando cada par repetido, nunca 409.
     */
    private void validarDuplicatasNoLote(List<CollectionBatchItemDto> coletasDto) {
        Set<String> vistas = new HashSet<>();
        Set<String> duplicatas = new LinkedHashSet<>();
        for (CollectionBatchItemDto dto : coletasDto) {
            if (!vistas.add(chave(dto.produtorId(), dto.data()))) {
                duplicatas.add("produtor " + dto.produtorId() + " em " + dto.data());
            }
        }
        if (!duplicatas.isEmpty()) {
            throw new IllegalArgumentException(
                    "O lote contém mais de uma coleta para o mesmo produtor e data: "
                            + String.join("; ", duplicatas));
        }
    }

    private CollectionEntry findColetaById(Long id) {
        // Finder escopado: a tenancy mora na query (e o produtor/organização
        // vêm no EntityGraph), não num filtro em memória que dispara lazy
        // loads. Id de outra organização devolve vazio e cai no 404.
        return coletaRepository.findVisivelById(id, organizacaoContexto.organizacaoId())
                .orElseThrow(() -> new EntityNotFoundException("Coleta não encontrada com o ID: " + id));
    }

    private Supplier findSupplierById(Long id) {
        return produtorRepository.findVisivelById(id, organizacaoContexto.organizacaoId())
                .orElseThrow(() -> new EntityNotFoundException("Supplier não encontrado com o ID: " + id));
    }

    private CollectionEntryResponseDto toResponseDto(CollectionEntry coleta) {
        return new CollectionEntryResponseDto(
                coleta.getId(),
                coleta.getData(),
                coleta.getQuantidadeLitros(),
                coleta.getSupplier().getId());
    }

    /**
     * Trava a edição de coletas cujo período já foi pago.
     * <p>
     * A trava é por PERÍODO, não por mês/ano: períodos podem atravessar meses
     * (padrão de pagamento do dia 20 ao dia 20 do mês seguinte) e a checagem
     * antiga comparava só o mês gravado a partir do {@code dataInicio}.
     */
    private void validarFechamento(Long produtorId, LocalDate data) {
        if (fechamentoRepository.existsBySupplierAndPeriodoContemData(produtorId, data)) {
            throw new IllegalStateException(MSG_PERIODO_FECHADO);
        }
    }
}
