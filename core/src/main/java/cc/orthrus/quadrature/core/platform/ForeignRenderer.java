package cc.orthrus.quadrature.core.platform;

/**
 * Another renderer Quadrature does not run beside.
 *
 * @param modId          its mod ID, or null if it is not a mod (a tweaker)
 * @param markerResource a class resource that identifies it without loading a class, or null
 */
public record ForeignRenderer(String name, String modId, String markerResource) {
}
