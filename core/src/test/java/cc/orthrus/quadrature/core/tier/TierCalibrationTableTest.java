package cc.orthrus.quadrature.core.tier;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Holds the calibration in force to what any calibration must be, so a figure copied wrong shows. */
final class TierCalibrationTableTest {

    private static final int[][] OUTPUTS = {{1280, 720}, {1920, 1080}, {2560, 1440}, {3840, 2160}};

    @Test
    void eachTierCostsAndHoldsAtLeastAsMuchAsTheOneBelow() {
        TierCalibration calibration = TierCalibration.CURRENT;
        for (int[] output : OUTPUTS) {
            for (int t = 1; t < Tier.values().length; t++) {
                Tier tier = Tier.values()[t];
                Tier below = Tier.values()[t - 1];
                assertTrue(calibration.referenceMs(tier, output[0], output[1])
                    >= calibration.referenceMs(below, output[0], output[1]), tier + " at " + output[0]);
                assertTrue(calibration.needMiB(tier, output[0], output[1])
                    >= calibration.needMiB(below, output[0], output[1]), tier + " at " + output[0]);
            }
        }
    }

    @Test
    void theBudgetAndBandsAreSane() {
        TierCalibration calibration = TierCalibration.CURRENT;
        assertTrue(calibration.budgetMs() > 0 && calibration.budgetMs() < 8.34);
        assertTrue(calibration.band() > 0 && calibration.band() < 0.5);
        assertTrue(calibration.memoryBand() > 0 && calibration.memoryBand() < 0.5);
        for (int[] output : OUTPUTS) {
            for (Tier tier : Tier.values()) {
                assertTrue(calibration.referenceMs(tier, output[0], output[1]) > 0);
                assertTrue(calibration.needMiB(tier, output[0], output[1]) > 0);
            }
        }
    }

    @Test
    void aCalibratedTableHasEveryReferenceFigureButTheTrace() {
        TierCalibration calibration = TierCalibration.CURRENT;
        if (!calibration.calibrated()) {
            return;
        }
        TierCalibration.Reference reference = calibration.reference();
        for (double figure : new double[] {reference.smallGBs(), reference.largeGBs(), reference.terrainMsPerMegaPrimitive(),
            reference.terrainMsPerMegapixel(), reference.lightMsPerMegaPrimitive()}) {
            assertTrue(figure > 0 && Double.isFinite(figure));
        }
    }
}
