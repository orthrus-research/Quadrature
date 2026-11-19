package cc.orthrus.quadrature.core.handoff;

import cc.orthrus.quadrature.core.platform.CapabilityReport;

import java.util.Arrays;
import java.util.List;

/**
 * What identifies a GPU and its driver to two graphics APIs, as OpenGL's external objects and Vulkan's
 * device properties report it. Memory and semaphores exported as opaque Win32 handles may only be imported
 * where the device UUID and the driver UUID both match (Vulkan's compatibility tables for those handle
 * types; OpenGL's external objects, section 4.2.1). The LUID names the same adapter to DXGI; where both
 * sides report one, it is compared too.
 *
 * @param deviceUuid 16 bytes
 * @param driverUuid 16 bytes
 * @param luid       8 bytes, or null where the API reports none or reports it invalid
 * @param nodeMask   the device nodes used, 0 where unknown
 */
public record DeviceIdentity(byte[] deviceUuid, byte[] driverUuid, byte[] luid, int nodeMask) {

    public DeviceIdentity {
        deviceUuid = deviceUuid.clone();
        driverUuid = driverUuid.clone();
        luid = luid == null ? null : luid.clone();
    }

    /**
     * Why memory exported from the device named by {@code exporter} cannot be imported by a context on one of
     * {@code importers} (OpenGL reports one UUID per device of a device group, with one driver UUID and LUID),
     * or null when it can.
     */
    public static String mismatch(List<DeviceIdentity> importers, DeviceIdentity exporter) {
        if (importers.isEmpty()) {
            return "The OpenGL context reports no device UUID.";
        }
        DeviceIdentity first = importers.get(0);
        if (!Arrays.equals(first.driverUuid, exporter.driverUuid)) {
            return "The driver UUIDs differ (OpenGL " + CapabilityReport.fingerprint(first.driverUuid) + ", Vulkan "
                + CapabilityReport.fingerprint(exporter.driverUuid) + ").";
        }
        boolean device = importers.stream().anyMatch(importer -> Arrays.equals(importer.deviceUuid, exporter.deviceUuid));
        if (!device) {
            return "No device UUID of the OpenGL context matches the Vulkan device's ("
                + CapabilityReport.fingerprint(exporter.deviceUuid) + ").";
        }
        if (first.luid != null && exporter.luid != null && !Arrays.equals(first.luid, exporter.luid)) {
            return "The device UUIDs match but the LUIDs differ (OpenGL " + CapabilityReport.fingerprint(first.luid)
                + ", Vulkan " + CapabilityReport.fingerprint(exporter.luid) + ").";
        }
        return null;
    }

    /** Fingerprints only, as the capability dump writes identifiers. */
    public String describe() {
        return "device " + CapabilityReport.fingerprint(deviceUuid) + ", driver " + CapabilityReport.fingerprint(driverUuid)
            + ", LUID " + (luid == null ? "none" : CapabilityReport.fingerprint(luid)) + ", node mask " + nodeMask;
    }
}
