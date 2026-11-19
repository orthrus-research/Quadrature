package cc.orthrus.quadrature.core.handoff;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;

class DeviceIdentityTest {

    private static byte[] bytes(int length, int fill) {
        byte[] value = new byte[length];
        java.util.Arrays.fill(value, (byte) fill);
        return value;
    }

    private static final DeviceIdentity NVIDIA = new DeviceIdentity(bytes(16, 1), bytes(16, 2), bytes(8, 3), 1);

    @Test
    void theSameDeviceAndDriverMatch() {
        assertNull(DeviceIdentity.mismatch(List.of(NVIDIA), new DeviceIdentity(bytes(16, 1), bytes(16, 2), bytes(8, 3), 1)));
        // A side that reports no valid LUID is matched by its UUIDs alone.
        assertNull(DeviceIdentity.mismatch(List.of(NVIDIA), new DeviceIdentity(bytes(16, 1), bytes(16, 2), null, 0)));
    }

    @Test
    void anotherAdaptersDeviceIsRefused() {
        DeviceIdentity integrated = new DeviceIdentity(bytes(16, 9), bytes(16, 8), bytes(8, 7), 1);
        String reason = DeviceIdentity.mismatch(List.of(NVIDIA), integrated);
        assertTrue(reason.startsWith("The driver UUIDs differ"), reason);
    }

    @Test
    void theSameDriverOnAnotherDeviceIsRefused() {
        String reason = DeviceIdentity.mismatch(List.of(NVIDIA), new DeviceIdentity(bytes(16, 5), bytes(16, 2), bytes(8, 3), 1));
        assertTrue(reason.startsWith("No device UUID"), reason);
    }

    @Test
    void anyDeviceOfAGroupMatches() {
        DeviceIdentity second = new DeviceIdentity(bytes(16, 6), bytes(16, 2), bytes(8, 3), 2);
        assertNull(DeviceIdentity.mismatch(List.of(NVIDIA, second), new DeviceIdentity(bytes(16, 6), bytes(16, 2), null, 0)));
    }

    @Test
    void differingLuidsAreRefusedAndNothingIsRefusedWithoutAContextUuid() {
        String reason = DeviceIdentity.mismatch(List.of(NVIDIA), new DeviceIdentity(bytes(16, 1), bytes(16, 2), bytes(8, 4), 1));
        assertTrue(reason.contains("LUIDs differ"), reason);
        assertTrue(DeviceIdentity.mismatch(List.of(), NVIDIA).contains("no device UUID"));
    }

    @Test
    void identifiersAreDescribedByFingerprintOnly() {
        String described = NVIDIA.describe();
        assertTrue(described.startsWith("device sha256 "), described);
        assertTrue(!described.contains("0101"), described);
    }
}
