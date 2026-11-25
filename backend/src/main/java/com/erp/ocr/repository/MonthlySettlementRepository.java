package com.erp.ocr.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.lang.NonNull;
import com.erp.ocr.model.MonthlySettlement;

public interface MonthlySettlementRepository extends JpaRepository<MonthlySettlement, Long> {

    /**
     * Todos os finders recebem `organizacaoId` e filtram por ele; nulo = sem
     * filtro (ADMIN). O fechamento herda a organização do produtor.
     *
     * Os finders antigos (findAll, findByDataInicioLessThanEqual..., findByNome)
     * foram removidos de propósito: devolviam a carteira inteira, sem escopo,
     * e eram exatamente o que os relatórios usavam.
     */

    /**
     * Um fechamento cujo período CONTÉM a data informada.
     * <p>
     * Substitui a checagem por mês/ano para travar a edição de coletas.
     * Períodos podem atravessar meses — a prática é pagar o leite produzido
     * entre o dia 20 e o dia 20 do mês seguinte — e a checagem por mês/ano
     * usava apenas o mês do {@code dataInicio} gravado: num fechamento
     * 20/06 a 20/07 (mes=6), as coletas de julho, já pagas, ficavam editáveis.
     */
    @Query("""
            SELECT COUNT(f) > 0 FROM MonthlySettlement f
            WHERE f.supplier.id = :produtorId
              AND f.dataInicio <= :data AND f.dataFim >= :data
            """)
    boolean existsBySupplierAndPeriodoContemData(@Param("produtorId") Long produtorId,
            @Param("entry_date") java.time.LocalDate data);

    /**
     * Fechamentos de vários produtores que intersectam o intervalo informado.
     * <p>
     * Substitui um {@link #existsBySupplierAndPeriodoContemData} por item do
     * lote: o chamador carrega os períodos uma vez e testa os pares
     * {@code (produtor, data)} em memória. O teste de sobreposição é o mesmo
     * dos demais finders — {@code dataInicio <= fim AND dataFim >= inicio} — e
     * o filtro exato de "contém a data" fica com o chamador.
     */
    @EntityGraph(attributePaths = { "supplier" })
    @Query("""
            SELECT f FROM MonthlySettlement f
            WHERE f.supplier.id IN :produtorIds
              AND f.dataInicio <= :dataFim AND f.dataFim >= :dataInicio
              AND (:organizacaoId IS NULL OR f.supplier.organization.id = :organizacaoId)
            """)
    java.util.List<MonthlySettlement> findVisiveisPorSupplierIdsEPeriodo(
            @Param("produtorIds") java.util.List<Long> produtorIds,
            @Param("dataInicio") java.time.LocalDate dataInicio,
            @Param("dataFim") java.time.LocalDate dataFim,
            @Param("organizacaoId") Long organizacaoId);

    /** Impede dois fechamentos que se sobreponham no mesmo produtor. */
    @Query("""
            SELECT COUNT(f) > 0 FROM MonthlySettlement f
            WHERE f.supplier.id = :produtorId
              AND f.dataInicio <= :dataFim AND f.dataFim >= :dataInicio
              AND (:organizacaoId IS NULL OR f.supplier.organization.id = :organizacaoId)
            """)
    boolean existsBySupplierAndPeriodoOverlap(@Param("produtorId") Long produtorId,
            @Param("dataInicio") java.time.LocalDate dataInicio,
            @Param("dataFim") java.time.LocalDate dataFim,
            @Param("organizacaoId") Long organizacaoId);

    @Query("""
            SELECT f FROM MonthlySettlement f
            WHERE f.id = :id
              AND (:organizacaoId IS NULL OR f.supplier.organization.id = :organizacaoId)
            """)
    @EntityGraph(attributePaths = { "supplier", "supplier.organization" })
    java.util.Optional<MonthlySettlement> findVisivelById(@Param("id") Long id,
            @Param("organizacaoId") Long organizacaoId);

    // B13 (N+1): o join de `produtor` vem no EntityGraph para a listagem não
    // issuing uma consulta por linha. O `listaDeductions` continua via
    // @BatchSize na entidade — collection JOIN FETCH quebraria a paginação SQL.
    //
    // O período é sempre um intervalo fechado: as datas chegam normalizadas
    // por `DateRangeUtil` (limites sentinela no lugar de null). O padrão
    // `(:param IS NULL OR coluna >= :param)` — que funciona para Long —
    // quebra com LocalDate no CockroachDB 26 (SQLState 42P18, "could not
    // determine data type of placeholder"), já no PREPARE. Ver DateRangeUtil
    // e a issue #47.
    //
    // O teste de sobreposição é o mesmo de sempre: o período do fechamento
    // ([dataInicio, dataFim]) precisa intersectar o filtro ([inicio, fim]).
    @EntityGraph(attributePaths = { "supplier", "supplier.organization" })
    @Query("""
            SELECT f FROM MonthlySettlement f
            WHERE (:organizacaoId IS NULL OR f.supplier.organization.id = :organizacaoId)
              AND (:produtorId IS NULL OR f.supplier.id = :produtorId)
              AND f.dataInicio <= :fim
              AND f.dataFim >= :inicio
            """)
    Page<MonthlySettlement> findVisiveisComFiltros(@Param("organizacaoId") Long organizacaoId,
            @Param("produtorId") Long produtorId,
            @Param("inicio") java.time.LocalDate inicio,
            @Param("fim") java.time.LocalDate fim,
            Pageable pageable);
}
