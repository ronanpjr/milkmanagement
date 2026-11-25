package com.erp.ocr.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.erp.ocr.model.Supplier;

import java.util.List;
import java.util.Optional;

public interface SupplierRepository extends JpaRepository<Supplier, Long> {

    /**
     * Listagem escopada. `organizacaoId` nulo = sem filtro (ADMIN).
     * <p>
     * O filtro mora na query, e não num if/else do service, para que o
     * caminho "sem escopo" e o caminho "com escopo" sejam a mesma consulta —
     * impossível um deles esquecer de filtrar. Compare com os finders antigos
     * deste repositório, que retornavam a carteira inteira: eles vaziam dados
     * entre organizações e foram removidos de propósito.
     */
    @Query("""
            SELECT p FROM Supplier p
            WHERE (:organizacaoId IS NULL OR p.organization.id = :organizacaoId)
            """)
    Page<Supplier> findVisiveis(@Param("organizacaoId") Long organizacaoId, Pageable pageable);

    /** Registro único escopado. Organização errada devolve vazio → 404, não 403. */
    @Query("""
            SELECT p FROM Supplier p
            WHERE p.id = :id AND (:organizacaoId IS NULL OR p.organization.id = :organizacaoId)
            """)
    Optional<Supplier> findVisivelById(@Param("id") Long id, @Param("organizacaoId") Long organizacaoId);

    /**
     * Resolve em UMA consulta todos os produtores de um lote, em vez de um
     * {@link #findVisivelById} por id. Escopado pela mesma regra: ids de outra
     * organização simplesmente não voltam na lista.
     */
    @Query("""
            SELECT p FROM Supplier p
            WHERE p.id IN :ids AND (:organizacaoId IS NULL OR p.organization.id = :organizacaoId)
            """)
    List<Supplier> findVisiveisByIdIn(@Param("ids") List<Long> ids,
            @Param("organizacaoId") Long organizacaoId);

    /**
     * Match do OCR, escopado. Sem o filtro, a importação de um usuário casaria
     * com produtor de outra organização pelo nome — e o nome vem de leitura
     * automática de imagem, ou seja, pode ter erro de digitação.
     */
    @Query("""
            SELECT p FROM Supplier p
            WHERE p.nome LIKE %:nome% AND (:organizacaoId IS NULL OR p.organization.id = :organizacaoId)
            ORDER BY LENGTH(p.nome)
            """)
    List<Supplier> findVisiveisPorNome(@Param("nome") String nome,
            @Param("organizacaoId") Long organizacaoId);

    /**
     * Supplieres com coleta no período e sem fechamento que o cubra.
     * Escopado: a lista de "quem falta fechar" jamais pode incluir produtor
     * de outra organização.
     */
    @Query("""
            SELECT DISTINCT p FROM Supplier p
            JOIN p.coletas c
            WHERE c.data BETWEEN :inicio AND :fim
              AND (:organizacaoId IS NULL OR p.organization.id = :organizacaoId)
              AND NOT EXISTS (
                SELECT f FROM MonthlySettlement f
                WHERE f.supplier = p
                  AND f.dataInicio <= :fim
                  AND f.dataFim >= :inicio
              )
            """)
    List<Supplier> findVisiveisSemFechamento(@Param("inicio") java.time.LocalDate inicio,
            @Param("fim") java.time.LocalDate fim,
            @Param("organizacaoId") Long organizacaoId);
}
