package cc.orthrus.quadrature.terrain;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.core.terrain.CubeKey;
import cc.orthrus.quadrature.mixin.RenderGlobalAccess;
import cc.orthrus.quadrature.mixin.RenderInfoAccess;
import cc.orthrus.quadrature.mixin.ViewFrustumAccess;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A diagnostic, off unless {@code -Dquadrature.terrain.compare=true}: sets the cubes Quadrature draws
 * beside the chunks the game selected for the same frame, and for every cube only one of them has,
 * says why, by evaluating the game's own conditions.
 *
 * <p>The game finds what to draw by a search outward from the camera's chunk. A chunk is entered from
 * a neighbor already reached, in direction d, only if all of these hold: the search has not travelled
 * in the direction opposite to d on its way to that neighbor; the neighbor's own geometry lets a line
 * of sight pass from the face it was entered through to its face toward d; the chunk has not been
 * reached already; and the chunk's box is inside the view frustum. A chunk reached is drawn if its
 * mesh has been built and is not empty.
 */
final class SelectionComparison {

    static final boolean ENABLED = Boolean.getBoolean("quadrature.terrain.compare");

    /**
     * A second diagnostic, off unless {@code -Dquadrature.terrain.gameSelection=true} or
     * {@code inverse}, and independent of the comparison: Quadrature draws only the cubes the game
     * selected, or only those it did not, so that a frame difference caused by drawing what the game's
     * search leaves out can be told from any other and then seen alone.
     */
    static final int GAME_SELECTION = selectionMode(System.getProperty("quadrature.terrain.gameSelection"));
    static final int ONLY_SELECTED = 1;
    static final int ONLY_UNSELECTED = 2;

    private static int selectionMode(String value) {
        return "true".equals(value) ? ONLY_SELECTED : "inverse".equals(value) ? ONLY_UNSELECTED : 0;
    }

    private static final int EVERY_FRAMES = 240;
    private static final int EXAMPLES = 4;

    private final Frustum frustum = new Frustum();
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private int frames;

    /** Fills {@code into} with the cube keys of the chunks the game's search reached for this frame. */
    static void selected(LongOpenHashSet into) {
        into.clear();
        List<?> infos = ((RenderGlobalAccess) Minecraft.getMinecraft().renderGlobal).getQuadratureRenderInfos();
        for (int i = 0, size = infos.size(); i < size; i++) {
            BlockPos at = ((RenderInfoAccess) infos.get(i)).getQuadratureRenderChunk().getPosition();
            into.add(CubeKey.of(at.getX() >> 4, at.getY() >> 4, at.getZ() >> 4));
        }
    }

    /**
     * @param drawn the cubes Quadrature drew geometry for in this frame, as cube keys
     * @param across the horizontal render distance, in cubes
     * @param upward the vertical render distance, in cubes
     * @param explain says, for a cube the game draws and Quadrature did not, what Quadrature knows of it
     */
    void compare(LongOpenHashSet drawn, double cameraX, double cameraY, double cameraZ, int across,
        int upward, java.util.function.LongFunction<String> explain) {
        if (++frames % EVERY_FRAMES != 0) {
            return;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        RenderGlobalAccess global = (RenderGlobalAccess) minecraft.renderGlobal;
        ViewFrustumAccess grid = (ViewFrustumAccess) global.getQuadratureViewFrustum();
        boolean occlusion = minecraft.renderChunksMany;
        frustum.setPosition(cameraX, cameraY, cameraZ);

        Long2ObjectOpenHashMap<RenderInfoAccess> selected = new Long2ObjectOpenHashMap<>();
        for (Object entry : global.getQuadratureRenderInfos()) {
            RenderInfoAccess info = (RenderInfoAccess) entry;
            BlockPos at = info.getQuadratureRenderChunk().getPosition();
            selected.put(CubeKey.of(at.getX() >> 4, at.getY() >> 4, at.getZ() >> 4), info);
        }

        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, List<String>> examples = new LinkedHashMap<>();
        int gameDraws = 0;
        int both = 0;
        for (RenderInfoAccess info : selected.values()) {
            CompiledChunk compiled = info.getQuadratureRenderChunk().getCompiledChunk();
            if (compiled != CompiledChunk.DUMMY && !compiled.isEmpty()) {
                gameDraws++;
            }
        }
        for (long key : drawn) {
            int x = CubeKey.x(key);
            int y = CubeKey.y(key);
            int z = CubeKey.z(key);
            RenderInfoAccess info = selected.get(key);
            String reason;
            String detail = "";
            if (info != null) {
                CompiledChunk compiled = info.getQuadratureRenderChunk().getCompiledChunk();
                if (compiled == CompiledChunk.DUMMY) {
                    RenderChunk chunk = info.getQuadratureRenderChunk();
                    boolean marked = chunk.needsUpdate();
                    boolean queued = global.getQuadratureChunksToUpdate().contains(chunk);
                    boolean edge = Math.abs(x - ((int) Math.floor(cameraX) >> 4)) >= across
                        || Math.abs(z - ((int) Math.floor(cameraZ) >> 4)) >= across
                        || Math.abs(y - ((int) Math.floor(cameraY) >> 4)) >= upward;
                    reason = "selected by the game, its mesh never built ("
                        + (marked ? "marked for building" : "not marked for building")
                        + (queued ? ", in the build queue" : ", not in the build queue")
                        + (edge ? ", on the grid's outermost row)" : ", inside the grid)");
                } else if (compiled.isEmpty()) {
                    reason = "selected by the game, its mesh empty";
                } else {
                    both++;
                    continue;
                }
            } else {
                RenderChunk chunk = grid.invokeQuadratureGetRenderChunk(pos.setPos((x << 4) + 8, (y << 4) + 8,
                    (z << 4) + 8));
                if (chunk == null) {
                    reason = "outside the game's grid of render chunks";
                } else {
                    boolean inFrustum = frustum.isBoundingBoxInFrustum(chunk.boundingBox);
                    int reached = 0;
                    int direction = 0;
                    int sight = 0;
                    int open = 0;
                    StringBuilder notes = new StringBuilder();
                    for (EnumFacing step : EnumFacing.VALUES) {
                        // The neighbor from which a step in this direction arrives here.
                        RenderInfoAccess from = selected.get(CubeKey.of(x - step.getXOffset(), y - step.getYOffset(),
                            z - step.getZOffset()));
                        if (from == null) {
                            continue;
                        }
                        reached++;
                        boolean directionOk = !occlusion
                            || (from.getQuadratureSetFacing() & 1 << step.getOpposite().ordinal()) == 0;
                        EnumFacing entered = from.getQuadratureFacing();
                        CompiledChunk compiled = from.getQuadratureRenderChunk().getCompiledChunk();
                        boolean sightOk = !occlusion || entered == null
                            || compiled.isVisible(entered.getOpposite(), step);
                        if (!directionOk) {
                            direction++;
                        }
                        if (!sightOk) {
                            sight++;
                        }
                        if (directionOk && sightOk) {
                            open++;
                        }
                        notes.append(' ').append(step.getName()).append(':')
                            .append(directionOk ? "" : "backtrack ").append(sightOk ? "" : "nosight ")
                            .append(compiled == CompiledChunk.DUMMY ? "unbuilt"
                                : compiled.isEmpty() ? "empty" : "built")
                            .append(" entered-").append(entered == null ? "start" : entered.getName());
                    }
                    detail = notes.toString();
                    if (!inFrustum) {
                        reason = "not selected: its box is outside the game's view frustum";
                    } else if (reached == 0) {
                        reason = "not selected: none of its six neighbors was selected either";
                    } else if (open > 0) {
                        reason = "not selected, though a selected neighbor has an open way to it";
                    } else if (sight == reached && direction == 0) {
                        reason = "not selected: every selected neighbor's geometry blocks the line of sight";
                    } else if (direction == reached && sight == 0) {
                        reason = "not selected: reaching it would mean travelling back the way the search came";
                    } else {
                        reason = "not selected: blocked by line of sight from some neighbors and by direction "
                            + "from the others";
                    }
                }
            }
            counts.merge(reason, 1, Integer::sum);
            List<String> list = examples.computeIfAbsent(reason, k -> new ArrayList<>());
            if (list.size() < EXAMPLES) {
                list.add(x + " " + y + " " + z + " (" + (x - ((int) Math.floor(cameraX) >> 4)) + " "
                    + (y - ((int) Math.floor(cameraY) >> 4)) + " " + (z - ((int) Math.floor(cameraZ) >> 4))
                    + " from the camera)" + detail);
            }
        }
        int gameOnly = 0;
        List<String> missed = new ArrayList<>();
        for (Long2ObjectOpenHashMap.Entry<RenderInfoAccess> entry : selected.long2ObjectEntrySet()) {
            CompiledChunk compiled = entry.getValue().getQuadratureRenderChunk().getCompiledChunk();
            if (compiled != CompiledChunk.DUMMY && !compiled.isEmpty() && !drawn.contains(entry.getLongKey())) {
                gameOnly++;
                if (missed.size() < 2 * EXAMPLES) {
                    long key = entry.getLongKey();
                    EnumFacing entered = entry.getValue().getQuadratureFacing();
                    missed.add(CubeKey.x(key) + " " + CubeKey.y(key) + " " + CubeKey.z(key) + " ("
                        + (CubeKey.x(key) - ((int) Math.floor(cameraX) >> 4)) + " "
                        + (CubeKey.y(key) - ((int) Math.floor(cameraY) >> 4)) + " "
                        + (CubeKey.z(key) - ((int) Math.floor(cameraZ) >> 4)) + " from the camera): "
                        + explain.apply(key) + "; the game entered it from "
                        + (entered == null ? "the start" : entered.getName()));
                }
            }
        }
        StringBuilder out = new StringBuilder(String.format("Selection comparison: Quadrature draws %d cubes, the "
                + "game selected %d and would draw %d; both %d, game only %d, occlusion search %s.", drawn.size(),
            selected.size(), gameDraws, both, gameOnly, occlusion ? "on" : "off"));
        counts.forEach((reason, count) -> {
            out.append(String.format("%n  %6d  %s", count, reason));
            for (String example : examples.get(reason)) {
                out.append(String.format("%n            %s", example));
            }
        });
        for (String cube : missed) {
            out.append(String.format("%n  game only  %s", cube));
        }
        Quadrature.LOGGER.info(out.toString());
    }
}
