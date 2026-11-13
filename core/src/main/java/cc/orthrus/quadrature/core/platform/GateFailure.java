package cc.orthrus.quadrature.core.platform;

/** One reason the game cannot run Quadrature: what was found, what is required, what to do. */
public record GateFailure(String found, String required, String remedy) {
}
