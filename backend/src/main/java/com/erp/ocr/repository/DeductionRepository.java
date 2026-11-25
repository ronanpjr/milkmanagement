package com.erp.ocr.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.erp.ocr.model.Deduction;

/**
 * Repositório de {@link Deduction}.
 * <p>
 * <strong>Por que não há finders aqui.</strong> Um desconto pertence a um
 * fechamento, e o fechamento já é escopado por organização: só se chega a um
 * desconto carregando o fechamento por um finder escopado
 * ({@code MonthlySettlementRepository.findVisivelById}), que já devolve 404 para
 * registro de outra organização. A lista de descontos vem junto, pela
 * associação {@code fechamento.getListaDeductions()} — que é também como o N+1
 * foi resolvido, via {@code @BatchSize} na entidade.
 * <p>
 * Existia aqui um {@code findByFechamentoId(fechamentoId)} que filtrava
 * <strong>apenas</strong> pelo fechamento, sem organização. Ele não tinha
 * chamador — nem em produção, nem em teste — e por isso nunca quebrou nada.
 * Foi removido justamente por ser uma armadilha: "os descontos deste
 * fechamento" é a pergunta natural, o repository é a resposta óbvia, e esse
 * finder permitiria que um {@code USER} da organização A lesse os descontos da
 * organização B.
 * <p>
 * Se surgir a necessidade de listar descontos de forma independente, o finder
 * novo tem que receber o {@code organizacaoId}, como todos os outros do
 * projeto:
 *
 * <pre>
 * &#64;Query(\"\"\"
 *        SELECT d FROM Deduction d
 *        WHERE d.settlement.id = :fechamentoId
 *          AND (:organizacaoId IS NULL OR d.settlement.supplier.organization.id = :organizacaoId)
 *        \"\"\")
 * List&lt;Deduction&gt; findVisiveisPorFechamentoId(...);
 * </pre>
 *
 * @see MonthlySettlementRepository#findVisivelById
 */
public interface DeductionRepository extends JpaRepository<Deduction, Long> {
}
