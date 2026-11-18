package cc.orthrus.quadrature.core.platform;

/**
 * GPU memory as one driver reports it, in KiB. A figure the source cannot give is {@link #UNKNOWN}.
 *
 * @param dedicatedKiB the card's own memory
 * @param totalAvailableKiB memory the driver can place work in, the card's own and any it borrows
 * @param currentAvailableKiB how much of the card's own memory is free now
 * @param evictions how many times the driver has moved memory off the card since the system started
 * @param evictedKiB how much it has moved off the card since the system started
 */
public record GpuMemory(long dedicatedKiB, long totalAvailableKiB, long currentAvailableKiB, long evictions,
                        long evictedKiB) {

    public static final long UNKNOWN = -1;

    /** What a platform whose driver reports nothing gives. */
    public static final GpuMemory NOT_REPORTED = new GpuMemory(UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN);
}
