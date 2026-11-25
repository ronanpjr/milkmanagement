package com.erp.ocr.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Normalização do intervalo de datas do filtro opcional.
 * <p>
 * Existe por causa do CockroachDB: o padrão {@code (:param IS NULL OR coluna
 * >= :param)} quebra com {@code LocalDate} (SQLState 42P18, "could not
 * determine data type of placeholder"), porque o placeholder usado só na
 * comparação {@code IS NULL} vai sem tipo declarado. Ver a issue #47 e o
 * javadoc de {@link DateRangeUtil}.
 */
@DisplayName("DateRangeUtil: normaliza o filtro de período para o CockroachDB")
class DateRangeUtilTest {

    @Test
    @DisplayName("sem filtro, o intervalo vira os limites sentinela")
    void semFiltro_UsaLimitesSentinela() {
        assertThat(DateRangeUtil.inicio(null)).isEqualTo(DateRangeUtil.SEM_LIMITE_INICIAL);
        assertThat(DateRangeUtil.fim(null)).isEqualTo(DateRangeUtil.SEM_LIMITE_FINAL);
    }

    @Test
    @DisplayName("com filtro, as datas passam intactas")
    void comFiltro_PreservaAsDatas() {
        LocalDate inicio = LocalDate.of(2026, 10, 1);
        LocalDate fim = LocalDate.of(2026, 10, 31);

        assertThat(DateRangeUtil.inicio(inicio)).isEqualTo(inicio);
        assertThat(DateRangeUtil.fim(fim)).isEqualTo(fim);
    }

    @Test
    @DisplayName("filtro de apenas uma ponta normaliza só ela")
    void apenasUmaPonta_NormalizaSomenteEla() {
        LocalDate fim = LocalDate.of(2026, 10, 31);

        assertThat(DateRangeUtil.inicio(null)).isEqualTo(DateRangeUtil.SEM_LIMITE_INICIAL);
        assertThat(DateRangeUtil.fim(fim)).isEqualTo(fim);
    }

    @Test
    @DisplayName("nenhum método devolve null — a query nunca recebe data nula")
    void nuncaDevolveNull() {
        assertThat(DateRangeUtil.inicio(null)).isNotNull();
        assertThat(DateRangeUtil.fim(null)).isNotNull();
        assertThat(DateRangeUtil.inicio(LocalDate.now())).isNotNull();
        assertThat(DateRangeUtil.fim(LocalDate.now())).isNotNull();
    }

    @Test
    @DisplayName("os limites sentinela cabem no DATE do CockroachDB e do LocalDate")
    void limitesSentinela_SaoValidos() {
        // O CRDB aceita DATE de 4713 a.C. a 294276 d.C.; o LocalDate do Java
        // vai de -999999999 a 999999999. Os sentinelas ficam na interseção,
        // longe das bordas dos dois.
        assertThat(DateRangeUtil.SEM_LIMITE_INICIAL).isAfter(LocalDate.of(-4712, 1, 1));
        assertThat(DateRangeUtil.SEM_LIMITE_FINAL).isBefore(LocalDate.of(294276, 1, 1));
    }
}
