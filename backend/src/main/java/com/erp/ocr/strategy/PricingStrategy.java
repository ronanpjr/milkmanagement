package com.erp.ocr.strategy;

/**
 * Strategy interface for computing supplier settlement unit prices.
 * <p>
 * Extends {@link SettlementRule} to allow runtime selection between
 * standard flat-rate pricing and tiered quality/grade-based pricing.
 */
public interface PricingStrategy extends SettlementRule {
}
