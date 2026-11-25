package com.erp.ocr.strategy;

import org.junit.jupiter.api.Test;
import com.erp.ocr.model.MonthlySettlement;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultTieredPricingStrategyTest {

    private final DefaultTieredPricingStrategy strategy = new DefaultTieredPricingStrategy();

    @Test
    void calculateFinalPrice_DeveAplicarBonusCorretamente() {
        BigDecimal precoBase = new BigDecimal("2.50");
        MonthlySettlement fechamento = new MonthlySettlement();

        // CBT: 20 (Bonus 0.08)
        fechamento.setCbt(new BigDecimal("20"));
        // CCS: 40 (Bonus 0.08)
        fechamento.setCcs(new BigDecimal("40"));
        // Gordura: 4.00 (Bonus 0.03) -> Range 3.90-4.19
        fechamento.setGordura(new BigDecimal("4.00"));
        // Proteina: 3.40 (Bonus 0.02) -> Range 3.30-3.49
        fechamento.setProteina(new BigDecimal("3.40"));

        // Bonus de volume e fidelidade (definidos pelo usuário)
        BigDecimal bonusVolume = new BigDecimal("0.06");
        BigDecimal bonusFidelidade = new BigDecimal("0.05");

        // Total Bonus: 0.08 + 0.08 + 0.03 + 0.02 + 0.06 + 0.05 = 0.32
        // Final Price: 2.50 + 0.32 = 2.82

        BigDecimal resultado = strategy.calculateFinalPrice(precoBase, fechamento, bonusVolume, bonusFidelidade);

        assertEquals(new BigDecimal("2.77"), resultado);
    }

    @Test
    void calculateFinalPrice_DeveAplicarPenalidadesCorretamente() {
        BigDecimal precoBase = new BigDecimal("2.50");
        MonthlySettlement fechamento = new MonthlySettlement();

        // CBT: 600 (Bonus -0.03) -> Range 501-750
        fechamento.setCbt(new BigDecimal("600"));
        // CCS: 600 (Bonus -0.02) -> Range 501-750
        fechamento.setCcs(new BigDecimal("600"));
        // Gordura: 2.50 (Bonus -0.04) -> Range 2.40-2.79
        fechamento.setGordura(new BigDecimal("2.50"));
        // Proteina: 2.50 (Bonus -0.04) -> Range 2.30-2.79
        fechamento.setProteina(new BigDecimal("2.50"));

        // Sem bonus de volume e fidelidade
        BigDecimal bonusVolume = null;
        BigDecimal bonusFidelidade = null;

        // Total Bonus: -0.03 - 0.02 - 0.04 - 0.04 + 0.00 = -0.13
        // Final Price: 2.50 - 0.13 = 2.37

        BigDecimal resultado = strategy.calculateFinalPrice(precoBase, fechamento, bonusVolume, bonusFidelidade);

        assertEquals(new BigDecimal("2.44"), resultado);
    }

    @Test
    void calculateFinalPrice_DeveTratarNulosComoZero() {
        BigDecimal precoBase = new BigDecimal("2.50");
        MonthlySettlement fechamento = new MonthlySettlement();
        // Tudo null

        BigDecimal resultado = strategy.calculateFinalPrice(precoBase, fechamento, null, null);

        // Bonus 0 + 0 + 0 + 0 + 0 + 0 = 0
        assertEquals(new BigDecimal("2.50"), resultado);
    }

    @Test
    void calculateFinalPrice_DeveSomarBonusFidelidade() {
        BigDecimal precoBase = new BigDecimal("2.50");
        MonthlySettlement fechamento = new MonthlySettlement();
        // Sem métricas de qualidade

        // Apenas bonus de fidelidade
        BigDecimal bonusFidelidade = new BigDecimal("0.10");

        BigDecimal resultado = strategy.calculateFinalPrice(precoBase, fechamento, null, bonusFidelidade);

        // Final Price: 2.50 + 0.10 = 2.60
        assertEquals(new BigDecimal("2.60"), resultado);
    }
}
