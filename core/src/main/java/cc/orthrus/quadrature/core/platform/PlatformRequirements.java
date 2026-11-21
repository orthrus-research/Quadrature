package cc.orthrus.quadrature.core.platform;

import java.util.List;
import java.util.Set;

/**
 * The supported platform: the capabilities every technique may assume, the vendors whose
 * capability sets are tested and admitted, the GPU memory floor, and each admitted vendor's
 * driver floor (D20).
 *
 * @param admittedVendors names matched against the start of the context's vendor string, ignoring case
 * @param driverFloors    one for each admitted vendor that has one; a vendor without one has no driver check
 */
public record PlatformRequirements(Set<Capability> capabilities, List<String> admittedVendors,
                                   long minimumMemoryMiB, List<DriverFloor> driverFloors) {

    public PlatformRequirements {
        capabilities = Set.copyOf(capabilities);
        admittedVendors = List.copyOf(admittedVendors);
        driverFloors = List.copyOf(driverFloors);
    }

    /** Requirements with no driver floor. */
    public PlatformRequirements(Set<Capability> capabilities, List<String> admittedVendors, long minimumMemoryMiB) {
        this(capabilities, admittedVendors, minimumMemoryMiB, List.of());
    }
}
