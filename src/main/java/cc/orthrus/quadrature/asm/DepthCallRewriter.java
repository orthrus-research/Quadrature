package cc.orthrus.quadrature.asm;

import net.minecraft.launchwrapper.IClassTransformer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Points every call to a depth-related OpenGL function, in every class the game loads, at the
 * method of the same name and signature in {@code DepthCalls}. Only the owner of the call changes,
 * so the stack and frames of the rewritten method are untouched.
 *
 * <p>This is what lets reversed depth cover code that sets depth state directly instead of through
 * the game's state manager: the call is found where it is made, whoever makes it.
 */
public final class DepthCallRewriter implements IClassTransformer {

    private static final boolean REQUESTED = Boolean.getBoolean("quadrature.reversedDepth");
    private static final String TARGET = "cc/orthrus/quadrature/depth/DepthCalls";
    private static final String OWN_PACKAGE = "cc.orthrus.quadrature.";
    private static final byte[] MARKER = "/opengl/GL".getBytes(StandardCharsets.US_ASCII);

    /** Name and descriptor of every call that is redirected. Each has a twin in DepthCalls. */
    private static final Set<String> CALLS = Set.of(
        "glDepthFunc(I)V",
        "glClearDepth(D)V",
        "glClearDepthf(F)V",
        "glPolygonOffset(FF)V",
        "glDepthRange(DD)V",
        "glDepthRangef(FF)V",
        "glMatrixMode(I)V",
        "glPopAttrib()V",
        "glLoadIdentity()V",
        "glLoadMatrix(Ljava/nio/FloatBuffer;)V",
        "glLoadMatrix(Ljava/nio/DoubleBuffer;)V",
        "glLoadMatrixf(Ljava/nio/FloatBuffer;)V",
        "glLoadMatrixf([F)V",
        "glLoadMatrixd(Ljava/nio/DoubleBuffer;)V",
        "glLoadMatrixd([D)V",
        "glGetInteger(I)I",
        "glGetInteger(ILjava/nio/IntBuffer;)V",
        "glGetIntegerv(ILjava/nio/IntBuffer;)V",
        "glGetIntegerv(I[I)V",
        "glGetFloat(I)F",
        "glGetFloat(ILjava/nio/FloatBuffer;)V",
        "glGetFloatv(ILjava/nio/FloatBuffer;)V",
        "glGetFloatv(I[F)V",
        "glGetDouble(I)D",
        "glGetDouble(ILjava/nio/DoubleBuffer;)V",
        "glGetDoublev(ILjava/nio/DoubleBuffer;)V",
        "glGetDoublev(I[D)V",
        "glReadPixels(IIIIIILjava/nio/FloatBuffer;)V",
        "glReadPixels(IIIIII[F)V",
        "glReadPixels(IIIIIILjava/nio/ByteBuffer;)V",
        "glReadPixels(IIIIIILjava/nio/IntBuffer;)V",
        "glReadPixels(IIIIIILjava/nio/ShortBuffer;)V");

    @Override
    public byte[] transform(String name, String transformedName, byte[] bytes) {
        // The binding's own classes call each other through these names; rewriting them would send the
        // translator's calls back to itself.
        if (!REQUESTED || bytes == null || transformedName.startsWith(OWN_PACKAGE)
            || transformedName.startsWith("org.lwjgl.") || transformedName.startsWith("org.lwjglx.")
            || !mentionsOpenGl(bytes)) {
            return bytes;
        }
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, 0);
        boolean[] changed = new boolean[1];
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String method, String descriptor, String signature,
                String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, method, descriptor, signature,
                    exceptions)) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String call, String callDescriptor,
                        boolean isInterface) {
                        if (opcode == Opcodes.INVOKESTATIC && isOpenGl(owner) && CALLS.contains(call + callDescriptor)) {
                            changed[0] = true;
                            super.visitMethodInsn(opcode, TARGET, call, callDescriptor, false);
                        } else {
                            super.visitMethodInsn(opcode, owner, call, callDescriptor, isInterface);
                        }
                    }
                };
            }
        }, 0);
        return changed[0] ? writer.toByteArray() : bytes;
    }

    /** The binding's classes, under either of the two package names calls to them carry. */
    private static boolean isOpenGl(String owner) {
        return owner.startsWith("org/lwjgl/opengl/GL") || owner.startsWith("org/lwjglx/opengl/GL");
    }

    /** A cheap test that spares parsing the many classes that never name an OpenGL class. */
    private static boolean mentionsOpenGl(byte[] bytes) {
        int last = bytes.length - MARKER.length;
        outer:
        for (int i = 0; i <= last; i++) {
            for (int j = 0; j < MARKER.length; j++) {
                if (bytes[i + j] != MARKER[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
