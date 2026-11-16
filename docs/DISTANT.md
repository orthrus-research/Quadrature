# Distant terrain

Status: the part with no graphics in it is built and tested, in `core`
(`cc.orthrus.quadrature.core.lod`): the run tile, the run mesher, the face record, the ring
selection, the schedule and the draw list. The first frames are written and compile, in
`cc.orthrus.quadrature.distant` (section 8): sampling, materials, an arena and the draw.
They have run four times in a game (sections 9 to 12): tiles are sampled on Cubature's client
preview pool, stored and drawn in the right places; land shows beyond near terrain to the
horizon under one fog; distant land begins where near land ends with no band of sky; near and
distant water meet at one level; and on land the near box does not stand open. **Untested
still:** the near sea bed under the box, the pool's threads when a world is left, entities
between the game's old fog distances, and the hand. The plain flaws left are looks: distant
water is flat and too bright, distant crowns are dark solid blocks, and textured near terrain
changes abruptly to flat colors. Section 13 is the plan for the next run.
Section 7 of
[ARCHITECTURE.md](ARCHITECTURE.md) states the structure this fills in.

## 1. What a tile is

Distant terrain is columns of vertical runs at every level. A tile is 64 by 64 columns; at
level k a column is a cell `1 << k` blocks wide, and height stays at one block, so a cliff
or a shoreline keeps its exact level however coarse the cell. A run is a top, a material
index and a kind (air, opaque, leaves, water, detail); a column's runs are contiguous from
the tile's floor to its ceiling. Runs are what Riven's terrain preview emits, so a tile is
filled from the sampler with no conversion, and any volume can be written this way,
overhangs included.

`RunTile` holds a tile with a ring of one more column all round, 66 by 66. The apron makes
no face; it tells the mesher what stands beside the tile. Each column also carries a tint
value for its biome, which the tile does not interpret.

**Buried strata.** `collapseBuried` joins the runs of a column that end at or below the
lowest space in it and in the four columns beside it. No face can stand on them, so the mesh
is unchanged, record for record. Measured on Riven's sampler, a column goes from 13 to 27
runs to 2.7 to 4.6 (section 5), which is what makes keeping tiles for re-meshing affordable.

## 2. Faces

`RunMesher` turns a tile into faces. Space is a stretch of a column with no opaque run.

| Rule | |
|---|---|
| Opaque tops and bottoms | Where the run above or below is space |
| Opaque sides | Against each of the four columns beside: one face for each stretch of that column's space that an unbroken stretch of opaque runs overlaps, whatever materials it crosses. The face carries the material at its top |
| Leaves | The same, run by run; no face against opaque runs or other leaves |
| Water | Top faces only, in their own layer. The bed is meshed as opaque |
| Detail | Plants and snow layers are space and make no face |
| Merged tops | Top faces of one layer, material, height, sky light and occlusion join into rectangles, along the axes on which their corners' occlusion agrees |
| Closed tile | On the four outer sides, what the neighbor hides of each stretch of opaque runs becomes skirt faces, one a hidden piece, so the sides are covered from floor to surface |
| Exposure | Off unless asked for: space counts only if reachable from the tile's top or sides. For tiles built from stored cubes, where sealed caves exist; generated terrain's sampler already fills what the sky cannot reach |
| Sky light | 15, less one for each block of leaves and three for each of water above, none under an opaque run; a side face takes it at its top |
| Occlusion | Two bits a corner of a top or side face: the opaque cells that close the corner in, the two beside it and the one diagonal, 3 when both sides are closed |

A side face is one quad however tall, which is why one-block height costs nothing. A closed
tile needs no stitching: two tiles of any two levels, or a tile beside one that has not
arrived, cannot show a gap, because each is covered on its own sides.

**Layers and groups.** A mesh has four layers (opaque, cutout for leaves, water, skirt) and
each layer six groups by direction. A view that cannot see a direction skips the group; the
skirt is its own layer so that a renderer can leave it out where both neighbors of a level
are present.

**The record.** A face is 64 bits (`FaceRecord`), to be expanded to a quad in the vertex
stage:

| Field | Bits | |
|---|---|---|
| x, z | 6 + 6 | The column within the tile |
| y | 12 | Blocks above the tile's floor; a tile is at most 4,096 blocks tall |
| extent | 12 | A side face: height less one. A top or bottom face: width less one and depth less one, 6 + 6 |
| direction | 3 | |
| material | 13 | |
| sky light | 4 | |
| occlusion | 8 | Two bits a corner |

`TileMesh` holds a tile's records by group and writes them, group after group, into a
buffer the caller owns, in the machine's byte order, with each group's offset and count, the
lowest and highest level any face reaches, and the 64 by 64 tints. Meshing allocates nothing
for a face; the mesher and the mesh are reused from tile to tile and grow when they must.

## 3. Rings

`Rings` selects the tiles a camera wants. Ring k is the square of level-k tiles within
`reach << k` blocks of the camera, less the square of the ring below. Every square is moved
to even tile positions, so the hole in a ring is a whole number of its tiles and a tile has
all four of its children in the ring below or none. Each level then holds the same number of
tiles: with a reach of 384 blocks, 144 at level 0 and 108 at each level above, 900 for eight
levels. Reaches of 256 and 512 give 64 and 256 a square.

Tiles outside the world's bounds are left out. The selection lists the coarsest level first
and, within a level, the nearest tile first. `coarsestNeeded` is the lowest level whose
square holds the whole world; no tile above it is selected. An 8 km world seen from
(0, -2560) is 478 tiles in levels 4 to 0; a 32 km world seen from (10240, -8192) is 664 in
levels 6 to 0.

`TileKey` packs a tile's level and position into a long and gives its parent, its four
children and whether one tile lies under another.

## 4. What is not built

- Crossfade between levels, the coverage map that hands columns to near terrain, the light's
  view of distant tiles, a stage of its own in the frame graph.
- Images beyond level 0 and below a side face's top block; rotated and mirrored images. Leaves are drawn solid.
- Water's side faces: a water tile beside lower ground shows no wall of water.
- Detail runs tinting the top under them.
- The overlay of edited terrain, and with it any use of the exposure rule.
- Where the world ends: the terrain preview does not say, so a bound is a setting.
- A coarse level kept for the whole world. Rings alone are selected; ground whose tile has
  not arrived is empty.

## 5. Measurements

Riven's sampler at seed 8675309, layout scale 1, the two windows of the size survey (a
forest around (3584, 7168) and mountains around (0, -2560)), every tile sampled once and
given to both the survey's mesher and this one. Faces include the skirt. "Survey rules" is
the survey's count under the same rules except occlusion, which it did not compute: its
merged tops, its sides with material ignored, its skirt. Mesh time is one thread's best of
five warm passes with the write into a native buffer, while seven other threads sampled.

| Window | Level | Tiles | Faces a column | Survey rules | Difference | KiB a tile | Runs a column, as sampled to collapsed | Mesh ms, p50 / p95 |
|---|---|---|---|---|---|---|---|---|
| forest | 5 | 16 | 2.54 | 2.37 | +7% | 81 | 18.4 to 4.6 | 2.8 / 4.6 |
| forest | 4 | 16 | 3.06 | 2.87 | +7% | 98 | 19.4 to 4.6 | 2.5 / 5.7 |
| forest | 3 | 16 | 3.98 | 3.69 | +8% | 127 | 27.0 to 4.5 | 4.1 / 5.9 |
| forest | 2 | 64 | 3.09 | 2.76 | +12% | 99 | 27.0 to 4.1 | 3.2 / 5.4 |
| forest | 1 | 256 | 4.28 | 4.02 | +6% | 137 | 27.3 to 4.0 | 3.5 / 11.1 |
| forest | 0 | 1,024 | 3.50 | 3.28 | +7% | 112 | 27.3 to 3.8 | 3.5 / 15.0 |
| mountain | 5 | 16 | 2.61 | 2.43 | +8% | 83 | 13.4 to 4.5 | 2.9 / 3.7 |
| mountain | 4 | 16 | 2.70 | 2.53 | +7% | 86 | 16.9 to 4.1 | 3.5 / 5.2 |
| mountain | 3 | 16 | 2.52 | 2.35 | +7% | 81 | 16.1 to 3.7 | 2.4 / 3.6 |
| mountain | 2 | 64 | 2.21 | 1.98 | +12% | 71 | 16.1 to 3.3 | 1.8 / 3.5 |
| mountain | 1 | 256 | 1.94 | 1.66 | +17% | 62 | 16.1 to 3.0 | 1.6 / 3.2 |
| mountain | 0 | 1,024 | 1.44 | 1.14 | +26% | 46 | 16.1 to 2.7 | 1.5 / 2.7 |

- In every row the bottoms, the opaque sides, the leaf sides, the skirt and the runs left
  after collapsing equal the survey's counts exactly.
- The whole difference is top faces, and its cause is occlusion: a top face whose corners
  differ from its neighbors' cannot join them. On open slopes most ground cells have a
  higher neighbor, so at level 0 in the mountains tops rise from 1.16 million to 2.38
  million. Joining along the axis on which corners agree (ground along a straight wall)
  recovered about a tenth of that; without it the mountain level 0 figure was +32%.
- Whether tops keep their occlusion is open (section 6).

## 6. Open

- **Occlusion on tops.** It costs 6 to 26% more faces. The alternatives are to leave it off
  tops at coarse levels, where a cell is wider than the effect, or to compute it in the
  fragment stage from the tile's heights. To be decided against frames.
- **A side face's light and occlusion** are taken at its ends; a tall face under a canopy
  is lit as its top is. Splitting a face where light changes would cost faces.
- **The record's bits** are as the design proposed; the sprite texture array and the
  material table they index are near terrain's to define.

## 7. Provenance

Records 6 to 10 below belong to the first frames (section 8).

Records for [PROVENANCE.md](PROVENANCE.md), kept here until this branch joins the one that
owns that file. No renderer's or level-of-detail mod's source was read for any of it.

**Open before this branch joins:** the published sources named below (Lysenko's two
articles, Losasso and Hoppe's paper) were cited from memory of them and were not read again
while this code was written. Each must be read and its record checked against it, or
corrected, before the records move into PROVENANCE.md; until then they are unverified.

### Run tiles
- Code: `core/src/main/java/cc/orthrus/quadrature/core/lod/RunTile.java`
- Source: original. Facts used: Cubature's terrain preview interface, which gives a column
  as contiguous vertical runs; `docs/research/R3-levels-of-detail.md` on column runs as a
  representation; Quadrature's own size survey of Riven's sampler (runs per column, and how
  many no face can touch).
- Taken: nothing beyond the interface's shape.
- Added: 2026-11-15

### Run mesher
- Code: `core/src/main/java/cc/orthrus/quadrature/core/lod/RunMesher.java`, `TileMesh.java`
- Source: original, from the distant terrain design and the size survey. Joining coplanar
  faces into rectangles is widely described, for example Lysenko, "Meshing in a Minecraft
  Game", 0fps, 2012. Skirts that hide the cracks between terrain levels are common in
  terrain rendering writing.
- Taken: the ideas. Faces come from the overlap of one column's solid stretches with its
  neighbor's stretches of space; tops are joined row by row from bit masks; the skirt is
  the part of a tile's outer sides that its neighbor hides, so a tile closes itself.
- Not taken: any mesher's source.
- Added: 2026-11-15

### Corner occlusion
- Code: `RunMesher.java` (`corner`, `topOcclusion`, `sideOcclusion`)
- Source: Lysenko, "Ambient occlusion for Minecraft-like worlds", 0fps, 2013.
- Taken: the rule for a corner from the three cells that touch it: both sides closed is
  fully closed, otherwise the count of closed cells.
- Not taken: code. Applying it to faces many blocks tall, at their two ends, and joining
  occluded faces only along the axis on which their corners agree, are original.
- Added: 2026-11-15

### Face record
- Code: `core/src/main/java/cc/orthrus/quadrature/core/lod/FaceRecord.java`
- Source: original. Facts used: the survey's measurements (12 bits of height suffice for
  Riven's world, and fewer than 400 block states appear in a window).
- Added: 2026-11-15

### Rings of tiles
- Code: `core/src/main/java/cc/orthrus/quadrature/core/lod/Rings.java`, `TileKey.java`
- Source: Losasso and Hoppe, "Geometry Clipmaps: Terrain Rendering Using Nested Regular
  Grids", SIGGRAPH 2004.
- Taken: the idea. Levels nested round the viewer, each twice the extent of the one inside
  it at half the resolution, moved in steps of two cells so that levels stay aligned.
- Not taken: the heightfield, the geometry and the blending of that paper. Tiles of run
  columns, squares with the finer square cut out, and clipping to a world's bounds are
  original.
- Added: 2026-11-15

### Vertex stage that builds faces from records
- Code: `src/main/java/cc/orthrus/quadrature/distant/DistantProgram.java`,
  `core/src/main/java/cc/orthrus/quadrature/core/lod/TileDraws.java`
- Source: the OpenGL 4.6 compatibility specification (shader storage blocks and std430
  layout, `gl_VertexID` and `gl_BaseInstance`, indirect multi-draw of arrays). Building
  vertices in the vertex stage from a buffer the stage indexes itself is widely described as
  programmable vertex pulling, for example Rakos, "Programmable Vertex Pulling", OpenGL
  Insights, 2012.
- Taken: the idea. The vertex number names the face and its corner; the draw's base instance
  names the tile, as near terrain's names a cube.
- Not taken: code. Unverified until the cited chapter is read again.
- Added: 2026-11-15

### One far distance for the whole world pass
- Code: `src/main/java/cc/orthrus/quadrature/mixin/MixinEntityRendererDistant.java`,
  `DistantHook.java`
- Source: original. Facts used: the game's world pass, read from its public classes: the
  four methods that build a projection and the profiler section that follows the opaque
  terrain layers; the OpenGL specification's mapping from clip depth to window depth.
- Taken: nothing. Two projections with different far distances map one distance to two
  depths; a single far distance for every projection of the pass avoids it.
- Added: 2026-11-15

### One fog for near and distant terrain, the near box and the debug views
- Code: `src/main/java/cc/orthrus/quadrature/mixin/MixinEntityRendererFog.java`,
  `DistantHook.java` (`fog`), `DistantSettings.java`, `DistantProgram.java`,
  `core/src/main/java/cc/orthrus/quadrature/core/lod/NearCover.java`
- Source: original. Facts used: the game's world pass, read from its public classes: where it
  sets its fog, with which distances, and which call is the sky's; the level at which the game
  draws a still water surface; the OpenGL compatibility specification's fog state.
- Taken: nothing. The fog formula is near terrain's own program's.
- Added: 2026-11-16

### Flat material colors and biome tints
- Code: `src/main/java/cc/orthrus/quadrature/distant/DistantMaterials.java`,
  `core/src/main/java/cc/orthrus/quadrature/core/lod/MaterialTable.java`
- Source: original. Facts used: the game's public interfaces for a block's baked model, its
  quads' images and tint indices, its block color handlers and a biome's colors.
- Added: 2026-11-15

### Tile schedule, distant arena and timing
- Code: `core/src/main/java/cc/orthrus/quadrature/core/lod/TileSchedule.java`,
  `TileCulling.java`, `BuiltTile.java`, and `src/main/java/cc/orthrus/quadrature/distant/`
  (`Distant.java`, `DistantWorkers.java`, `DistantArena.java`)
- Source: original. The arena is near terrain's (its record above) with other sizes. Facts
  used: Cubature's cube source and terrain preview interfaces; the OpenGL 4.6 compatibility
  specification (timestamp queries, sync objects, buffer storage).
- Added: 2026-11-15

## 8. First frames

Off unless `-Dquadrature.distant=own`, which needs `-Dquadrature.terrain=own`.

| Setting | Default | |
|---|---|---|
| `quadrature.distant.reach` | 384 | How far ring 0 reaches, in blocks; a multiple of 128 |
| `quadrature.distant.levels` | 7 | Levels 0 to 6, which reach 24.5 km |
| `quadrature.distant.world` | none | Half the side of the world's square in blocks; no tile is built outside it |
| `quadrature.distant.threads` | 2 | The fallback pool's threads, 1 to 8. No effect while Cubature's pool is used, whose size is Cubature's `clientPreviewThreads` |
| `quadrature.distant.fog` | `own` | `own`: one fog for near and distant terrain. `game`: the game's fog on near terrain and none on distant terrain, as the first two runs drew |
| `quadrature.distant.fogStart` | the reach (384) | Where the shared fog begins, in blocks from the eye |
| `quadrature.distant.fogEnd` | the outermost ring's reach (24,576) | Where the shared fog is complete |
| `quadrature.distant.debug` | none | `box` or `level` (see Debug views) |
| `quadrature.distant.detail` | `images` | `images`: level 0's faces show their materials' images near the join. `flat`: flat colors everywhere |
| `quadrature.distant.detailStart` | 224 | Up to this distance from the eye, in blocks, a level 0 face shows its image whole |
| `quadrature.distant.detailEnd` | 352 | From this distance on, flat color only |

**Source.** The terrain preview comes from Cubature's cube source (`terrainPreview()`). It is
looked for once a frame: the source has one listener, which near terrain holds, so its
changed event is not used. A new world, or a preview that came or went, lets every tile go
and refuses, by a generation number, whatever is still being built. A withdrawn preview's
exception ends a task quietly.

**Building.** A task samples a tile's 66 by 66 columns, each at its cell's middle with the
cell's width as its footprint, joins the buried strata, meshes, and leaves faces and tints in
one buffer on a queue. The client thread stores finished tiles for at most a millisecond a
frame. `TileSchedule` hands tiles out in the selection's order, coarsest level first and
nearest first, at most two for each thread at a time. A tile that leaves every ring is
dropped if it was waiting, told to stop at its next row if it is being sampled, and refused
if it arrives. A stored tile that leaves the rings is kept, and drawn, until the tiles of
the selection over the same ground are stored, so ground does not turn empty while its
replacement is built; for that long two levels are drawn over one another there.

**Threads.** Quadrature starts no threads for terrain work. Tiles are built on Cubature's
client preview pool (`ICubeSource.previewWork()`, Cubature's docs/CUBE-SOURCE.md, section 8):
Cubature keeps the threads (`clientPreviewThreads`, by default a quarter of the processors,
1 to 4) and a sampler for each, and its governor lets fewer tiles run at once while frames
run longer than `clientPreviewFrameBudgetMs` or the integrated server's ticks run long. A
tile is handed in with its place in the schedule's order as its priority, checks between
rows whether it was cancelled and waits there if the governor has lowered the count. A tile
that leaves every ring has its task cancelled. A task withdrawn because its preview went is
told as abandoned and asked for again if still wanted. Each tile's end is told once, by its
job or by the client thread, whichever comes first.

The fallback, for a cube source with no such pool: a pool of distant terrain's own,
`quadrature.distant.threads` threads at the lowest priority, as the first two runs used. A
warning is logged when it is used. The log's lines say which pool builds tiles, its threads
and how many run at once.

Not run in a game on Cubature's pool (section 12).

**Materials.** Each block state met gets an index as tiles are sampled. On the client thread
each index gets two colors, for its top and for its sides: the mean, weighted by alpha, of
the image the block's model shows on that face, with a mark where the biome tints it (grass,
foliage, water). A column's tint is its biome's number, one byte, stored after the tile's
faces; each biome's three tints are a small buffer. A merged top face takes the tint of its
first column.

**Arena.** A second arena, like near terrain's: 64 MiB pages written through the driver and
never mapped, eight at most (512 MiB), a tile in one block, a freed block reused only after
a fence. A tile the arena has no room for is left out until it leaves the rings and returns.

**Draw.** A tile's page is bound as a shader storage buffer and the vertex stage builds a
face's six vertices from its record: the vertex number names the face and the corner, the
draw's base instance names the tile's record (its place from the view's origin, worked out
in doubles; its cell width; where its tints lie). This was chosen over integer vertex
attributes with an instance a face because it leaves the base instance for the tile, needs
no vertex layout, and makes a page's layer one indirect draw of plain ranges. There are three
draw layers: opaque with the skirt, leaves (solid), and water, blended over the others.

A tile is not drawn when all of it lies in the box near terrain covers, when the view's
volume misses its bounds, and, group by group, when its faces turn away from the eye. Faces
are not culled by winding.

**Where it is drawn, and depth.** Before the game's first draw of entities in the world
pass: after near terrain's opaque layers, with the matrices the driver holds for the world.
The injection is on that call and not on the profiler section before it, whose argument the
Mixin that reads section names has already rewritten; an injection that looked for the
section's name beside the call found nothing and stopped the first start of the game. Depth state is left as the game set it, so the draw is correct with reversed depth
on or off, and distant fragments behind near terrain are rejected before they are shaded.
Without reversed depth the integer depth buffer cannot separate distant faces (about 30
blocks at 5 km) and they will flicker; a warning is logged.

**The far distance.** While distant terrain is on, the far distance of every projection the
game builds in the world pass is raised to 65,536 blocks: terrain and entities, the sky and
the projection restored after it, the clouds, the hand. One far distance for all of them is
what keeps depth consistent: with the game's own, about 543 blocks at a view distance of 24
chunks, a second projection for distant terrain would map distance to depth differently, and
distant ground between 300 and 670 blocks would draw in front of near ground at 300. What
this changes for hosted drawing: anything drawn beyond the old far distance is no longer
clipped; the volume the game culls chunks and entities against reaches farther, though its
own distance limits still apply; depth precision near the eye is unchanged, since the near
distance sets it.

**Fog.** The game fogs the world between three quarters of its view distance and the whole of
it. Left alone, that hides near terrain's last cubes in front of clear distant land (section
10). With `quadrature.distant.fog=own`, the default, near and distant terrain take one fog:

- Which calls. `EntityRenderer.setupFog` sets the game's fog several times a world pass. After
  each call that is not the sky's (`startCoords` -1) and not made inside `renderCloudsCheck`,
  `MixinEntityRendererFog` hands the game's view distance to `DistantHook.fog`. If the fog the
  game has just set is its ordinary one, linear and complete at the view distance, its start
  and end are replaced through `GlStateManager.setFogStart` and `setFogEnd` by
  `quadrature.distant.fogStart` and `fogEnd`. The color, the mode and the density are the
  game's.
- Under which switch. Only with `-Dquadrature.distant=own`, `quadrature.distant.fog` not
  `game`, the debug view not `level`, and while distant terrain has a preview to draw from. In a
  world with no preview, or before the first world pass has found one, the game's fog stands.
- What is left alone. The sky's fog, which shades the dome toward the horizon; the clouds' fog;
  and every fog that is not the ordinary one: under water or lava, in blindness, inside a cloud,
  or one another mod has set to other distances.
- What it changes for hosted drawing. Everything the game draws in the world between those
  calls is fogged by the new distances: near terrain's layers, entities and block entities, the
  block outline, particles, rain and snow, and whatever other mods draw there. With the
  defaults nothing within 384 blocks is fogged at all, so entities and particles at the edge of
  the view distance no longer fade. The sky and the clouds look as they did. Clouds still fade
  at the game's view distance, which will now look early beside clear terrain.
- Distant terrain's program applies the fog the fixed-function state holds when it draws, by
  distance from the eye, with the code near terrain's program uses. It draws between two of the
  calls above, so it reads the same distances and color near terrain did.
- Until the first tiles arrive, near terrain ends on a clear edge against the sky.

The defaults follow the rings: fog begins where ring 0 ends, so everything at full detail is
clear, and is complete at the outermost ring's reach, so the last tiles fade into the horizon's
color and do not end on an edge. Both are untried in a game.

**Water's surface.** The game draws still water a ninth of a block below its block's top. A
distant water face at the block's top stood that much higher than near water, and through the
slot between them at the box's edge the unlit bed beyond showed: a third of a pixel at 190
blocks, which the second run drew as a row of dark dashes along the join (section 10). The
water layer's top faces are now lowered by `NearCover.WATER_SURFACE_DROP`.

**Images near the join.** Near terrain's images used to end on a line, with flat colors
beyond. A face of level 0 now shows its material's image out to `detailStart` and fades into
its flat color by `detailEnd`. The flat color is the image's mean, so nothing changes where
the fade ends, and it ends inside ring 0's reach, so no other level needs images.

- The images are the layers of one array texture, 16 texels a side with every smaller copy,
  repeated a block apart, nearest texel when magnified. Two layers a material, as its colors
  are: its top and its side, each the first frame of the image the game's model shows on that
  face, resampled to 16 texels by nearest texel, with the block's own fixed color multiplied
  in and every texel made opaque (a clear texel black). 2,048 layers, or as many as the driver
  has, hold 1,024 materials in under 3 MiB; a material past that draws its flat color.
- A face finds its texel from its place within its tile, whose corner is a block's corner: a
  top or bottom face by its two horizontal coordinates, a side face by its horizontal one and
  its height. Images are not rotated or mirrored as some of the game's models are.
- A side face carries the material at its top, whatever it crosses below (section 2). Only
  its top block shows that image; below it the face is its flat color, or a cliff would
  repeat its turf down its whole height. So a cliff near the join has one row of image over
  flat color, and what it is made of below its top still does not show.
- Water has no image.
- The biome's tint multiplies the image as it multiplies the flat color.
- In the fragment stage the surface (image or flat color, with tint), its light (sky light,
  the day, occlusion, the shade of its direction) and the fog are worked out one after
  another and kept apart, so that a stage that lights the frame later can take the surface
  and its direction and light them by its own means.

Cost: one texture look-up for fragments of level 0 within `detailEnd`. Not seen in a game.

**Leaves.** Drawn solid, in the color the game's own far leaves have. Measured in the fourth
run from above, near crowns were (44, 50, 31) to (49, 58, 38) and distant crowns (65, 75, 47):
distant crowns were the lighter, near ones 0.68 as bright. The cause is how the game makes
the smaller copies of an image: it averages light, not stored values, counts a clear texel as
black, and its alpha test passes what is left. From some tens of blocks out a near leaf face
is therefore solid and darker than the leaf. For Riven's umbrella acacia, whose image is 34
to 41% leaf, that mean comes to 0.65 to 0.71 of the leaf's color, which is the ratio
measured; mesquite 0.58, baobab and mahogany 0.81 to 0.93. A distant leaf's material now takes
that color (`MaterialTable.seenFromAfar`, the same mean), where it took the color of the leaf
texels alone.

There is no cut-out. Distant terrain begins about 180 blocks out, where a texel of the image
is a third of a pixel or less; a cut-out that small is a pattern at the scale of a pixel,
which crawls as the camera moves, or needs a multisampled frame, which the game's is not; and
the game's own leaves are solid there. What makes a far tree line read dark against the sky
is that it is solid and little fogged at a kilometer, not that its color is wrong: a tree
line was (46, 55, 37) from the ground, the color of a near crown. More haze would be a change
to the fog's two distances. Not seen in a game.

**Water's color.** Distant water is the last layer and is blended over the distant sea bed by
the alpha of the game's water image, which its material carries as cover (the mean alpha, in
the top six bits of the material's entry). The game draws near water so. Drawn opaque, as in
the first four runs, distant water was the image's own color, (47, 68, 244), where near water
over its dark bed was (36, 51, 173); that ratio is the image's alpha, about 0.71. The biome's
water tint and the shared fog apply as before; there are no waves and no reflection. Opaque
layers are drawn with blending off and water with the game's usual blend, and the blend state
is put back as found. Not seen in a game.

**Debug views.** `-Dquadrature.distant.debug=box` shows where the near box stands open. After
the ordinary draw, which leaves out what the box holds, the faces the box holds are drawn
again in magenta, each moved straight away from the eye by a hundredth of its distance. They
keep their place on the screen and lose to anything at their own depth or nearer, so a pixel
is magenta only where distant terrain has ground in the box and near terrain drew nothing in
front: a cube not yet meshed, a hole in near terrain, or a box larger than what near terrain
meshes. Near terrain's cut-out leaves let it through between their texels, where distant
terrain's solid crowns stand behind, which is not a fault. Distant water is left out of the
second draw: its faces lie over the sea bed, and near water, which writes no depth, cannot
hide them (the fourth run's water read purple all over for that reason). Without them, near
water with no near sea bed behind it shows as magenta seen through water, which is a fault. The
tiles the box holds whole are drawn for this view only, and the log says how many.

The first box view (section 11) drew those faces at their own depth. Distant ground lies in
the plane of near ground, so depth tied and magenta covered near terrain almost everywhere.

`=level` gives every face a color for its tile's level (0 red, 1 orange, 2 yellow, 3 green, 4
cyan, 5 blue, 6 violet), with no fog on distant terrain and the game's fog on near terrain.

**What near terrain covers.** Fragments inside a box round the camera are discarded: the
loaded cubes' reach on each axis less one cube, since near terrain meshes a cube only among
loaded neighbors and so never the outermost. It is a box, not a column: above and below the
vertical reach distant terrain shows. `NearCover` holds the rule, and a test checks it against
near terrain's (a cube is meshed when its six neighbors are loaded) over whole neighborhoods.

**Light.** Sky light and corner occlusion from the record, the day's brightness, and a fixed
shade for each direction. A corner is darkened by 0.08 for each of the up to three
cells that close it in. The game's own figure, about a fifth, drew a dark line along every
one-block step of the ground in the first frame.

**What this simple rule will show.**
- A change at the box's edge from near terrain's lighting to distant terrain's simpler light.
- Gaps where a cube inside the box is not yet loaded or meshed, and at the box's edge if
  the cube arithmetic is off by one from what near terrain meshes.
- Empty ground wherever a tile has not arrived: on joining, most of the view for the first
  minute or more in a 32 km world.
- No block light, no shadows, no waves or reflection on water, leaves as solid blocks.

**Log.** With `-Dquadrature.frameLog=true`, every five seconds: tiles wanted and stored by
level, being built, kept past the rings, resident; tiles, faces and draws of the last frame;
GPU time from timestamp queries and the client thread's time; tiles built, sampled and
meshed milliseconds a tile, stored, abandoned, failed, refused, let go; the arena's pages
and bytes; materials. The GPU time is distant terrain's own, from timestamps; the frame
log's stage times count the draw under "entities", since a stage of its own would need the
frame graph changed. With `-Dquadrature.glDebug=true` the driver's messages are logged as near terrain's
are, marked "during distant terrain".

## 9. The first run, 2026-11-16

One turn under the lease, in a 32 km world (seed 2485331, layout scale 4), dedicated server
and client, reversed depth on, RTX 3060, 1280 by 720.

**What happened.** The first start stopped while the game loaded `EntityRenderer`: the
injection that draws distant terrain found no target (section 8, where it is drawn). With
that corrected the game ran. The tour was meant to capture three views and captured one: its
second view stood where the first did, and Riven's launcher does not capture a view closer
than 9 blocks to the last, so it waited out its 15 minutes. There was no reference run.

**What the run showed**, from a camera 340 blocks up looking down at 45 degrees:

| | |
|---|---|
| The join | Riven's factory built the preview on the client from the server's message; identities agreed |
| Tiles | 642 wanted in levels 0 to 6 (144, 108, 108, 108, 84, 66, 24), all stored, none failed, none refused |
| Time from the first request, two threads, nothing built before | First tiles about 30 s; the coarsest level complete about 62 s; everything about 130 s |
| A tile | 401 ms to sample, 1.0 ms to mesh; storing took at most 0.13 ms of a frame |
| Drawn | 272 to 335 tiles, 1.41 to 1.61 million faces, 3 draws |
| GPU time | 2.2 to 2.6 ms mean, 3.1 ms at most |
| Client thread | 0.11 to 0.15 ms a frame |
| Arena | One page, 31.4 MiB of 64 |
| Materials | 281 |
| Driver | No error. One warning, since removed: the biome tints' usage hint |

**The frame.** The ground is in the right place and whole: two rivers with their banks, the
steps of the ground, single trees, a braided channel with its bars, and no gap or misplaced
face where tiles of different levels meet. Colors are flat and strong: water an unbroken
blue, red sand a vivid orange. Occlusion drew a dark line along every contour, which the
softer step answers. A dark disc about 100 blocks in radius is real: a sabkha, a salt-flat
biome whose top block is mud, round as Riven places it, at (-4317, 12851); sampling the
preview there offline gives mud on red sand.

**What it did not test.** At that height the ground lies below the loaded cubes' vertical
reach, so near terrain had no mesh and the whole frame was distant terrain. The box near
terrain covers, depth against near terrain and the far distance against the game's own
drawing were not exercised, and no view showed the horizon.

## 10. The second run, 2026-11-16

One turn under the lease, two tours of four views on one fresh instance
(`quadrature-distant-03`, the 32 km world of section 9), a reference with distant terrain off
and a run with it on, reversed depth on, the game's fog as it was. Three views stood where near
terrain is meshed under the camera; the tour is `tools/tours/distant-2485331.json`.

| | |
|---|---|
| Both runs | Started, captured 4 of 4 views and stopped; no error in either log |
| The preview | Built by Riven's factory from the server's definitions |
| Tiles | Stored equal to wanted at every capture (658, 658, 642, 676); failed 0, refused 0 |
| Arrival, nothing built before | First tiles about 27 s after sampling began, level 6 complete about 75 s, all 658 about 155 s; after a move, 15 to 45 s |
| Drawn | 240 to 335 tiles, 1.14 to 1.61 million faces, 3 draws |
| GPU time | 2.1 to 2.6 ms mean, 2.7 ms at most |
| A tile | 292 to 455 ms to sample, 0.93 to 1.09 ms to mesh |
| Arena | One page, 31.4 to 35.7 MiB |
| Driver, during distant terrain | Notifications only (buffer placement) |

**What it showed.** Land where the reference has sky: a range beyond the savanna, a plain with
its river and a far sea, the far shore of a strait. Nothing distant drew over near terrain.
Where near terrain is drawn the two runs agree pixel for pixel in two views (no pixel differs
below the horizon band); in the third, near water differs only as its animated image does. The
sky is identical in two views and differs by at most 4 of 255 in one, unexplained. The softer
occlusion reads as fine contour lines.

**What it did not settle.** Whether a band of sky stands between near and distant terrain. At a
view distance of 12 chunks the game's fog is complete where the box ends, and fog is the
horizon's color, so near terrain in full fog and empty sky look alike. The band of fog between
near and distant terrain in `above_west` is the same in the reference and has fogged trees in
it, so most of it is near terrain. The shared fog and the box view (section 8) are for this.

**The dashed row on the water.** In `coast_strait` a one-pixel row of evenly spaced dark dashes
ran along the join. Worked out afterwards, without a game: the box is where near terrain's
meshes end (`NearCover` and its test), so there is no missing block. The cause is height. Near
water is drawn a ninth of a block below distant water, and a sight line passing just over near
water's edge runs under the distant water face to the unlit sea bed. The slot is a third of a
pixel tall at that distance, which a slightly sloping edge samples as dashes. Distant water is
now drawn at the game's level (section 8). Not yet seen in a game.

**Two shapes in `high_down`**, sampled from the preview at full detail under the frame:

- The straight brown strip is Riven's. It is a slot about 8 blocks wide and 280 long, open
  from the surface at 81 down to between -20 and -116, straight to within a few blocks: a joint
  slot of the broken basement, or a gallery where a dike weathered out, that reaches the
  surface. The preview gives it at one-block cells, so it is not made by a level, a tile edge
  or a tint. Whether generated cubes hold the same open slot was not checked.
- The straight boundary between savanna and desert, with its rounded corner, is Riven's biome
  boundary: `riven:savanna` against `riven:hot_desert`, grass against red sand, with the same
  straight runs and rounded turns at one-block cells.

**Also seen, not distant terrain's:** lighter patches of water at near terrain's outer edge in
both runs, where near water has nothing opaque drawn behind it.

## 11. The third run, 2026-11-16

One turn under the lease, two starts. The build is tagged `lod-turn-3`: Cubature 802bab6d,
distant terrain's own two threads, the shared fog, the lowered water surface and the first
box view. The tour is `tools/tours/distant-join-2485331.json`.

**The first start stopped** while the game loaded `EntityRenderer`: "@Shadow field
farPlaneDistance was not located in the target class". The fog's Mixin shadowed a private
field, and this build does not carry a shadowed member's name into the released jar. The
offline check had passed, since it reads development names, where the field resolves. The fog
now reads the view distance from the game's settings, the same number.

**Rule: no Mixin of this mod shadows a game member.** Use an accessor, whose name the build
does record, or the game's public state. `tools/mixin_check.py` refuses a Mixin with a
`@Shadow`, because it cannot see this fault otherwise.

**The second start** ran the box view on a fresh instance (`quadrature-distant-06`: that build
with the correction, branch `fix/lod-turn-3-fog`) and captured 4 of 4 views with no error.

| | |
|---|---|
| Fog | The log: the world's fog runs from 384 to 24,576 blocks for near and distant terrain alike, in place of the game's 144 to 192 |
| Tiles | Stored equal to wanted at every view (658, 658, 676, 676); failed 0, refused 0 |
| Arrival | First tiles 30 s after sampling began, level 6 complete at 75 s, all 658 at 150 s |
| Drawn | 240 to 332 tiles, 1.14 to 1.59 million faces, 3 draws |
| GPU time | 2.2 to 2.4 ms mean, 3.5 ms at most |
| Driver, during distant terrain | Notifications only |

**What it showed.**

- The shared fog works. Near terrain is clear to its edge; distant terrain fades gently and
  meets the horizon's color without an edge. The sky is unchanged from the second run's
  reference (identical in one view, within 2 of 255 in another).
- In `above_west`, near terrain's plants and images run up to a clean line and distant terrain
  continues the same ground at the same height from there: no band of sky, no band of fog,
  and no strip of solid magenta along the edge, which a box larger than near terrain's
  meshes would have drawn at ground level.
- In `coast_join` the box's corner is a sharp angle in the water where it was expected.

**What it did not show.**

- Whether the box stands open. The box view was wrong: magenta covered most of near terrain
  (116,000 to 535,000 pixels a frame), because distant ground ties with near ground in
  depth, distant crowns are solid where near leaves are cut out, and near water, drawn
  later, loses to distant water at its own level. It marked where the two coincide. Section 8
  describes the corrected view.
- The lowered water surface and the dashed row: under magenta in both water views.
- Clouds and entities under the shared fog: the instance's client has clouds off and no
  animals, and the player is a spectator.
- The normal look: the turn's second tour went to the retry.

**Open: GPU memory.** It peaked at 3,189 MiB against the second run's 2,633. Not explained.
The box view drew the tiles the box holds as well, and its client held a different set of
other programs' memory; the sampler counts the whole GPU, not the game. The next run samples
memory in both of its runs, and its log says how many tiles the box view adds.

## 12. The fourth run, 2026-11-16

One turn under the lease, two tours on a fresh instance (`quadrature-distant-07`): the normal
look, then the box view. Cubature 24f5ad89 on both sides, tiles built on its client preview
pool, the shared fog, the lowered water surface. Clouds were turned on in the client and
animals allowed. The tour is `tools/tours/distant-fourth-2485331.json`.

| | |
|---|---|
| Both runs | Started, captured 4 of 4 views and stopped; no error in either client log |
| Cubature 24f5ad89 with Riven's jar built against 802bab6d | Links and runs: the preview was built by Riven's factory from the server's definitions; no Mixin failed |
| The pool | "tiles are built on Cubature's client preview pool, 4 threads, 2 at once to start"; the governor went to 3 and to 4 and stayed; 120 frames a second at every capture |
| Arrival, nothing built before | First tiles 30 s after sampling began, level 6 complete at 65 to 70 s, all 658 at 110 to 120 s; the third run's two threads took 150 s |
| Tiles | Stored equal to wanted at every view (658, 658, 676, 676); failed 0, refused 0, none left in flight; 514 let go across the moves |
| A tile | 390 to 650 ms to sample with four at once, 1.0 to 1.1 ms to mesh |
| Drawn | 240 to 332 tiles, 1.14 to 1.59 million faces, 3 draws; 6 in the box view |
| GPU time | 2.2 to 2.5 ms mean; the box view's second draw adds 0.3 to 0.7 ms |
| Driver, during distant terrain | Notifications only |

**What it showed.**

- The water join is clean: the row of dark dashes is gone, two stray dark pixels remain, and
  no step of level shows. Near water is (36, 51, 173) and distant water (47, 68, 244) where
  they meet: distant water is flat and much brighter.
- From above, near terrain runs straight into distant terrain, contour lines continuing
  across; the only seam is from images to flat colors.
- Clouds draw as before over clear terrain. Cows stand unfogged in front of distant land and
  are drawn correctly against it.
- The box view on land: magenta only through near crowns' cut-outs (1,518 pixels from above,
  all in crowns), none on the ground and none along the box's edge. On land the box is closed
  and no larger than what near terrain meshes.
- Outside magenta the two runs agree: 0.03% of pixels differ from above, about 1.5% over
  water, which is the water's animation.

**What it did not show.**

- The near sea bed. All near water read purple in the box view, because the second draw
  included distant water's faces (section 8, since corrected).
- The pool's threads when a world is left: the launcher ends the client itself.
- Entities between 144 and 192 blocks, where the game's fog used to lie: the cows stood
  nearer.

**The camera.** In the first tour the first view faced another way than asked, though the
server's teleport was as asked: something turned the camera afterwards, most likely the mouse
over the game's window. The tour now gives the first view's teleport again as one of its
commands, and the second tour's first view was right. A view that does not look like its
neighbor run's is to be checked for this before anything else.

**GPU memory.** 2,745 to 3,136 MiB in the first tour and 2,355 to 2,810 in the second: each
adds 400 to 450 MiB over its own floor, and the floor moved 390 MiB between them. The sampler
counts the whole GPU. The box view added no tile at any capture. The third run's 3,189 MiB is
taken to have been the floor.

## 13. The next run

Not done. To be planned when the first changes to looks are in (water, crowns, images near
the join). It should also:

- leave the world through the game, if the launcher can, and look for threads named
  "Cubature client preview" afterwards;
- place an entity between 144 and 192 blocks from a view;
- run the box view over the sea, where magenta seen through near water now means a missing
  near sea bed.
