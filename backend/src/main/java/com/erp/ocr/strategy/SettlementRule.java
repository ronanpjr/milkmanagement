package com.erp.ocr.strategy;

import com.erp.ocr.model.MonthlySettlement;
import java.math.BigDecimal;

/**
 * Contract for pluggable settlement pricing and adjustment rules.
 * <p>
 * Implementations encapsulate organization-specific pricing formulas (e.g.,
 * flat rate, tiered quality grades, volume incentives) without coupling the
 * core settlement workflow to proprietary business logic.
 */
public interface SettlementRule {

    /**
     * Computes the final unit price for a monthly settlement.
     *
     * @param basePrice     agreed base unit price
     * @param settlement    settlement record containing quality/volume metrics
     * @param volumeBonus   optional manual volume adjustment
     * @param loyaltyBonus  optional manual loyalty adjustment
     * @return computed final unit price
     */
    BigDecimal calculateFinalPrice(BigDecimal basePrice, MonthlySettlement settlement,
            BigDecimal volumeBonus, BigDecimal loyaltyBonus);
}
