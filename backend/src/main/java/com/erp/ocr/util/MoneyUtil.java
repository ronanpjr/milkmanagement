package com.erp.ocr.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Centraliza o arredondamento de valores monetários (B10) para evitar que
 * operações com {@link BigDecimal} acumulem casas decimais arbitrárias.
 */
public final class MoneyUtil {

    /** Escala usada para valores em reais (totais, descontos). */
    public static final int CURRENCY_SCALE = 2;

    /** Escala usada para preço por litro (mais precisão que valores totais). */
    public static final int PRICE_PER_LITER_SCALE = 4;

    private MoneyUtil() {
    }

    /**
     * Arredonda um valor monetário (reais) para 2 casas decimais, HALF_UP.
     */
    public static BigDecimal round(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.setScale(CURRENCY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Arredonda um preço por litro para 4 casas decimais, HALF_UP.
     */
    public static BigDecimal roundPricePerLiter(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.setScale(PRICE_PER_LITER_SCALE, RoundingMode.HALF_UP);
    }
}
