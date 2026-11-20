package cc.orthrus.quadrature.frame;

import cc.orthrus.quadrature.core.scale.EntityMotion;
import cc.orthrus.quadrature.core.stage.FrameBlock;
import cc.orthrus.quadrature.core.scale.Reprojection;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.MathHelper;

import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL45;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Motion for the entities the game draws (research R4, 7b.5), for the temporal pass's motion: each frame, every entity
 * near the view that moved or turned since the frame before is given its box, grown a little, and the matrix taking a
 * point on it to where it lay the frame before ({@link EntityMotion}), both relative to the view's origin. An entity
 * that stood still needs none: the camera's motion is right for it. Off unless {@code -Dquadrature.taa.entityMotion=true}.
 * Client thread.
 */
final class EntityMotions {

    static final boolean ON = Boolean.getBoolean("quadrature.taa.entityMotion");
    /** How far from the view an entity is looked at, in blocks. */
    private static final double REACH = 96;
    /** How much a box is grown each way, in blocks: the model reaches past it at the arms and head. */
    private static final double GROW = 0.25;
    private static final int MOST = 512;
    /** A record: the box's low and high corners (four floats each) and the matrix (sixteen). */
    private static final int RECORD_BYTES = 24 * 4;

    private Int2ObjectOpenHashMap<double[]> last = new Int2ObjectOpenHashMap<>();
    private Int2ObjectOpenHashMap<double[]> now = new Int2ObjectOpenHashMap<>();
    private final ByteBuffer records = ByteBuffer.allocateDirect(MOST * RECORD_BYTES).order(ByteOrder.nativeOrder());
    private int buffer;
    private int count;
    /** Records written over the run, and frames with any. */
    long written;
    long framesWithAny;

    /**
     * Notes this frame's entities and writes the records for those that moved since the frame before.
     *
     * @param previous the frame before, or null where it cannot be reached; then no record is written
     * @return how many records the buffer holds
     */
    int gather(Reprojection.Frame current, Reprojection.Frame previous) {
        Minecraft minecraft = Minecraft.getMinecraft();
        now.clear();
        records.clear();
        count = 0;
        if (minecraft.world == null || current == null) {
            swap();
            return 0;
        }
        float partial = minecraft.getRenderPartialTicks();
        double[] origin = {current.originX(), current.originY(), current.originZ()};
        double[] previousOrigin = previous == null ? null : new double[] {previous.originX(), previous.originY(), previous.originZ()};
        Entity viewer = minecraft.getRenderViewEntity();
        boolean firstPerson = minecraft.gameSettings.thirdPersonView == 0;
        for (Entity entity : minecraft.world.loadedEntityList) {
            if (entity == viewer && firstPerson) {
                continue;
            }
            double x = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partial;
            double y = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partial;
            double z = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partial;
            double dx = x - origin[0];
            double dy = y - origin[1];
            double dz = z - origin[2];
            if (dx * dx + dy * dy + dz * dz > REACH * REACH) {
                continue;
            }
            // The yaw the game draws with: a living entity's body, interpolated the short way round; any other's own.
            double yaw;
            if (entity instanceof EntityLivingBase) {
                EntityLivingBase living = (EntityLivingBase) entity;
                yaw = living.prevRenderYawOffset + MathHelper.wrapDegrees(living.renderYawOffset - living.prevRenderYawOffset) * partial;
            } else {
                yaw = entity.prevRotationYaw + (entity.rotationYaw - entity.prevRotationYaw) * partial;
            }
            double[] place = {x, y, z, yaw};
            now.put(entity.getEntityId(), place);
            double[] before = last.get(entity.getEntityId());
            if (before == null || previousOrigin == null || count == MOST) {
                continue;
            }
            if (before[0] == x && before[1] == y && before[2] == z && before[3] == yaw) {
                continue;
            }
            AxisAlignedBB box = entity.getEntityBoundingBox();
            double ox = x - entity.posX - origin[0];
            double oy = y - entity.posY - origin[1];
            double oz = z - entity.posZ - origin[2];
            records.putFloat((float) (box.minX + ox - GROW)).putFloat((float) (box.minY + oy - GROW))
                .putFloat((float) (box.minZ + oz - GROW)).putFloat(0);
            records.putFloat((float) (box.maxX + ox + GROW)).putFloat((float) (box.maxY + oy + GROW))
                .putFloat((float) (box.maxZ + oz + GROW)).putFloat(0);
            double[] matrix = EntityMotion.toPrevious(new double[] {x, y, z}, yaw, before, before[3], origin, previousOrigin);
            for (float f : FrameBlock.floats(matrix)) {
                records.putFloat(f);
            }
            count++;
        }
        swap();
        if (count > 0) {
            records.flip();
            if (buffer == 0) {
                buffer = GL45.glCreateBuffers();
            }
            // Each frame's records replace the last's whole.
            GL45.glNamedBufferData(buffer, records, GL15.GL_STREAM_DRAW);
            written += count;
            framesWithAny++;
        }
        return count;
    }

    private void swap() {
        Int2ObjectOpenHashMap<double[]> kept = last;
        last = now;
        now = kept;
    }

    /** The buffer the records lie in; 0 before the first. */
    int buffer() {
        return buffer;
    }

    void delete() {
        if (buffer != 0) {
            GL15.glDeleteBuffers(buffer);
            buffer = 0;
        }
    }
}
