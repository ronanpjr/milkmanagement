package com.erp.ocr.strategy;

import com.erp.ocr.model.MonthlySettlement;
import java.math.BigDecimal;

/**
 * Default tiered pricing strategy using generic placeholder grade bands.
 * <p>
 * Demonstrates how an organization can plug in multi-factor quality and
 * volume adjustments on top of {@code basePrice}. Replace the placeholder
 * thresholds below with your domain-specific grading schedule.
 */
public class DefaultTieredPricingStrategy implements PricingStrategy {

    @Override
    public BigDecimal calculateFinalPrice(BigDecimal basePrice, MonthlySettlement settlement,
            BigDecimal volumeBonus, BigDecimal loyaltyBonus) {
        BigDecimal totalAdjustment = BigDecimal.ZERO;

        totalAdjustment = totalAdjustment.add(evaluateLowerIsBetterTier(settlement.getCbt()));
        totalAdjustment = totalAdjustment.add(evaluateLowerIsBetterTier(settlement.getCcs()));
        totalAdjustment = totalAdjustment.add(evaluateHigherIsBetterTier(settlement.getGordura()));
        totalAdjustment = totalAdjustment.add(evaluateHigherIsBetterTier(settlement.getProteina()));

        if (volumeBonus != null) {
            totalAdjustment = totalAdjustment.add(volumeBonus);
        }
        if (loyaltyBonus != null) {
            totalAdjustment = totalAdjustment.add(loyaltyBonus);
        }

        return basePrice.add(totalAdjustment);
    }

    /**
     * Generic placeholder tier for metrics where lower values represent higher quality
     * (e.g., impurity or defect counts).
     */
    private BigDecimal evaluateLowerIsBetterTier(BigDecimal metric) {
        if (metric == null) {
            return BigDecimal.ZERO;
        }
        double value = metric.doubleValue();
        if (value <= 100) {
            return new BigDecimal("0.05");  // Grade A bonus
        }
        if (value <= 300) {
            return new BigDecimal("0.02");  // Grade B bonus
        }
        if (value <= 600) {
            return BigDecimal.ZERO;         // Standard baseline
        }
        return new BigDecimal("-0.04");     // Below-standard deduction
    }

    /**
     * Generic placeholder tier for metrics where higher values represent higher quality
     * (e.g., purity or concentration percentages).
     */
    private BigDecimal evaluateHigherIsBetterTier(BigDecimal metric) {
        if (metric == null) {
            return BigDecimal.ZERO;
        }
        double value = metric.doubleValue();
        if (value >= 3.80) {
            return new BigDecimal("0.04");  // Grade A bonus
        }
        if (value >= 3.30) {
            return new BigDecimal("0.02");  // Grade B bonus
        }
        if (value >= 3.00) {
            return BigDecimal.ZERO;         // Standard baseline
        }
        return new BigDecimal("-0.03");     // Below-standard deduction
    }
}
