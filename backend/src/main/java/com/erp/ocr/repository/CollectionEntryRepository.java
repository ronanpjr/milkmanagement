package com.erp.ocr.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.erp.ocr.model.CollectionEntry;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CollectionEntryRepository extends JpaRepository<CollectionEntry, Long> {

    /**
     * Todas as consultas abaixo recebem `organizacaoId` e filtram por ele.
     * Nulo = sem filtro (ADMIN). O predicado mora na query, e não num if/else
     * do service, para que "com escopo" e "sem escopo" sejam literalmente a
     * mesma consulta — impossível um dos caminhos esquecer de filtrar.
     *
     * A coleta não tem coluna de organização: ela herda do produtor, que é a
     * raiz da propriedade (ver V4__tenancy.sql).
     */
    @Query("""
            SELECT c FROM CollectionEntry c
            WHERE c.supplier.id = :produtorId
              AND c.data = :data
              AND (:organizacaoId IS NULL OR c.supplier.organization.id = :organizacaoId)
            """)
    Optional<CollectionEntry> findVisivelBySupplierIdEData(@Param("produtorId") Long produtorId,
            @Param("entry_date") LocalDate data,
            @Param("organizacaoId") Long organizacaoId);

    @Query("""
            SELECT c FROM CollectionEntry c
            WHERE c.supplier.id = :produtorId
              AND YEAR(c.data) = :ano AND MONTH(c.data) = :mes
              AND (:organizacaoId IS NULL OR c.supplier.organization.id = :organizacaoId)
            """)
    List<CollectionEntry> findVisiveisPorSupplierEMes(@Param("produtorId") Long produtorId, @Param("ano") int ano,
            @Param("month") int mes, @Param("organizacaoId") Long organizacaoId);

    /**
     * Coletas de um produtor no período, para somar os litros do fechamento.
     * O produtor já foi resolvido por um finder escopado antes, mas o filtro
     * se repete para que a consulta seja autossuficiente.
     */
    @Query("""
            SELECT c FROM CollectionEntry c
            WHERE c.supplier.id = :produtorId
              AND c.data BETWEEN :dataInicio AND :dataFim
              AND (:organizacaoId IS NULL OR c.supplier.organization.id = :organizacaoId)
            """)
    List<CollectionEntry> findVisiveisPorSupplierEPeriodo(@Param("produtorId") Long produtorId,
            @Param("dataInicio") LocalDate dataInicio, @Param("dataFim") LocalDate dataFim,
            @Param("organizacaoId") Long organizacaoId);

    /**
     * Busca em massa para o {@code salvarColetasEmLote}, em vez de uma
     * consulta por item. Escopado pelo mesmo motivo das demais.
     * <p>
     * O filtro é por <strong>intervalo</strong> de datas, não por
     * {@code data IN :datas}: o produto cartesiano {@code produtorIds × datas}
     * devolvia até |produtores| × |datas| linhas para um lote de 1000. O
     * chamador filtra os pares exatos {@code (produtor, data)} em memória.
     * <p>
     * O {@code produtor} vem no {@link EntityGraph} porque o chamador lê
     * {@code getSupplier().getId()} ao montar a chave — sem ele seria um lazy
     * load por linha.
     */
    @EntityGraph(attributePaths = { "supplier" })
    @Query("""
            SELECT c FROM CollectionEntry c
            WHERE c.supplier.id IN :produtorIds
              AND c.data BETWEEN :dataInicio AND :dataFim
              AND (:organizacaoId IS NULL OR c.supplier.organization.id = :organizacaoId)
            """)
    List<CollectionEntry> findVisiveisPorSupplierIdsEPeriodo(@Param("produtorIds") List<Long> produtorIds,
            @Param("dataInicio") LocalDate dataInicio, @Param("dataFim") LocalDate dataFim,
            @Param("organizacaoId") Long organizacaoId);

    /**
     * Registro único escopado, com o produtor (e sua organização) já
     * carregados. A checagem de tenancy fica no banco, não num filtro em
     * memória que dispara lazy loads e deixa a regra espalhada no service.
     * Organização errada devolve vazio → 404, não 403.
     */
    @EntityGraph(attributePaths = { "supplier", "supplier.organization" })
    @Query("""
            SELECT c FROM CollectionEntry c
            WHERE c.id = :id
              AND (:organizacaoId IS NULL OR c.supplier.organization.id = :organizacaoId)
            """)
    Optional<CollectionEntry> findVisivelById(@Param("id") Long id,
            @Param("organizacaoId") Long organizacaoId);

    @Query("""
            SELECT c FROM CollectionEntry c
            WHERE (:organizacaoId IS NULL OR c.supplier.organization.id = :organizacaoId)
            """)
    Page<CollectionEntry> findVisiveis(@Param("organizacaoId") Long organizacaoId, Pageable pageable);

    /**
     * Listagem com filtro de período.
     * <p>
     * O período é <strong>sempre</strong> um intervalo fechado, nunca "sem
     * filtro": as datas chegam já normalizadas por
     * {@code DateRangeUtil} (limites sentinela no lugar de {@code null}).
     * <p>
     * O motivo é o CockroachDB: o padrão {@code (:param IS NULL OR coluna >=
     * :param)} — que funciona para {@code Long} — quebra com {@code LocalDate},
     * porque o placeholder usado só na comparação {@code IS NULL} é enviado
     * sem tipo declarado e o CRDB 26 responde {@code could not determine data
     * type of placeholder} (42P18) já no {@code PREPARE}. Ver
     * {@code DateRangeUtil} e a issue #47.
     * <p>
     * Vantagem colateral: sem o {@code OR ... IS NULL}, o filtro por
     * {@code data} passa a poder usar índice.
     */
    @Query("""
            SELECT c FROM CollectionEntry c
            WHERE (:organizacaoId IS NULL OR c.supplier.organization.id = :organizacaoId)
              AND (:produtorId IS NULL OR c.supplier.id = :produtorId)
              AND c.data >= :inicio
              AND c.data <= :fim
            """)
    Page<CollectionEntry> findVisiveisComFiltros(@Param("organizacaoId") Long organizacaoId,
            @Param("produtorId") Long produtorId, @Param("inicio") LocalDate inicio,
            @Param("fim") LocalDate fim, Pageable pageable);
}
