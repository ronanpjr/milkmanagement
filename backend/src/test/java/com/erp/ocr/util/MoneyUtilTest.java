package com.erp.ocr.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cobertura de {@link MoneyUtil} (B10) — o arredondamento monetário é
 * centralizado aqui justamente para não haver divergência entre o cálculo e o
 * que é gravado/mostrado, então os testes verificam os valores finais exatos.
 */
class MoneyUtilTest {

    @Test
    void round_DeveArredondarParaDuasCasasEmHalfUp() {
        BigDecimal resultado = MoneyUtil.round(new BigDecimal("10.005"));

        assertEquals(new BigDecimal("10.01"), resultado);
        assertEquals(2, resultado.scale());
    }

    @Test
    void round_DeveArredondarParaBaixoQuandoQuartoDigitoMenorQueCinco() {
        BigDecimal resultado = MoneyUtil.round(new BigDecimal("10.004"));

        assertEquals(new BigDecimal("10.00"), resultado);
    }

    @Test
    void round_DeveArredondarParaCimaEmValorNegativo() {
        // HALF_UP arredonda "para longe do zero": -1.005 vira -1.01, e não -1.00.
        BigDecimal resultado = MoneyUtil.round(new BigDecimal("-1.005"));

        assertEquals(new BigDecimal("-1.01"), resultado);
    }

    @Test
    void round_DevePreservarValorQueJaPossuiDuasCasas() {
        BigDecimal resultado = MoneyUtil.round(new BigDecimal("1234.56"));

        assertEquals(new BigDecimal("1234.56"), resultado);
        assertEquals(2, resultado.scale());
    }

    @Test
    void round_DeveReduzirEscalaDeValorComMaisCasas() {
        // Coleta de 1/3 de litro accumula muitas casas; o total deve sair com 2.
        BigDecimal resultado = MoneyUtil.round(new BigDecimal("33.333333333333336"));

        assertEquals(new BigDecimal("33.33"), resultado);
        assertEquals(2, resultado.scale());
    }

    @Test
    void round_DeveArredondarValoresGrandesSemPerderCentavos() {
        BigDecimal resultado = MoneyUtil.round(new BigDecimal("99999999.995"));

        assertEquals(new BigDecimal("100000000.00"), resultado);
    }

    @Test
    void round_DeveRetornarNullParaEntradaNula() {
        assertNull(MoneyUtil.round(null));
    }

    @Test
    void roundPricePerLiter_DeveArredondarParaQuatroCasasEmHalfUp() {
        BigDecimal resultado = MoneyUtil.roundPricePerLiter(new BigDecimal("3.14159"));

        assertEquals(new BigDecimal("3.1416"), resultado);
        assertEquals(4, resultado.scale());
    }

    @Test
    void roundPricePerLiter_DevePreservarValorQueJaPossuiQuatroCasas() {
        BigDecimal resultado = MoneyUtil.roundPricePerLiter(new BigDecimal("2.6500"));

        assertEquals(new BigDecimal("2.6500"), resultado);
        assertEquals(4, resultado.scale());
    }

    @Test
    void roundPricePerLiter_DeveArredondarParaCimaEmValorNegativo() {
        BigDecimal resultado = MoneyUtil.roundPricePerLiter(new BigDecimal("-0.00005"));

        assertEquals(new BigDecimal("-0.0001"), resultado);
    }

    @Test
    void roundPricePerLiter_DeveRetornarNullParaEntradaNula() {
        assertNull(MoneyUtil.roundPricePerLiter(null));
    }

    @Test
    void constantes_DeveExporAsEscalasDocumentadas() {
        assertEquals(2, MoneyUtil.CURRENCY_SCALE);
        assertEquals(4, MoneyUtil.PRICE_PER_LITER_SCALE);
    }
}
