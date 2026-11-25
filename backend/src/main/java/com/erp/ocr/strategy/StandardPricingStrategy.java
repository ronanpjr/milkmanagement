package com.erp.ocr.strategy;

import com.erp.ocr.model.MonthlySettlement;
import java.math.BigDecimal;

/**
 * Standard flat-rate pricing strategy.
 * <p>
 * Applies the agreed {@code basePrice} plus any manual commercial adjustments
 * ({@code volumeBonus} and {@code loyaltyBonus}) recorded on the settlement.
 * Quality tier metrics are ignored by this strategy.
 */
public class StandardPricingStrategy implements PricingStrategy {

    @Override
    public BigDecimal calculateFinalPrice(BigDecimal basePrice, MonthlySettlement settlement,
            BigDecimal volumeBonus, BigDecimal loyaltyBonus) {
        BigDecimal totalAdjustment = BigDecimal.ZERO;

        if (volumeBonus != null) {
            totalAdjustment = totalAdjustment.add(volumeBonus);
        }
        if (loyaltyBonus != null) {
            totalAdjustment = totalAdjustment.add(loyaltyBonus);
        }

        return basePrice.add(totalAdjustment);
    }
}
