import {
    getBonusCBT,
    getBonusCCS,
    getBonusGordura,
    getBonusProteina,
    calculateFinalPrice
} from '../../utils/pricingLogic';

describe('Pricing Logic Unit Tests (Generic Placeholder Tiers)', () => {

    describe('getBonusCBT / getBonusCCS (lower is better)', () => {
        test('returns 0 for null or undefined', () => {
            expect(getBonusCBT(null)).toBe(0);
            expect(getBonusCBT(undefined)).toBe(0);
            expect(getBonusCCS(null)).toBe(0);
        });

        test('applies generic Grade A/B/Standard/Below-Standard tiers', () => {
            expect(getBonusCBT(50)).toBe(0.05);
            expect(getBonusCBT(100)).toBe(0.05);
            expect(getBonusCBT(250)).toBe(0.02);
            expect(getBonusCBT(500)).toBe(0);
            expect(getBonusCBT(800)).toBe(-0.04);

            expect(getBonusCCS(80)).toBe(0.05);
            expect(getBonusCCS(200)).toBe(0.02);
            expect(getBonusCCS(450)).toBe(0);
            expect(getBonusCCS(900)).toBe(-0.04);
        });
    });

    describe('getBonusGordura / getBonusProteina (higher is better)', () => {
        test('returns 0 for null or undefined', () => {
            expect(getBonusGordura(null)).toBe(0);
            expect(getBonusProteina(null)).toBe(0);
        });

        test('applies generic Grade A/B/Standard/Below-Standard tiers', () => {
            expect(getBonusGordura(4.0)).toBe(0.04);
            expect(getBonusGordura(3.5)).toBe(0.02);
            expect(getBonusGordura(3.1)).toBe(0);
            expect(getBonusGordura(2.7)).toBe(-0.03);

            expect(getBonusProteina(3.9)).toBe(0.04);
            expect(getBonusProteina(3.4)).toBe(0.02);
            expect(getBonusProteina(3.0)).toBe(0);
            expect(getBonusProteina(2.8)).toBe(-0.03);
        });
    });

    describe('calculateFinalPrice', () => {
        test('sums base price, quality tiers, and manual commercial bonuses', () => {
            // 2.50 + 0.05 + 0.02 + 0.04 + 0.02 + 0.03 (volume) + 0.01 (loyalty) = 2.67
            expect(calculateFinalPrice(2.50, 80, 200, 3.9, 3.4, 0.01, 0.03)).toBe(2.67);
        });

        test('treats null metrics as 0 adjustment', () => {
            expect(calculateFinalPrice(2.50, null, null, null, null, 0, null)).toBe(2.50);
        });
    });
});
