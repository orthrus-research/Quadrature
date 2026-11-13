package cc.orthrus.quadrature.core.platform;

import java.util.List;
import java.util.Set;

/**
 * The supported platform: the capabilities every technique may assume, the vendors whose
 * capability sets are tested and admitted, and the GPU memory floor.
 *
 * @param admittedVendors names matched against the start of the context's vendor string, ignoring case
 */
public record PlatformRequirements(Set<Capability> capabilities, List<String> admittedVendors,
                                   long minimumMemoryMiB) {

    public PlatformRequirements {
        capabilities = Set.copyOf(capabilities);
        admittedVendors = List.copyOf(admittedVendors);
    }
}
