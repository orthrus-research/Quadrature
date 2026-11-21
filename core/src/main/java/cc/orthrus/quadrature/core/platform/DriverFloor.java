package cc.orthrus.quadrature.core.platform;

import java.util.List;

/**
 * An admitted vendor's driver policy (D20): the oldest branch the gate lets the game run on, the branch below
 * which it warns, and builds known to fail, refused whatever their branch.
 *
 * @param vendor          matched against the start of the context's vendor string, ignoring case, as the admitted
 *                        vendors are; also the name looked for before the driver's version in the version string
 * @param floorBranch     the oldest branch the game runs on
 * @param warnBelowBranch the branch below which the gate warns: the vendor's current production branch
 * @param knownBad        driver versions, as written, refused whatever their branch
 */
public record DriverFloor(String vendor, int floorBranch, int warnBelowBranch, List<String> knownBad) {

    public DriverFloor {
        knownBad = List.copyOf(knownBad);
    }
}
