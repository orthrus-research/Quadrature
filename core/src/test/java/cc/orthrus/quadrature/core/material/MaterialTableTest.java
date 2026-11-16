package cc.orthrus.quadrature.core.material;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

class MaterialTableTest {

    private static final MaterialKinds KINDS = MaterialKinds.parse(Arrays.asList(
        "# a comment",
        "",
        "block.101= minecraft:flowing_lava minecraft:lava",
        "block.110= minecraft:leaves minecraft:leaves2",
        "block.112= minecraft:double_plant:half=lower",
        "block.113= minecraft:double_plant:half=upper",
        "block.130= minecraft:glass   minecraft:stained_glass",
        "block.140= minecraft:iron_block",
        "block.x= minecraft:broken",
        "not a line"));

    private static MaterialKinds.Properties state(Map<String, String> values) {
        return values::get;
    }

    @Test
    void aListNamesBlocksAndStatesOfBlocks() {
        assertEquals(9, KINDS.names());
        assertEquals(MaterialKinds.LAVA, KINDS.kind("minecraft:lava", state(Map.of())));
        assertEquals(MaterialKinds.LEAVES, KINDS.kind("minecraft:leaves2", state(Map.of("variant", "acacia"))));
        assertEquals(MaterialKinds.GLASS, KINDS.kind("minecraft:stained_glass", state(Map.of())));
        assertEquals(MaterialKinds.TALL_PLANT_LOWER, KINDS.kind("minecraft:double_plant", state(Map.of("half", "lower"))));
        assertEquals(MaterialKinds.TALL_PLANT_UPPER, KINDS.kind("minecraft:double_plant", state(Map.of("half", "upper"))));
        // A state the list does not name, of a block it does.
        assertEquals(MaterialKinds.NONE, KINDS.kind("minecraft:double_plant", state(Map.of("half", "middle"))));
        assertEquals(MaterialKinds.NONE, KINDS.kind("minecraft:stone", state(Map.of())));
        assertTrue(KINDS.names("minecraft:double_plant"));
        assertFalse(KINDS.names("minecraft:broken"));
        assertTrue(MaterialKinds.foliage(MaterialKinds.HANGING_PLANT));
        assertFalse(MaterialKinds.foliage(MaterialKinds.GLASS));
    }

    @Test
    void aStatesValuesFollowFromItsKindAndItsLight() {
        MaterialTable table = new MaterialTable();
        // A state never set has the values of an ordinary block.
        assertEquals(0.85f, table.roughness(500), 0.002f);
        assertEquals(0.04f, table.reflectance(500), 0.002f);
        assertEquals(0f, table.subsurface(500));
        assertEquals(0f, table.emission(500));
        assertFalse(table.conductor(500));

        table.set(1, MaterialKinds.LEAVES, 0);
        assertEquals(0.55f, table.subsurface(1), 0.002f);
        assertTrue(table.foliage(1));
        assertEquals(0.85f, table.roughness(1), 0.002f);
        assertEquals(MaterialKinds.LEAVES, table.kind(1));

        table.set(2, MaterialKinds.GLASS, 0);
        table.set(3, MaterialKinds.ICE, 0);
        assertEquals(0.12f, table.roughness(2), 0.002f);
        assertEquals(0.12f, table.roughness(3), 0.002f);

        table.set(4, MaterialKinds.METAL, 0);
        assertTrue(table.conductor(4));
        assertEquals(0.32f, table.roughness(4), 0.002f);

        table.set(5, MaterialKinds.LAVA, 15);
        assertEquals(0.10f, table.emission(5), 0.003f);
        table.set(6, MaterialKinds.BRIGHT_EMITTER, 15);
        assertEquals(1f, table.emission(6));
        table.set(7, MaterialKinds.NONE, 7);
        assertEquals(7 / 15f, table.emission(7), 0.003f);
        assertEquals(MaterialTable.STATES * 8, table.bytes().length);
        assertEquals(7, table.version());
    }

    @Test
    void theFallbackNeverCallsABlockAMetalAndOnlyTheGamesWaterWater() {
        // A block of the game's iron or anvil material that the list does not name: no kind.
        assertEquals(MaterialKinds.NONE, MaterialKinds.fallback(MaterialKinds.Family.METALLIC, 0));
        // One that gives light has no kind either, and is taken to give none: a machine does not glow.
        assertEquals(MaterialKinds.NONE, MaterialKinds.fallback(MaterialKinds.Family.METALLIC, 15));
        assertEquals(0, MaterialKinds.fallbackLight(MaterialKinds.Family.METALLIC, 15));
        assertEquals(15, MaterialKinds.fallbackLight(MaterialKinds.Family.OTHER, 15));
        for (MaterialKinds.Family family : MaterialKinds.Family.values()) {
            for (int light = 0; light <= 15; light++) {
                assertTrue(MaterialKinds.fallback(family, light) != MaterialKinds.METAL);
            }
        }
        assertEquals(MaterialKinds.WATER, MaterialKinds.fallback(MaterialKinds.Family.WATER_ITSELF, 0));
        // Another fluid of the game's water material has no kind, whatever light it gives.
        assertEquals(MaterialKinds.NONE, MaterialKinds.fallback(MaterialKinds.Family.WATER_LIKE_FLUID, 0));
        assertEquals(MaterialKinds.NONE, MaterialKinds.fallback(MaterialKinds.Family.WATER_LIKE_FLUID, 15));
        assertEquals(MaterialKinds.LAVA, MaterialKinds.fallback(MaterialKinds.Family.LAVA, 15));
        assertEquals(MaterialKinds.PLANT, MaterialKinds.fallback(MaterialKinds.Family.PLANT, 0));
        assertEquals(MaterialKinds.ICE, MaterialKinds.fallback(MaterialKinds.Family.ICE, 0));
        assertEquals(MaterialKinds.DIM_EMITTER, MaterialKinds.fallback(MaterialKinds.Family.OTHER, 7));
        assertEquals(MaterialKinds.NONE, MaterialKinds.fallback(MaterialKinds.Family.OTHER, 0));
    }

    @Test
    void theKindsOfTheSecondRevision() {
        MaterialTable table = new MaterialTable();
        table.set(1, MaterialKinds.STIFF_PLANT, 0);
        table.set(2, MaterialKinds.SUBMERGED_PLANT, 0);
        table.set(3, MaterialKinds.GROUND_COVER, 0);
        table.set(4, MaterialKinds.PLAIN, 14);
        // Stiff and submerged plants are foliage.
        for (int state = 1; state <= 2; state++) {
            assertTrue(table.foliage(state));
            assertEquals(0.55f, table.subsurface(state), 0.005f);
        }
        // Ground cover has the defaults: no subsurface, no foliage.
        assertFalse(table.foliage(3));
        assertEquals(0f, table.subsurface(3), 0f);
        assertEquals(0.85f, table.roughness(3), 0.005f);
        assertEquals(0.04f, table.reflectance(3), 0.005f);
        // A plain state gives no emission whatever light it gives.
        assertEquals(0f, table.emission(4), 0f);
        assertFalse(table.foliage(4));
        assertFalse(table.conductor(4));
        assertEquals(MaterialKinds.PLAIN, table.kind(4));
        MaterialKinds kinds = MaterialKinds.parse(Arrays.asList("block.116= a:mushroom", "block.117= a:kelp",
            "block.118= a:litter", "block.199= a:egg"));
        assertEquals(116, kinds.kind("a:mushroom", property -> null));
        assertEquals(117, kinds.kind("a:kelp", property -> null));
        assertEquals(118, kinds.kind("a:litter", property -> null));
        assertEquals(199, kinds.kind("a:egg", property -> null));
    }

    @Test
    void aNameMayAskForTwoPropertiesAndBothMustMatch() {
        MaterialKinds kinds = MaterialKinds.parse(Arrays.asList(
            "block.121= a:lamp:inverted=false:powered=true a:lamp:inverted=true:powered=false a:broken:powered"));
        assertEquals(2, kinds.names());
        assertEquals(121, kinds.kind("a:lamp", Map.of("inverted", "false", "powered", "true")::get));
        assertEquals(121, kinds.kind("a:lamp", Map.of("inverted", "true", "powered", "false")::get));
        // Unlit: one of the two matches and the other does not.
        assertEquals(MaterialKinds.NONE, kinds.kind("a:lamp", Map.of("inverted", "false", "powered", "false")::get));
        assertEquals(MaterialKinds.NONE, kinds.kind("a:lamp", Map.of("inverted", "true", "powered", "true")::get));
        // A state without one of the properties named does not match.
        assertEquals(MaterialKinds.NONE, kinds.kind("a:lamp", Map.of("powered", "true")::get));
        // A name whose property has no value is passed over.
        assertEquals(MaterialKinds.NONE, kinds.kind("a:broken", property -> "true"));
    }
}