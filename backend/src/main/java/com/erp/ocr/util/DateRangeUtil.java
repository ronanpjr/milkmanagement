package com.erp.ocr.util;

import java.time.LocalDate;

/**
 * Normaliza o intervalo de datas de um filtro opcional.
 * <p>
 * Existe por causa do CockroachDB. O padrão {@code (:param IS NULL OR coluna
 * >= :param)} — usado para "filtro opcional" — funciona com {@code Long}
 * ({@code organizacaoId}, {@code produtorId}), mas <strong>quebra com
 * {@code LocalDate}</strong>: o placeholder usado apenas na comparação
 * {@code IS NULL} é enviado pelo driver sem tipo declarado, e o CockroachDB 26
 * não consegue inferir o tipo dele. O banco responde
 * {@code ERROR: could not determine data type of placeholder $N} (SQLState
 * 42P18) já no {@code PREPARE}, e a requisição morre com 500.
 * <p>
 * Verificado direto no CRDB v26.2.7:
 * <ul>
 * <li>{@code WHERE ($1 IS NULL OR data >= $1)} → <strong>erro 42P18</strong>;</li>
 * <li>{@code WHERE (CAST($1 AS DATE) IS NULL OR data >= $1)} → ok;</li>
 * <li>{@code WHERE data >= $1 AND data <= $2} → ok (o tipo vem da coluna).</li>
 * </ul>
 * A alternativa adotada é a terceira: o filtro deixa de ser opcional
 * <em>dentro da query</em> e passa a ser sempre um intervalo, com os limites
 * sem início/sem fim materializados aqui. Com isso todo placeholder fica numa
 * posição tipada pela coluna — e o filtro por data passa a poder usar índice,
 * o que o {@code OR ... IS NULL} impedia.
 * <p>
 * Os limites sentinela são valores reais, não nulos: dentro do intervalo
 * aceito pelo {@code DATE} do CockroachDB (4713 a.C. a 294276 d.C.) e do
 * {@code LocalDate} do Java.
 */
public final class DateRangeUtil {

    /** Limite inicial quando o filtro não tem data inicial. */
    public static final LocalDate SEM_LIMITE_INICIAL = LocalDate.of(1, 1, 1);

    /** Limite final quando o filtro não tem data final. */
    public static final LocalDate SEM_LIMITE_FINAL = LocalDate.of(9999, 12, 31);

    private DateRangeUtil() {
    }

    /**
     * Data inicial do filtro, com o limite sentinela no lugar de {@code null}.
     *
     * @param inicio data inicial pedida, ou {@code null} se não há filtro
     * @return a data inicial a comparar, nunca {@code null}
     */
    public static LocalDate inicio(LocalDate inicio) {
        return inicio != null ? inicio : SEM_LIMITE_INICIAL;
    }

    /**
     * Data final do filtro, com o limite sentinela no lugar de {@code null}.
     *
     * @param fim data final pedida, ou {@code null} se não há filtro
     * @return a data final a comparar, nunca {@code null}
     */
    public static LocalDate fim(LocalDate fim) {
        return fim != null ? fim : SEM_LIMITE_FINAL;
    }
}
