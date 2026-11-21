package cc.orthrus.quadrature.terrain;

import cc.orthrus.quadrature.core.terrain.CubeKey;
import cc.orthrus.quadrature.core.terrain.CubeList;

import io.github.opencubicchunks.cubicchunks.api.world.ICube;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ClassInheritanceMultiMap;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.client.MinecraftForgeClient;

/**
 * Entities and block entities in a world whose chunk system Quadrature has taken over ({@link GameChunks}), drawn for
 * the cubes the player's view sees (PLAN item 12g). The game draws both per render chunk its own search reached; with
 * that search gone, its two loops find nothing and these take their place, at the same points of its entity pass and
 * with the same tests, reading each seen cube's entities and block entities from the cube itself. The view's search
 * lists every loaded cube it reaches, empty ones included, and never leaves out one the game's search would reach.
 *
 * <p>Block entities whose renderer draws them wherever they are ("global" renderers, such as a beacon's beam) are not
 * drawn per seen cube: the game keeps them in a set it draws every frame, which its chunk rebuild fills, and which
 * this class fills instead as Quadrature places and forgets the meshes of their cubes.
 *
 * <p>Client thread only. Loaded only where Cubature is installed.
 */
public final class EntityPass {


    /** Each cube's block entities with global renderers, as last handed to the game's set. */
    private static final Long2ObjectOpenHashMap<List<TileEntity>> GLOBALS = new Long2ObjectOpenHashMap<>();

    private EntityPass() {
    }

    /**
     * The game's entity loop, over the seen cubes. Fills {@code outlined} and {@code multipass} as the game's loop does,
     * for the game to draw after it.
     */
    public static void entities(EntityPassHost host, World world, Entity viewer, ICamera camera, float partialTicks,
        List<Entity> outlined, List<Entity> multipass) {
        CubeList seen = TerrainHook.seen();
        if (seen == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        RenderManager manager = minecraft.getRenderManager();
        Entity view = minecraft.getRenderViewEntity();
        double x = viewer.prevPosX + (viewer.posX - viewer.prevPosX) * partialTicks;
        double y = viewer.prevPosY + (viewer.posY - viewer.prevPosY) * partialTicks;
        double z = viewer.prevPosZ + (viewer.posZ - viewer.prevPosZ) * partialTicks;
        int pass = MinecraftForgeClient.getRenderPass();
        boolean sleeping = view instanceof EntityLivingBase && ((EntityLivingBase) view).isPlayerSleeping();
        BlockPos.PooledMutableBlockPos at = BlockPos.PooledMutableBlockPos.retain();
        try {
            for (int i = 0, count = seen.count(); i < count; i++) {
                long key = seen.key(i);
                ICube cube = CubicWorlds.loadedCube(world, CubeKey.x(key), CubeKey.y(key), CubeKey.z(key));
                if (cube == null) {
                    continue;
                }
                ClassInheritanceMultiMap<Entity> entities = cube.getEntitySet();
                if (entities.isEmpty()) {
                    continue;
                }
                for (Entity entity : entities) {
                    if (!entity.shouldRenderInPass(pass)) {
                        continue;
                    }
                    if (!manager.shouldRender(entity, camera, x, y, z) && !entity.isRidingOrBeingRiddenBy(minecraft.player)) {
                        continue;
                    }
                    if (entity == view && minecraft.gameSettings.thirdPersonView == 0 && !sleeping) {
                        continue;
                    }
                    // The game's own test, kept as it is: an entity within the old height limits is drawn only where
                    // its block is loaded.
                    if (entity.posY >= 0.0 && entity.posY < 256.0 && !world.isBlockLoaded(at.setPos(entity))) {
                        continue;
                    }
                    host.quadrature$counted();
                    manager.renderEntityStatic(entity, partialTicks, false);
                    if (host.quadrature$outlined(entity, view, camera)) {
                        outlined.add(entity);
                    }
                    if (manager.isRenderMultipass(entity)) {
                        multipass.add(entity);
                    }
                }
            }
        } finally {
            at.release();
        }
    }

    /** The game's block entity loop, over the seen cubes; those with global renderers are left to the game's set. */
    public static void blockEntities(World world, ICamera camera, float partialTicks) {
        CubeList seen = TerrainHook.seen();
        if (seen == null) {
            return;
        }
        TileEntityRendererDispatcher dispatcher = TileEntityRendererDispatcher.instance;
        int pass = MinecraftForgeClient.getRenderPass();
        for (int i = 0, count = seen.count(); i < count; i++) {
            long key = seen.key(i);
            ICube cube = CubicWorlds.loadedCube(world, CubeKey.x(key), CubeKey.y(key), CubeKey.z(key));
            if (cube == null) {
                continue;
            }
            Map<BlockPos, TileEntity> blockEntities = cube.getTileEntityMap();
            if (blockEntities.isEmpty()) {
                continue;
            }
            for (TileEntity blockEntity : blockEntities.values()) {
                TileEntitySpecialRenderer<TileEntity> renderer = dispatcher.getRenderer(blockEntity);
                if (renderer == null || renderer.isGlobalRenderer(blockEntity)) {
                    continue;
                }
                if (!blockEntity.shouldRenderInPass(pass) || !camera.isBoundingBoxInFrustum(blockEntity.getRenderBoundingBox())) {
                    continue;
                }
                dispatcher.render(blockEntity, partialTicks, -1);
            }
        }
    }

    /** A cube's mesh was placed, or the cube found to hold nothing: its global block entities are handed over anew. */
    static void placed(World world, int x, int y, int z) {
        if (!GameChunks.takesOver(world)) {
            return;
        }
        long key = CubeKey.of(x, y, z);
        ICube cube = CubicWorlds.loadedCube(world, x, y, z);
        List<TileEntity> now = Collections.emptyList();
        if (cube != null && !cube.getTileEntityMap().isEmpty()) {
            TileEntityRendererDispatcher dispatcher = TileEntityRendererDispatcher.instance;
            for (TileEntity blockEntity : cube.getTileEntityMap().values()) {
                TileEntitySpecialRenderer<TileEntity> renderer = dispatcher.getRenderer(blockEntity);
                if (renderer != null && renderer.isGlobalRenderer(blockEntity)) {
                    if (now.isEmpty()) {
                        now = new ArrayList<>();
                    }
                    now.add(blockEntity);
                }
            }
        }
        List<TileEntity> before = GLOBALS.get(key);
        if (before == null && now.isEmpty()) {
            return;
        }
        List<TileEntity> removed = new ArrayList<>(before == null ? Collections.emptyList() : before);
        removed.removeAll(now);
        List<TileEntity> added = new ArrayList<>(now);
        if (before != null) {
            added.removeAll(before);
        }
        Minecraft.getMinecraft().renderGlobal.updateTileEntities(removed, added);
        if (now.isEmpty()) {
            GLOBALS.remove(key);
        } else {
            GLOBALS.put(key, now);
        }
    }

    /** A cube is no longer held: its global block entities leave the game's set. */
    static void forgotten(int x, int y, int z) {
        List<TileEntity> before = GLOBALS.remove(CubeKey.of(x, y, z));
        if (before != null) {
            Minecraft.getMinecraft().renderGlobal.updateTileEntities(before, Collections.emptyList());
        }
    }

    /** Another world: the game cleared its set when it loaded its renderers for it, and nothing here carries over. */
    static void cleared() {
        GLOBALS.clear();
    }

    /** How many block entities with global renderers are handed to the game's set. */
    static int globals() {
        int count = 0;
        for (List<TileEntity> list : GLOBALS.values()) {
            count += list.size();
        }
        return count;
    }
}
