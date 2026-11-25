// Live preview helper mirroring backend DefaultTieredPricingStrategy.
// The backend is the single source of truth for settlement totals; this module
// only powers the live UI preview and itemized breakdown display.

const evaluateLowerIsBetterTier = (val) => {
    if (!val && val !== 0) return 0;
    if (val <= 100) return 0.05;  // Grade A bonus
    if (val <= 300) return 0.02;  // Grade B bonus
    if (val <= 600) return 0;     // Standard baseline
    return -0.04;                 // Below-standard deduction
};

const evaluateHigherIsBetterTier = (val) => {
    if (!val && val !== 0) return 0;
    if (val >= 3.80) return 0.04; // Grade A bonus
    if (val >= 3.30) return 0.02; // Grade B bonus
    if (val >= 3.00) return 0;    // Standard baseline
    return -0.03;                 // Below-standard deduction
};

export const getBonusCBT = (val) => evaluateLowerIsBetterTier(val);

export const getBonusCCS = (val) => evaluateLowerIsBetterTier(val);

export const getBonusGordura = (val) => evaluateHigherIsBetterTier(val);

export const getBonusProteina = (val) => evaluateHigherIsBetterTier(val);

export const calculateFinalPrice = (priceBase, cbt, ccs, gordura, proteina, fidelidade = 0, bonusVolume = null) => {
    const vCBT = getBonusCBT(cbt);
    const vCCS = getBonusCCS(ccs);
    const vGordura = getBonusGordura(gordura);
    const vProteina = getBonusProteina(proteina);
    const vVolume = bonusVolume !== null ? (parseFloat(bonusVolume) || 0) : 0;
    const vFidelidade = parseFloat(fidelidade) || 0;

    const totalBonus = vCBT + vCCS + vGordura + vProteina + vVolume + vFidelidade;
    const finalPrice = (priceBase || 0) + totalBonus;

    return Math.round(finalPrice * 10000) / 10000;
};
