# Distant terrain

Status: the part with no graphics in it is built and tested, in `core`
(`cc.orthrus.quadrature.core.lod`): the run tile, the run mesher, the face record, the ring
selection, the schedule and the draw list. The first frames are written and compile, in
`cc.orthrus.quadrature.distant` (section 8): sampling, materials, an arena and the draw.
They have run six times in a game (sections 9 to 13 and 15): tiles are sampled on Cubature's client
preview pool, stored and drawn in the right places; land shows beyond near terrain to the
horizon under one fog; distant land begins where near land ends with no band of sky; near and
distant water meet at one level and in one color; on land and over the sea the near box does
not stand open; an entity 150 blocks out is drawn in front of distant land; near terrain's
coverage map stands in for the box with no hole; levels fade into each other with no line;
distant crowns have near crowns' color. **Untested still:**
the pool's threads when a world is left, whether images near the join show, a tile arriving
in view, anything in motion, the hand, a short view distance, and the rest of section 14;
the first three steps of section 16.6 are built and have not run in a game.
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
| `quadrature.distant.crossfade` | `on` | `on`: two levels are drawn over a band where they meet and fade into each other, and a tile fades in when it arrives. `off`: each level ends on a line |
| `quadrature.distant.fill` | `on` | `on`: every level also takes the tiles inside its own hole and draws them until finer tiles are stored, so no hole is open while distant terrain arrives (section 18). `off`: each level its ring only |
| `quadrature.distant.order` | `near` | `near`: the coarsest level's fill tiles, then every level from the finest outward. `coarse`: the coarsest level first. Needs fill tiles (sections 18, 19) |
| `quadrature.distant.detail` | `images` | `images`: level 0's faces show their materials' images near the join. `flat`: flat colors everywhere |
| `quadrature.distant.detailStart` | 224 | Up to this distance from the eye, in blocks, a level 0 face shows its image whole |
| `quadrature.distant.detailEnd` | 352 | From this distance on, flat color only |
| `quadrature.distant.travelCapture` | none | Delays in milliseconds, `500,2000,5000,12000`: after every jump of the viewer a frame is saved at each, as `travel-<jump>-<delay>ms.png` in the game's screenshots, with the log's summary beside it (section 17) |

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
  in and every texel made opaque. A clear texel takes the one color that makes the image's
  plain mean equal its color from afar (the leaves' rule below), since the image's smaller
  copies are plain means. In the fifth run clear texels were black: an acacia's image then
  averaged 0.52 to 0.59 of its flat color, and crowns within 352 blocks came out 0.84 as
  bright as near crowns, where they had been 1.45. 2,048 layers, or as many as the driver
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

**With near terrain's coverage map** (`-Dquadrature.coverage=true`, which is near terrain's
switch), the box gives way to what near terrain says it has done. The map holds a state for
every chunk column of its box; distant terrain is left out only over columns whose surface is
meshed, and shows over those not loaded, with no surface known, or still waiting. Near
terrain's outermost ring of columns waits for good, since it lacks a neighbor, so distant
terrain shows there as it did under the box rule.

- After a teleport, or wherever near terrain has not reached, distant terrain stays under
  the ground not yet meshed and gives way column by column as the map changes. There is no
  hole while near terrain catches up, which the box left.
- Heights. A meshed column is left to near terrain over the heights the box spans, and no
  further: above and below the cubes near terrain holds, distant terrain shows as before. The
  map's surface cube is not used to cut finer. Above a column's surface cube everything is
  air near terrain has meshed, so nothing distant is wanted there. Below its surface band,
  near terrain's cubes may still be waiting, at the foot of a cliff or in a pit; distant
  terrain is left out there too. Drawn, it would lie in the plane of near terrain's faces
  once they arrive and flicker against them; left out, a deep pit in a meshed column can
  show the sky until its lower cubes are meshed. The second was chosen as the rarer and the
  shorter.
- A side face is counted to the column of the block it belongs to, not the one it looks at:
  its position is taken a hundredth of a block into its own block.
- The map is a texture of one byte a column, written when the map's generation moves. A tile
  is not drawn at all when every column it touches is meshed and it lies within the box's
  heights (`NearCover.covered`).
- Near terrain works the map out at most once in 20 frames. For that long after a column is
  meshed both terrains draw it, in one plane; for that long after near terrain lets a column
  go at the trailing edge of a move, neither does. Both are near terrain's interval to set.
- Without the switch `TerrainHook.coverage()` is null and the box rule stands. The log says
  which is in use, and the map's counts by state.

Not seen in a game.

**Where images fade** follows what near terrain reaches: the fade never begins within 32
blocks of near terrain's edge and ends at least 32 blocks inside ring 0 (`NearCover.imageFade`).
At the usual view distance that is the 224 and 352 blocks asked for; at 16 chunks and more the
start moves out and the fade is squeezed against ring 0's edge.

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

## 13. The fifth run, 2026-11-16

One turn under the lease, two tours on a fresh instance (`quadrature-distant-08`): the normal
look, then the box view, with blended distant water, far-leaf colors, images near the join and
the box view without water. Cubature 24f5ad89. The tour is
`tools/tours/distant-fifth-2485331.json`.

| | |
|---|---|
| Both runs | Started, captured 4 of 4 views and stopped; no error; the driver took the changed shaders |
| Tiles | Stored equal to wanted at every view (658, 658, 676, 676); failed 0, refused 0, none left in flight |
| The pool | 4 threads; the governor's count ranged 1 to 4 and settled at 4; first tiles 30 s after sampling began, level 6 complete at 80 s, all 658 at 125 s |
| GPU time | 2.0 to 2.4 ms mean: reading an image costs nothing that shows |
| Driver, during distant terrain | Notifications only |

**What it showed.**

- Water. At the join distant water is (37 to 43, 52 to 58, 175 to 182) beside near water at
  (36, 51, 172); it had been (47, 68, 244). From above the strait the box's corner, a bright
  angle in the fourth run, cannot be found. No dashes, no step.
- An ender dragon 150 blocks out, between the 144 and 192 blocks where the game's fog used to
  lie, is drawn in front of the distant range and unfogged.
- The box view over the sea shows no magenta through near water: the near sea bed is there
  under all near water in view, the lighter patches included. Those patches are something in
  near terrain's own drawing of water. On land, as in the fourth run, magenta only through near
  crowns' cut-outs.
- Outside magenta the two runs agree to within 0.4% of pixels.

**What it showed wrong.**

- Crowns. From above, distant crowns were (37, 43, 26) to (41, 47, 29) beside near crowns at
  (44, 50, 31) to (49, 58, 38): 0.84 as bright, where they had been 1.45. The far color is
  right; the leaf images drawn within 352 blocks were too dark, their clear texels being
  black (section 8, since corrected).

**What it did not show.**

- Images near the join. From above, grass and soil average to their flat color at 200 blocks
  and an image cannot be told from it. The seam left in `above_west` is where near terrain's
  plants end. The next tour has a view at ground level 200 blocks from trees.
- The pool's threads when a world is left. The client's threads were listed four times in the
  last second of each tour, and all four fell before the disconnect, with the pool's four
  threads alive as they should be. The launcher ends the client too soon after it stops the
  server for this to be seen from outside.

## 14. Design: transitions and the shading targets

Not built, but for the pattern (14.5). What comes after the first pass on looks: the crossfade between levels and at the
near join, and distant terrain writing the targets near terrain writes for shading. Read for
it, and not changed: near terrain's targets on its own branch (`GBufferTarget` and
`TerrainProgram` there), and the Riven shader's `docs/TARGET.md` and
`renderer-contract.json`, which state what a shading stage needs and are a design, not an
interface either side runs.

### 14.1 Crossfade between levels

**Now.** Ring k is level k's square less level k-1's. Where they meet, one level stops and
the next begins on a line, and when the camera crosses a snapping step that line jumps by two
tiles (128 blocks at level 0, 8 km at level 6) and the ground between changes level at once.
Tiles close themselves with skirts, so nothing gapes; what shows is a change of detail on a
line, and a pop.

**Design.** Over a band on the inside of each line both levels are drawn, and each pixel
keeps one of them by a threshold that runs from all-fine to all-coarse across the band.

- *Selection.* Ring k keeps, besides its own tiles, the ring of level k tiles just inside its
  hole. The hole is a whole number of level k tiles, so this is one more tile inward on each
  side: with a reach of 384, 20 more tiles a level (108 to 128, a fifth more), none at level
  0. `Rings` selects them and marks them as band tiles; a test checks that every column
  inside the outermost square is covered by exactly one level outside bands and by exactly
  two inside.
- *The band.* For level k-1 meeting level k: the last level k tile inside the hole, so its
  width is 64 << k blocks, a sixth of level k-1's reach. It moves with the snapped square,
  not with the eye, so both levels are certain to have tiles under all of it.
- *The threshold.* A tile's record carries its square's inner and outer edges from the view's
  origin (two more floats a record; the record has four bytes spare and grows by one word).
  In the fragment stage `t` runs 0 to 1 across the band by the fragment's distance from the
  edge, measured on the axis it is nearest the edge on. Level k-1 keeps a fragment where a
  pattern value is at or above `t`; level k keeps it where the value is below `t`. One
  pattern and complementary tests: where both levels have a surface at a pixel, exactly one
  is kept.
- *The pattern.* Ordered, 4 by 4, from the pixel's place on the screen. It does not crawl
  over a still frame. Under motion it stays with the screen while the ground moves under it,
  which reads as fine grain in the band, not as a moving edge. When a stage accumulates over
  frames, the pattern is offset by the frame's index, so the band resolves to a true blend;
  the frame's index is an input that stage already needs. A pattern fixed to the ground was
  weighed and set aside for the reason leaves have no cut-out: far out its cells are smaller
  than a pixel.
- *A tile that has not arrived.* The other level is then drawn whole in the band: a tile
  without its partner is told so in its record (the spare `shows` value), and the schedule
  knows which are stored.
- *A tile arriving.* The same value fades a newly stored tile in over a few frames by the same
  pattern, in place of today's appearing at once.
- *Depth.* Both levels write depth. The coarser surface lies up to a cell away from the finer
  one, so in the band the two can cross; the pattern hides that as it hides the change of
  detail.
- *Skirts* in a band are behind the other level's surface and cost fragments only where they
  were already drawn.

**Cost.** A fifth more tiles and faces at levels 1 and up (about 35 MiB to 41 by the fourth
run's figures), no more draws, and a pattern look-up and a compare in fragments of band
tiles. Fragments in a band are shaded twice at most.

**Alternative.** Moving the finer level's vertices toward the coarser surface across the
band (geomorphing). It needs each fine face to know its coarse parent's height, which a run
tile does not hold, and it cannot fade a tree in. Set aside.

**Built** (not seen in a game), with these differences from the design above:

- The band's edges are not in the tile's record. Each level's hole is a uniform, set each
  frame from the camera the rings were last selected for, and the band's width is the level's
  tile. The record's spare word says which of the tile's partners are stored.
- A tile gives pixels up only to a partner that is stored and past its fading in: a coarser
  tile to its four children, a finer tile to the tile over it. So there is no level-wide
  switch: while a level is still arriving, each tile fades against what is there, and what
  is not there leaves the other drawn whole. Nothing depends on a whole level being stored,
  which under travel is rarely so.
- A tile kept past its ring is drawn whole and is no one's partner.
- A newly stored tile comes in over 24 frames by the same pattern.
- The box view draws without the crossfade. The level view draws with it, each level in its
  color, which shows the bands.
- The pattern's frame is 0.

`Rings.overlap`, `Rings.holeStart`, `Rings.holeSide`, `TileSchedule.kept`,
`TileDraws.FINER_STORED` and `COARSER_STORED`; in the mod, `Distant.bands` and
`Distant.partners`, and the fragment stage of `DistantProgram`.

### 14.2 The near join

**Now.** Fragments inside the near box are left out. With images out to 224 blocks the colors
meet, and what is left at the box's edge is that near terrain has plants, cut-out leaves,
block light and smooth lighting and distant terrain has not.

**What a crossfade here needs.** Distant terrain can fade itself in over the last cube inside
the box by the pattern above. Near terrain must fade itself out over the same cube by the
complementary test, or the two are simply both drawn there. That is a change in near
terrain's fragment stage: the box and band as uniforms and the same pattern function. It is
near terrain's to make.

**And the box itself is provisional.** Near terrain is building a coverage map that says,
column by column, what it has meshed. When distant terrain leaves out what the map covers in
place of what the box holds, a cube not yet meshed is filled by distant terrain and not left
open, and the band can follow the map's edge. The order proposed: the coverage map first,
then the fade on both sides against it.

### 14.3 Writing the shading targets

**What near terrain writes** (its branch, read 2026-11-16). Four targets of 8 bits a channel
and reversed 32-bit float depth, no lit color:

| Target | Red, green, blue | Alpha |
|---|---|---|
| A | The world normal, n * 0.5 + 0.5 | Perceptual roughness |
| B | Sky light, emission strength, block light, each 0 to 1 over the game's 0 to 15 | A response class: 0.375 on terrain, 0 where nothing was written |
| C | The square root of linear albedo after tint | 1 for a conductor |
| D | Reflectance and subsurface (a conductor: its colored reflectance) | The material's occlusion times the game's vertex occlusion |

Its material values come from a buffer of two words a block state: roughness, reflectance,
subsurface and emission in the first, flags in the second.

**What distant terrain has for each.**

| Wanted | Distant terrain | Gap |
|---|---|---|
| World normal | The face's direction, one of six, exact | None. A flat value from the record |
| Roughness, reflectance, subsurface, emission, conductor | Nothing: a distant material is two colors | Distant materials are numbered as they are met, near terrain's by block state. The distant table must carry the block state's number, or the same two words, so both read one material |
| Albedo | The image near the join, the flat color beyond, with the biome's tint, as stored values | Convert to linear and write its square root. Leaves' far color is already what the game shows |
| Sky light | 4 bits a face, from the runs above it | None for open ground. Under canopy it is the face's top (section 6) |
| Block light | None. The preview has no lights | Written as 0. Lava, glowstone and the like beyond the box do not light anything until the preview says where emitters are (Cubature's preview excludes emitters today) |
| Occlusion | 2 bits a corner, softened to 0.08 a step | Write 1 - 0.08 * closed as the vertex occlusion; the material's own is 1 |
| Response class | - | 0.375 as terrain. Whether distant terrain should have a class of its own, so a stage can tell them apart, is open |
| Depth | The frame's own, reversed float | None, with one far distance for the pass (section 8) |
| Coverage, transmission | Water blended by its cover; leaves solid | Water is a translucent: it is not written to these targets but shaded forward after them, as near water will be. Its bed is written |
| Motion, identity | Nothing | Terrain does not move: motion is the camera's, which a stage can work out from depth. A material identity can be the block state's number once the table carries it |

**The shape of the change.** The fragment stage already works out surface, light and fog
apart (section 8). With the targets on, it writes the surface's parts to A to D and applies
no light and no fog: those become the shading stage's, as for near terrain. The forward path
stays for when the targets are off. The opaque and leaf layers draw into near terrain's
framebuffer, after its layers and before shading; water draws after shading with the
translucents. So distant terrain needs: the targets' framebuffer from near terrain, a place
in the frame for "distant opaque" before shading and "distant translucent" after, and the
material words.

**The fog.** The shared fog (section 8) is the game's fixed-function fog with other
distances. A shading stage that brings its own aerial perspective replaces it; until then
the shading stage must apply the same two distances to both terrains, or leave the fog where
it is now, in each terrain's forward path.

### 14.4 Decided, 2026-11-16

Answers to the questions this design raised, from near terrain and from the Riven shader.
Near terrain's targets and material table compile and have not run; its names may move.

**The pattern.** One function for both terrains, in core: `FadePattern` (built, section
14.5). Near terrain calls it; distant terrain owns it.

**The near join.** The coverage map first, then the fade on both sides. Near terrain's fade is
behind a switch that is off by default, since it breaks its pixel parity with the game in the
band; the band is a uniform, so cubes outside it pay nothing; the light's depth pass does not
fade.

**The targets.** Distant terrain draws into near terrain's targets. Order: near opaque, near
cut-out, distant opaque, then shading; translucents after, forward. The targets come from
`TerrainHook.shadingTargets()`: a framebuffer, its size, and a generation that moves when it
is resized. Rules for distant terrain's pass: write the encodings of 14.3 exactly; never
clear; test against the targets' reversed float depth, greater or equal, cleared to 0; turn
the fixed-function alpha test off, since target A's alpha is roughness. No new line of GPU
memory.

**Materials.** One table for both terrains: 8 bytes a block state (roughness, reflectance,
subsurface, emission, flags, a kind), indexed by the 16-bit state number the light scene uses
(block id and metadata). The class is in core and the host publishes the filled table;
distant terrain reads it and drops its own numbering. Where a coarse cell stands for several
states, distant terrain picks the dominant one.

**Response class.** The same as near terrain's, 0.375. A stage that must tell the two apart
uses depth against the box's edge.

**Block light.** None beyond the box is accepted. One addition where the preview knows the
state: emission strength in target B's green for surfaces that light themselves, lava above
all, so distant lava glows. Emitters lighting distant terrain are not needed.

**The pattern over frames.** There is an accumulating stage: temporal antialiasing with
history, whose sampling shifts over a cycle of 8 frames. The pattern is offset each frame
from the published frame index, the one the history targets use, so a band resolves to a
smooth blend. It must still be tolerable in one frame: history resets on a camera cut, a
change of dimension or a resize, and a lower profile may run without it. So the pattern
stays ordered, and bands stay narrow.

**Fog.** On the shaded path distant terrain writes unfogged albedo: the shader's aerial
perspective replaces both distances. 384 and 24,576 stay as settings of the forward path
only. Distant terrain publishes instead the farthest distance it is drawn to, with a signal
when that changes, so that the shader fades to the sky before the edge.

**Water.** Near translucent terrain stays forward after the targets, reading the opaque color
and depth; distant water the same way, its order against near water to be settled when both
exist. Beyond color, cover, normal and depth the shader wants of water:

- a way to tell water from other translucent surfaces: a material kind (100) or a class
  code, since it lights water itself;
- sky light at the surface;
- the water's color as a tint apart from its image, not a lit color;
- water kept out of the opaque depth, with the opaque color and depth under it available
  for absorption and refraction;
- a flat upward normal: it adds the waves.

If distant water gives normal, tint, sky light and kind as inputs, near and far water shade
alike. Distant water has all four today, worked out apart (section 8).

### 14.5 Built: the pattern

`cc.orthrus.quadrature.core.fade.FadePattern`. Ordered, 4 by 4, from the pixel's place in the
window; thresholds are the sixteen odd thirty-seconds in Bayer's order.

```java
static boolean keeps(int x, int y, float fade, boolean complement, int frame)
static String glsl()   // declares: bool quadratureFadeKeeps(vec2 window, float fade, bool complement, int frame)
```

`fade` is how much of the primary side shows, 0 to 1. The primary side asks with
`complement` false, the other with true and the same fade, and exactly one of them keeps any
pixel. `frame` is 0 with no accumulating stage; with one it is the published frame index, and
the pattern moves through its 16 places, one a frame. Over the shader's cycle of 8 frames a
pixel sees 8 of them, so a band resolves in eighths. A fragment stage passes
`gl_FragCoord.xy` and discards where the function is false. Nothing uses it yet.

### 14.6 Still open

- Which side is the primary at the near join. Proposed: distant terrain, its fade rising from
  0 at the band's inner edge to 1 at the box's edge.
- Whether the pattern's walk should be 8 places to fit the shader's cycle.
- The order of distant water against near water.
- Emission: how distant terrain learns a state's emission before the shared table exists.

## 15. The sixth run

Done 2026-11-16; what it showed is at the end of this section. The plan as it was written: one
GPU turn under Riven's lease, two tours on one fresh instance
(`quadrature-distant-09`), on the current build: near terrain's coverage map in place of the
box, the crossfade between levels with tiles fading in, and leaf images filled to their far
color. Cubature 24f5ad89.

**Before it, without a game.** Gradle on Eclipse Adoptium 25.0.5 (`JAVA_HOME`); the Mixin
check with `--mixinkit <orthlib-mixinkit jar>`: 36 hooks on 6 targets, no shadowed member.

**Instance**, prepared as the fifth run's: `--spawn-animals`, `renderClouds:true` set in the
prepared client's options. The lab's launcher runs only at a view distance of 12 and refuses
an instance with any other, so the join stays about 180 blocks out, where a block is 4 to 5
pixels: images near the join can be judged only on trunks, crowns and steps.

**Tour**, `tools/tours/distant-sixth-2485331.json`: `ground_west`; `ground_join`, at ground
level 200 blocks east of it looking west, so that trees first seen from `ground_west` stand
just past near terrain; `above_west`; `coast_join`.

**Runs**, each `play --name quadrature-distant-09 --tour <the tour>` with `JAVA_TOOL_OPTIONS`
set, GPU memory sampled beside both, bounded from outside at 24 minutes:

1. Normal look: `-Dquadrature.terrain=own -Dquadrature.reversedDepth=true
   -Dquadrature.frameLog=true -Dquadrature.distant=own -Dquadrature.distant.world=16384
   -Dquadrature.glDebug=true -Dquadrature.coverage=true`.
2. Level view: the same, and `-Dquadrature.distant.debug=level`. Every level in its own color,
   with the crossfade on, so the bands and the pattern between levels show, and where each
   level begins can be read off. Distant terrain takes no fog in this view.

**Pass, run 1:**

1. Starts and finishes the tour; no error; the driver takes the shaders. The log says near
   terrain's cover is its coverage map, with the counts by state, and that the crossfade is on.
2. No hole and no band of sky at the near join in any view: distant terrain shows under the
   outer ring of columns, which near terrain never meshes, as it did under the box.
3. While near terrain is still meshing after each teleport, the log's count of meshed columns
   rises and distant terrain is drawn under the rest: recorded from the log between a
   teleport and its capture (tiles drawn falling as columns are meshed).
4. Near terrain within 144 blocks agrees with the fifth run's frame of the same view, where
   there is one (`ground_west`, `above_west`, `coast_join`).
5. Crowns: distant crowns from above within a tenth of near crowns' color (the fifth run:
   0.84; the fourth: 1.45).
6. Between levels: no line where one level stops, in `above_west` and `coast_join`; in a
   band, a fine grain of the two levels and no gap.
7. Tiles: stored equal to wanted, with about a fifth more wanted at levels 1 and up than the
   fifth run's 658 and 676; failed 0, refused 0; arena and GPU time beside the fifth run's
   35.6 MiB and 2.0 to 2.4 ms.
8. `ground_join`: looked at and described: whether trunks and crowns just past near terrain
   show their images, and whether the join can be found.

**Pass, run 2:**

9. Each level's color covers its ring; in each band the two colors are mixed by the pattern
   and shade from one to the other across it; no pixel of sky inside the outermost ring.
10. The bands lie where expected: one coarser tile inside each ring's inner edge.
11. Near terrain is as in run 1.

**Still not covered by this run:** a tile arriving in view (stills are captured after a
settle), anything in motion, the pool's threads when a world is left, and a short view
distance.

### 15.1 What it showed

Both tours ran to the end with no error, on the build at bc6ba14.

| | Result |
|---|---|
| 1 | Pass. The log names the coverage map and the crossfade |
| 2 | Pass: no hole and no band of sky at the near join in any view |
| 3 | Meshed columns rise after a teleport (the coast: 0, 355, 529 of 625; `ground_join`: 264, 284, 562). The tiles drawn beside them were not read off |
| 4 | Pass: the rows near terrain fills are identical to the fifth run's in `ground_west` and `above_west`. In `coast_join` near water differs by about 1.5 of 255 on a sixth to a quarter of its pixels; not traced |
| 5 | Pass, once measured rightly: 0.94 of near crowns' brightness where images show (the fifth run: 0.88) and 0.92 beyond. See below |
| 6 | Pass by eye in `above_west` and `coast_join` |
| 7 | Pass: 764 to 788 tiles wanted and stored (the fifth run: 658 and 676), failed 0, refused 0. Arena 42.4 MiB (35.6); GPU 2.6 ms mean, 3.1 at most (2.0 to 2.4); 386 tiles and 1.9 million faces in 3 draws |
| 8 | The join cannot be found by eye; trunks and crowns go on past near terrain. At 4 to 5 pixels a block an image cannot be told from a flat color |
| 9 | Pass: levels 0 to 4 in order up the frame, each pair mixed by the pattern across its band; sky-colored pixels inside the leveled ground 0.00 to 0.05 % |
| 10 | In part: every band is there and in order; that each lies one coarser tile inside its ring's edge was not measured |
| 11 | Not a test: in the level view the shared fog is off, so near terrain stands in the game's own fog, 144 to 192 blocks, and fades out before level 0 begins |

At rest the coverage map read 529 of 625 columns meshed and 96 waiting, the 96 being its outer
ring, which near terrain never meshes; after a short move, 562 and 63.

**Crowns.** The first measure gave 0.88 and 0.86, and 0.83 for the fifth run. It counted every
dark green pixel as crown, and near terrain's rows hold small blue-green plants that distant
terrain does not draw, which are brighter than leaves. With those left out (blue below red)
the figures are 0.94 and 0.92, and 0.88 for the fifth run, so the filled leaf images moved the
band where they show from 0.88 to 0.94. What is left is not a color:

- A distant crown's pixels are three colors and no others in number: (55, 64, 39), and 0.8 and
  0.6 of it. Those are the top face and the two shades the game gives side faces, which near
  terrain gives too. The top color is near crowns' commonest, (56, 64, 38) and (54, 63, 38).
  So the far color is right, and corner occlusion, which would show as steps of 0.08, is not
  what darkens them.
- Farther crowns are seen at a flatter angle and show more of their sides: the darkest class
  is 26 % of near crown pixels, 37 % where images show and 45 % beyond. The view looks west,
  at east faces, which have the darker of the two shades.
- Grass, as a control: (108.5, 103.8, 48.3) near against (109.3, 104.5, 48.8) and
  (109.2, 104.8, 50.4) distant, so the light on top faces and the biome's tint agree.

Nothing was changed. Seen beside it and not looked into: distant trunks under a crown are
nearly black where near trunks are brown, which would be the sky light the preview gives
under leaves.

**Seen in the level view at the coast.** With the coverage map, distant sea bed shows through
near water over a wide area, entered from some 50 blocks out. In a column near terrain has
meshed, a distant fragment within the heights of its cubes is discarded (there, Y -80 to 288),
so what shows is in columns it has not meshed, 176 blocks out and farther, or below Y -80.
It is the first. Riven's own sampler gives the bed along the view's line (north-east from the
camera, in the 32 km layout): a shelf from Y 54 under the camera to 41 at 120 blocks, then a
drop to 20 at 158 blocks, -7 at 182 and -45 by 290, never near -80. A line of sight steeper
than about 27 degrees below level meets the shelf, which near terrain draws; a flatter one
passes over the shelf's edge and comes down on the deep bed 180 to 400 blocks out, beyond near
terrain. In the frame the edge between the two lies at about 28 degrees. So near terrain's bed
is not missing and nothing lies below its cubes: the ground past it is completed. In the normal look that area is lighter
by about 2 of 255 (37, 52, 173 against 35, 49, 170), which makes it a candidate for the
lighter patches in near water; that it is their cause is not shown.

## 16. Plan: the shaded path

Not built. How distant terrain comes to be shaded with near terrain, by what section 14.4
decided. It names what distant terrain changes, what it waits for from near terrain, and the
order.

### 16.1 What exists and what does not, 2026-11-16

In this branch, with near terrain merged at 052490c:

| Needed | In the tree | |
|---|---|---|
| `TerrainHook.shadingTargets()`: the framebuffer, its size, a generation | No | Near terrain has its targets and their program written and not committed |
| The material table: a class in core, 8 bytes a state, published by the host | No | Likewise. Near terrain numbers a state by `Block.getStateId(state) & 0xFFFF` for its light scene already (`CubeMesher`) |
| A place in the frame for "distant opaque" between near cut-out and shading | No | Distant terrain is drawn from a Mixin before the first draw of entities; the frame graph has no stage for it |
| The opaque color and depth under translucents, for water | No | With the targets |
| A published frame index, for the pattern | No | The shader's history targets define it |
| A place to publish the farthest distance drawn | No | `TerrainHook` publishes the light's depth, the light scene and the coverage map; nothing of distant terrain |
| The pattern both terrains share | Yes | `FadePattern` |
| The coverage map | Yes | `TerrainHook.coverage()` |

So everything below but 16.2 waits on near terrain's commit of its targets and table. Their
names may differ when they come; what is fixed is section 14.3's encodings.

### 16.2 Materials: what the face record and the tile must gain

**The record does not change.** Its 13 bits of material stay distant terrain's own index,
numbered as states are met, 8,192 at most; a world's preview has met about 300. A state
number is 16 bits and does not fit, and it need not: the index leads to it.

**The material's entry gains a word.** Today two words a material: top and side, each a
color, a tint and a cover. A third holds the state number, `Block.getStateId(state) & 0xFFFF`,
the number near terrain's table is indexed by. In the vertex stage a face reads its entry,
takes the state number, and passes it on flat; the fragment stage reads the shared table by
it for roughness, reflectance, subsurface, emission, the conductor flag and the kind. The
color, tint and cover stay distant terrain's own, since the shared table has no flat colors
and no far-leaf color.

**The tile does not change.** A run is a state already; the preview chooses the state a
coarse cell stands for, by its footprint, and that is the dominant state. A tile holds runs,
tints and faces as now.

**Sky light, occlusion, direction:** in the record already. **Block light:** written as 0.
**Emission:** from the shared table by state, so lava and the like glow with no change to a
tile. Until the table exists there is nothing to read emission from; distant terrain does not
make its own list of bright blocks.

**Water's kind:** from the table by state (100, or what near terrain settles), so the shader
tells water from other translucents by the same value on both sides.

### 16.3 The opaque pass

With the targets present (`shadingTargets()` not null) and the shaded path asked for:

- Drawn after near terrain's cut-out layer and before shading, into near terrain's
  framebuffer: four targets and its reversed float depth. Never cleared by distant terrain;
  depth test greater or equal against what near terrain wrote; the fixed-function alpha test
  off; blending off.
- The opaque and leaf layers only. Water is not written here.
- The fragment stage's last step changes and nothing before it: where the forward path
  multiplies the surface by its light and mixes in the fog, the shaded path writes
  - A: the face's direction as a world normal, n * 0.5 + 0.5; roughness from the table;
  - B: sky light as light / 15; emission from the table; block light 0; the class 0.375;
  - C: the square root of linear albedo, the albedo being the image or flat color with the
    biome's tint, converted from stored values as near terrain converts; the conductor flag;
  - D: reflectance and subsurface from the table (a conductor: its albedo); occlusion as
    1 - 0.08 for each cell that closes the corner in.
  No light, no fog.
- What near terrain covers is left out as on the forward path, by the coverage map, and the
  crossfade between levels and the fade at the near join run as there: a discard is a
  discard in either path.
- The far distance stays raised for the pass (section 8), so the targets' depth holds distant
  terrain at its true depth.

The forward path stays, for when the targets are absent, and is what the runs so far drew.

### 16.4 Water

After shading, with near terrain's translucents, forward, reading the opaque color and depth:
distant water gives the shader a flat upward normal, the biome's water tint apart from any
image, sky light at the surface, cover, depth, and the kind. It writes no opaque depth. The
order against near water waits until both exist; the box or the coverage map already keeps
them from overlapping except in a fade band.

### 16.5 What distant terrain publishes

- The farthest distance it draws to: the outermost ring that has tiles, `reach << level`, less
  nothing; and the nearer of that and the world's bound where one is set. With a signal, a
  generation that moves, when it changes: the rings' levels are fixed for a session, so in
  practice when a world is joined or its bound is learned. The shader fades to the sky before
  it.
- Whether the shaded path is in use this frame.
- Through `DistantHook`, as near terrain publishes through `TerrainHook`.

### 16.6 Order of commits

Those marked (wait) need near terrain's targets and table in this branch first. The first three
are built and not yet run in a game; none changes what the forward path draws.

1. Built. The material entry's third word, the state number, filled and unused
   (`MaterialTable.ENTRY_WORDS`, `put`). The top and side words are packed as before, which
   `MaterialTableTest` pins beside the layout.
2. Built. `DistantHook.farthest()` and `farthestGeneration()`: the reach of the highest ring
   that has tiles, or the distance to the world's far edge where a bound is set and that is
   nearer, along an axis (`Rings.drawnTo`, tested with a bound and without). The ring's
   square is moved to even tile positions, so its edges lie within one of its tiles of that.
3. Built. The fragment stage's last step is its own text (`FORWARD_OUTPUT` in
   `DistantProgram`): `finish`, the surface times the face's light and then the fog, and
   `finishFlat` for a debug color. The targets path will be a second such text. Checked
   without a game: the vertex stage's text is unchanged, and the fragment stage holds the
   same statements in the same order within `finish`, with only the two functions' wrappers
   and their calls added. Its frames are still to be compared with a run's.
4. (wait) Read the shared table by state number in place of nothing: roughness and the rest.
5. (wait) The opaque pass into near terrain's targets at the place the frame gives it, with
   the encodings of 16.3. Checked first by reading the targets back at a known pixel over
   distant ground: normal, sky light, albedo.
6. (wait) Water after shading, as inputs.
7. (wait) The near join's fade on both sides, with near terrain's half behind its switch.
8. The pattern's frame from the published frame index, once there is one.

### 16.7 What near terrain must expose that it does not yet

- `TerrainHook.shadingTargets()`.
- The material table and how a state's number is made, as an interface and not as
  `CubeMesher`'s own code.
- A call or a stage at which distant opaque is to draw, between its cut-out layer and shading,
  with the targets bound; and one after shading for water.
- The opaque color and depth under translucents.
- The frame index its history targets use.

## 17. A tour of distant terrain in travel

Run once, 2026-11-16 (17.1). Every run so far captured stills after a settle, which never show a tile
arriving, its fade over 24 frames, the coverage map handing columns over, or a ring moving.

**What the lab's tools do today.** A tour is a list of views; the launcher teleports to each
and waits for the client to say it captured one. The capture is made by Riven's client, once
a view, when the player has stood still for the instance's settle time (`--capture-ticks` at
prepare, 100 ticks at least, one value for the whole instance) at a place more than 8 blocks
from the last capture. There is no capture before the settle and no settle time for one view.
None of that is changed.

**What is added, in this mod and its own runner only.**
`-Dquadrature.distant.travelCapture=500,2000,5000,12000` (`TravelCapture`): each time the
viewer jumps more than 8 blocks, a frame is saved at every listed delay after the jump, at the
end of that frame's world pass, as `travel-<jump>-<delay>ms.png` in the game's
screenshots, and the log's summary (tiles stored by level, the coverage map's counts) is
written beside each. The first frame of a world is jump 0. The launcher's own teleports are
the jumps, so a tour needs nothing more; where it lowers the player in steps each step is a
jump of its own, and the log says where each jump went. The runner copies those frames beside
the tour's.

**The tour**, `tools/tours/distant-travel-2485331.json`, on an instance prepared with the
launcher's least settle, 400 ticks, so that all four delays fall before the settled capture:

1. `arrive_ground`: the join, at the place of `ground_west`. Nothing is stored yet.
2. `arrive_above`: 2 km east and 160 high, looking west and down. Near terrain and every
   ring begin again: tiles arriving coarsest first, near columns handed over by the map.
3. `step_1` to `step_6`: 32 blocks west each time from there, same height and view. Ring 0's
   square moves by 128 blocks at a time, so in 192 blocks it moves at least once: tiles enter
   at one edge and fade in, and the band between levels 0 and 1 moves across fixed ground.

**To look for.** At 0.5 s: sky where nothing is stored, or coarse levels only, and no hole
inside near terrain's columns as they are meshed. At 2 s and 5 s: finer levels filling in from
the middle outward; a tile newly stored showing as a grain of the pattern and not popping.
After a step: no line where the band between two levels moved, and no tile kept past its ring
left over a finer one. The same tour in the level view shows which level each pixel is.

**Not covered still:** continuous motion, which needs a flight and a video or a run of frames
a tick apart.

### 17.1 The seventh run

Two runs under one lease on the build at e4df9ef, neither with an error: the travel tour, and
the sixth run's still tour on a twin instance with the long settle, for comparison.

**The still tour against the sixth run.** Every row that should not change is identical in
`ground_west`, `ground_join` and `above_west`; in `coast_join` only near water differs, by
about 1 of 255, as between earlier runs. What changed is what the light under a branch was
meant to change: in `above_west` 2,637 pixels of distant terrain, every one darker than 30
before and none darker after; pixels darker than 22 in distant rows fell from 2,120 to 110,
and grass at a fifth of its color from 476 to 2. So the entry's third word, the published far
distance and the split of the fragment stage's last step left the forward look as it was.
788 tiles of 788, arena 42.4 MiB, GPU 2.63 ms mean.

**The travel tour.** Seconds after distant terrain's first frame in the world:

| When | Stored of 764 | Levels | Columns meshed of 625 | The frame |
|---|---|---|---|---|
| 0.5 to 24 | 0 | none | 474 to 529; 0 to 529 after the far jump | Near terrain and sky |
| 31 | 4 | 6 | 529 | A strip of far land on a dark slab |
| 92 | 92 | 6 whole, 5 half | 552 | A tall dark wall across the view |
| 100 | 284 | 6 to 4 whole, 3 in part | 507 | Land at the horizon, a dark wall under it down to near terrain |
| 112 | 557 | 6 to 2 whole, 1 half | 552 | Whole but for a dark gap past near terrain and a pit at one side |
| 121 | 709 | 1 whole, 0 in part | 506 | Whole from this view |
| 125 | 764 | all | 552 | Whole |

- **A view is whole about 125 seconds after a join.** The first tile takes 30 seconds and
  level 6 alone 90; levels 5 to 0 then come in 35. Level 0, nearest the viewer, is last.
- **After a step of 32 blocks** with everything stored, the frame half a second later is
  whole. Near terrain's columns are handed over within 2 seconds: 506 meshed at half a
  second, 552 at 2.
- **The tour's fault.** Its far jump came 9 seconds after the join, before any tile was
  stored, so the time to a whole view after a jump was not measured apart from the join's.
  A travel tour must stand until all is stored before it jumps.
- **Not seen because not captured:** a tile's fade over 24 frames, and whatever near
  terrain's map interval of 20 frames leaves at the trailing edge; the delays are seconds.

**A defect: skirts show while finer levels are missing.** A tile's skirt faces have no material
and no light and are meant to be hidden by the tile beside them. Inside a ring's hole the
tiles beside them are the finer level's, and until those are stored the skirts on the hole's
sides are in plain view: a dark wall from the surface to the world's floor, from about 30 to
120 seconds after a join. Open: how to close it. A skirt in the ground's color would only
make the wall less strange. What is wanted is no open hole: a coarser tile drawn over ground
whose finer tiles are not stored yet, giving way to them as they arrive, which the crossfade's
partner flags already describe for the band.

## 18. Arrival: no hole and no wall

Built and tested without a game; not run in one. What the seventh run showed is in 17.1: for
two minutes after a join the ground between near terrain and the coarse levels was sky or the
dark side of a skirt.

### 18.1 The rule

A level's ring has a hole, which is the square of the level below. Until now nothing of that
level was wanted inside the hole, so the ground there was empty until every finer level had
arrived, the finest last.

**Fill tiles.** With `quadrature.distant.fill` on, a level takes its whole square: its ring,
its band, and the tiles inside its hole past the band, 16 a level at a reach of 384
(`Rings.fill`, `Rings.filling`). A fill tile lies over ground the finer levels draw and is
drawn only until they do.

**By place, not by tile.** The finest shown tile over a place draws it (shown: stored, wanted
and past its fading in). A coarser tile draws a place only where no finer level has a shown
tile; the one exception is a band, where a level's band tile and the shown tile of the next
finer level under it are both drawn and fade into each other. Nothing waits: a finer tile is
seen as soon as it is shown, and the coarser tile under it goes on drawing the ground beside
it. So tiles may arrive in any order.

The draw decides it for each fragment. Each level has one bit for each tile of its square,
set when the tile is shown (`Cover.masks`: 144 bits a level, 40 words for eight levels, sent
as uniforms with where each square begins). A fragment of level k asks levels k - 1 down to 0
whether a shown tile lies over its place and is discarded at the first that does, unless it is
in its band and the pattern keeps it. The crossfade's finer side asks the same of level k + 1.
The partner flags in a tile's record, which said the same for whole tiles, are no longer used.

The first version of this section gave ground up by quarters of a fill tile and made finer
tiles wait under a quarter not yet given up. That held the invariant but hid a stored level 0
under a level 6 quarter, two kilometers across, until levels 1 to 4 were whole.

`Cover` states the rule for a place and `CoverTest` checks it by enumeration: of the shown
tiles over any place exactly one draws it whole, the finest, and besides it at most the band
tile over it; the bits the draw reads agree with the tiles shown. Four levels from four
cameras with tiles stored one at a time, in both orders; thirty random subsets in bounded and
unbounded worlds; seven levels in the lab's world.

**Letting go, and not building.** A fill tile all of whose ground finer shown tiles draw is of
no use (`Cover.spare`): if stored, its storage is freed (`TileSchedule.retire`); if not yet
built, it is not built (`TileSchedule.next`). Either way it stays wanted and is marked spared,
which holds no other tile back and is asked about again at the next selection. Letting spare
tiles go leaves the same places drawn, which the tests check. In steady travel, where the
finer levels are already stored when a hole moves, fill tiles are neither sampled nor stored.

**Order** (`quadrature.distant.order`, section 19). `near`, the default: the coarsest level's
fill tiles, which cover the ground about the viewer out to 8 km, then every level from the
finest, each nearest first, and last the rest of the coarsest. The finer levels' own fill
tiles come after the levels that cover them and are spared, so only the coarsest level's are
built. `coarse`: the coarsest level first, as before.

### 18.2 What it costs

On a cold join in the default order, the coarsest level's fill tiles and no others: 16 more
tiles beside 764 (12 in the lab's world from the tour's place). In the arena, those tiles
until the levels under them are whole. In the order `coarse` every level's fill tiles are
built: 96 more, about 5 MiB at the worst moment.

In the fragment stage: no texture is read. A fragment of level k reads up to k words of the
uniform bits, one for each finer level, and stops at the first that is set; level 0, which
fills most of the near screen, reads one word, for the crossfade's test against level 1. That
replaces two tests of a flag. The GPU time is expected to stay near the 2.6 ms measured, and
is to be read from the next run and not taken from this.

### 18.3 Skirts

A skirt face closes the gap beside a tile of another level. It had no material and no light,
which was the dark wall. It cannot count on being hidden, so it now has the material at its
top and the light the column beside it lets in above it, as the side face over it has
(`RunMesher.skirt`). With fill tiles a hole's sides are covered as soon as that level's fill
tiles are stored, which is before its ring's.

### 18.4 Open

- A place changes hands when the finer tile's fading in ends: while it fades in, the coarser
  tile is still whole under it, so the pattern shows the finer through gaps only where it
  lies nearer the eye.
- Tiles kept past their ring are still drawn whole over whatever replaces them, as before.
- How long the first tile takes is not changed by any of this: section 19.

## 19. Arrival: how long, and in what order

Measured without a game, through Riven's own preview with nothing built: a fresh JVM a run,
seed 2485331, the 32 km layout, the travel tour's place, 4 threads, levels 0 to 6 with bands
and fill tiles. Seconds from the first request; constructing the preview takes 5 to 8 before
it, 1.7 with a regional store.

| Order | First tile | Coarse cover to 8 km | Level 0 whole | All |
|---|---|---|---|---|
| Coarsest level first (as run so far) | 22 | 40 | 113 | 113 |
| Finest first, outward | 19 | level 5 at 89 | 30 | 117 |
| Coarsest fill, level 0, outward | 19 | 34 | 46 | 107 |
| Coarsest fill, every level's fill, level 0, outward | 19 | 34 | 47 | 102 |
| Coarsest level first, reading a regional store | 12 | 23 | 98 | 98 |
| Coarsest fill, level 0, outward, reading a store | 13 | 25 | 36 | 89 |

**The first 19 to 22 seconds** are the same whatever is asked first; one level 0 tile alone
takes 19. Stacks sampled in a cold start: over half of the worker threads' samples wait in
Riven's `SharedBuild.get`, three threads on a build the fourth is doing; the work is coast
forms for the cells about the viewer, the continental outline, coast sediment, drainage and
the province map. It is Riven's, and one thread's.

**Why the coarsest level is slow.** Not its cells: warm, a level 6 tile costs 1.3 to 1.75
seconds of CPU against 0.12 for level 5. Level 6 covers the whole world and so builds Riven's
regional data everywhere; it is half of all the sampling. After it everything finer is warm:
levels 5 to 0, 792 tiles, take 40 seconds.

**The regional store** takes the first tile from 19 to 22 seconds down to 12 or 13 and the
coarse cover from 34 to 40 down to 23 to 25 here; it does not hold coast forms or the outline.
A client in single player borrows the server's generator, whose builds it shares; that was
not measured.

**Chosen:** the coarsest level's fill tiles, then level 0, then outward (`order=near`). Cold:
a first picture at 19 seconds, coarse ground to 8 km at 34, full detail about the viewer at
46, all at about 107. Finest first shows detail 16 seconds sooner and the horizon 55 later.

### 19.1 The cold start inside Riven: what was found, and a proposal

Looked into in Riven's sources and with the same harness; nothing of Riven's was changed.

**What happens.** Every column asks for the coast forms of the nine coast cells about it. Cold,
the first of those cells is built once, by whichever thread asks first; the others wait for
it, since a build is single-flight by key, and they all need the same key. In 41 of 64 stack
samples of a cold start exactly one of four tile threads was working. What it works on: the
coast cell's forms need the ground before them, which needs drainage, whose build samples the
continental outline's kernels over a wide area. So it is one build that everything near needs,
and reordering which thread asks for which cell cannot help: there is one cell to wait for.

**Why it runs on one thread.** Drainage and hydrology split their builds over Riven's build
pool, but the preview's sampler marks its thread alone (`BuildPool.alone`), on purpose: a
distant preview tile would otherwise hold pool helpers that the server's own generation waits
for. On a client there is no generation to protect, and no pool either.

**Measured,** with the mark lifted by a switch in a scratch build that was not kept, the
coarsest fill tiles and then level 0, cold:

| Tile threads, build pool | First tile | Coarse cover | Level 0 whole |
|---|---|---|---|
| 4, alone (as now) | 18.7 | 34 | 44 |
| 4, pool of 4 | 15.7 | 26 | 43 |
| 2, alone (as now) | 19.6 | 40 | 58 |
| 2, pool of 2 | 15.7 | 30 | 51 |
| 1, pool of 3 | 15.3 | 39 | 75 |

So pooled builds are worth about 3 seconds on the first tile and 8 to 10 on the coarse cover,
with the same total of threads in the last two rows as in the first. Most of the first 15
seconds stays on one thread even then: the outline sampling inside the drainage build is not
in a part the pool splits. Where exactly was not found.

**Proposal, for Riven's author; not built.** Each needs a decision about the generator and
none changes terrain, the builds being pure:

1. On a client, let the preview's builds use a pool: the client's preview is built with
   `inputs.buildPool()`, which could be Cubature's client preview pool or a small pool of its
   own, and the sampler would not mark itself alone there. Gain as measured above. The
   server is not touched: its sampler keeps the mark.
2. Split the outline sampling inside the drainage build over the pool as its other stages
   are. This would help the server's cold builds as well, and is where most of the remaining
   15 seconds are. Its gain is not measured.
3. Hold coast forms, or what they need of drainage, in the regional store, and offer the
   store at a join: 7 to 9 seconds on the first tile with the store as it stands.

A change that only reorders which thread asks for which cell was considered and dropped: the
threads wait on one cell's build, not on each other's cells.
