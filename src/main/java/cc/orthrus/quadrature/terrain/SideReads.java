package cc.orthrus.quadrature.terrain;

import cc.orthrus.quadrature.core.terrain.TouchingSides;

import io.github.opencubicchunks.cubicchunks.api.client.ICubeSnapshot;

import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.Biome;

import javax.annotation.Nullable;

/**
 * A cube's snapshot as a build reads it, noting which of the 26 cubes around it the build read and for what:
 * their blocks (states, tile entities, biomes, power, solid sides) or their light. A cube touching it can change
 * what the build drew only through what the build read of it, so a later change there needs a rebuild only where
 * this says the build read that cube for that kind of thing (Terrain's side-aware rebuilds).
 *
 * <p>The game's renderers read the light around a block only for a face they draw, after the face test, so a
 * block whose faces are all hidden reads its face neighbors' states and nothing of the cubes at its edges and
 * corners. Sides are {@link TouchingSides}' bits; what the cube reads of itself sets none. One per mesher, set for
 * each build; not thread-safe.
 */
final class SideReads implements ICubeSnapshot {

    private ICubeSnapshot snapshot;
    private int minX;
    private int minY;
    private int minZ;
    private int blocks;
    private int light;

    /** Starts a build over a snapshot of the cube whose lowest corner is the given block. */
    SideReads begin(ICubeSnapshot snapshot, int minX, int minY, int minZ) {
        this.snapshot = snapshot;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        blocks = 0;
        light = 0;
        return this;
    }

    /** The sides whose blocks the build read, as bits. */
    int blocks() {
        return blocks;
    }

    /** The sides whose light the build read, as bits. */
    int light() {
        return light;
    }

    /** Lets the snapshot go. */
    void end() {
        snapshot = null;
    }

    /** The bit of the cube a block lies in, relative to this one; reads beyond one cube count as the nearest. */
    private int side(BlockPos pos) {
        return TouchingSides.bit(TouchingSides.offset(pos.getX(), minX), TouchingSides.offset(pos.getY(), minY),
            TouchingSides.offset(pos.getZ(), minZ));
    }

    private void block(BlockPos pos) {
        blocks |= side(pos) & TouchingSides.ALL;
    }

    private void lit(BlockPos pos) {
        light |= side(pos) & TouchingSides.ALL;
    }

    // IBlockAccess, each read noted.

    @Nullable
    @Override
    public TileEntity getTileEntity(BlockPos pos) {
        block(pos);
        return snapshot.getTileEntity(pos);
    }

    @Override
    public int getCombinedLight(BlockPos pos, int lightValue) {
        lit(pos);
        return snapshot.getCombinedLight(pos, lightValue);
    }

    @Override
    public int getLightFor(EnumSkyBlock type, BlockPos pos) {
        lit(pos);
        return snapshot.getLightFor(type, pos);
    }

    @Override
    public IBlockState getBlockState(BlockPos pos) {
        block(pos);
        return snapshot.getBlockState(pos);
    }

    @Override
    public boolean isAirBlock(BlockPos pos) {
        block(pos);
        return snapshot.isAirBlock(pos);
    }

    @Override
    public Biome getBiome(BlockPos pos) {
        block(pos);
        return snapshot.getBiome(pos);
    }

    @Override
    public int getStrongPower(BlockPos pos, EnumFacing direction) {
        block(pos);
        return snapshot.getStrongPower(pos, direction);
    }

    @Override
    public WorldType getWorldType() {
        return snapshot.getWorldType();
    }

    @Override
    public boolean isSideSolid(BlockPos pos, EnumFacing side, boolean fallback) {
        block(pos);
        return snapshot.isSideSolid(pos, side, fallback);
    }

    // The snapshot's own questions, passed on unnoted: they ask about versions and presence, not content.

    @Override
    public int centerX() {
        return snapshot.centerX();
    }

    @Override
    public int centerY() {
        return snapshot.centerY();
    }

    @Override
    public int centerZ() {
        return snapshot.centerZ();
    }

    @Override
    public long worldIdentity() {
        return snapshot.worldIdentity();
    }

    @Override
    public boolean present(int dx, int dy, int dz) {
        return snapshot.present(dx, dy, dz);
    }

    @Override
    public long blockVersion(int dx, int dy, int dz) {
        return snapshot.blockVersion(dx, dy, dz);
    }

    @Override
    public long lightVersion(int dx, int dy, int dz) {
        return snapshot.lightVersion(dx, dy, dz);
    }

    @Override
    public long version(int dx, int dy, int dz) {
        return snapshot.version(dx, dy, dz);
    }

    @Override
    public boolean centerEmpty() {
        return snapshot.centerEmpty();
    }

    @Override
    public void release() {
        snapshot.release();
    }
}
