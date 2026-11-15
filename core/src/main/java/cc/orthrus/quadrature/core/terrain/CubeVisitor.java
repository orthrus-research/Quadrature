package cc.orthrus.quadrature.core.terrain;

/** Receives one cube position. */
@FunctionalInterface
public interface CubeVisitor {
    void cube(int cubeX, int cubeY, int cubeZ);
}
