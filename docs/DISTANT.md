# Distant terrain

Status: the part with no graphics in it is built and tested, in `core`
(`cc.orthrus.quadrature.core.lod`): the run tile, the run mesher, the face record, the ring
selection, the schedule and the draw list. The first frames are written and compile, in
`cc.orthrus.quadrature.distant` (section 8): sampling, materials, an arena and the draw.
They have run in a game since (sections 9 to 13, 15, 16.9 and 17): tiles are sampled on Cubature's client
preview pool, stored and drawn in the right places; land shows beyond near terrain to the
horizon under one fog; distant land begins where near land ends with no band of sky; near and
distant water meet at one level and in one color; on land and over the sea the near box does
not stand open; an entity 150 blocks out is drawn in front of distant land; near terrain's
coverage map stands in for the box with no hole; levels fade into each other with no line;
distant crowns have near crowns' color; with the shading stage shown, distant ground is lit by
it as near ground is (16.9). **Untested still:**
the pool's threads when a world is left, whether images near the join show, a tile arriving
in view, anything in motion, the hand, a short view distance, and the rest of section 14.
Phase 4's services and work orders are in [PLAN.md](PLAN.md).
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

The records for the techniques here are in [PROVENANCE.md](PROVENANCE.md), from "Run tiles"
to "Writer audit". No renderer's or level-of-detail mod's source was read for any of it. The
published sources they cite were read again on 2026-11-17; Rakos's chapter itself was not
available, and its record rests on the chapter's published sample shaders.

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
| `quadrature.distant.fogEnd` | the outermost ring's reach less one of its tiles (20,480) | Where the shared fog is complete: no farther than the nearest side of the outermost square can be (section 16.10) |
| `quadrature.distant.debug` | none | `box`, `level` or `join` (see Debug views) |
| `quadrature.distant.crossfade` | `on` | `on`: two levels are drawn over a band where they meet and fade into each other, and a tile fades in when it arrives. `off`: each level ends on a line |
| `quadrature.distant.fill` | `on` | `on`: every level also takes the tiles inside its own hole and draws them until finer tiles are stored, so no hole is open while distant terrain arrives (section 18). `off`: each level its ring only |
| `quadrature.distant.order` | `near` | `near`: the coarsest level's fill tiles, then every level from the finest outward. `coarse`: the coarsest level first. Needs fill tiles (sections 18, 19) |
| `quadrature.distant.shaded` | `off` | `on`: the opaque layers are also drawn into near terrain's targets for shading, where it makes them (section 16.8). The forward picture is unchanged |
| `quadrature.distant.joinFade` | `off` | `on`: distant terrain fades in across near terrain's last blocks. No effect unless near terrain says it fades (section 20) |
| `quadrature.distant.joinFadeBand` | none | `start,end` in blocks: a debug view of distant terrain's half of that fade alone, whatever near terrain does |
| `quadrature.distant.detail` | `images` | `images`: level 0's faces show their materials' images near the join. `flat`: flat colors everywhere |
| `quadrature.distant.detailStart` | 224 | Up to this distance from the eye, in blocks, a level 0 face shows its image whole |
| `quadrature.distant.detailEnd` | 352 | From this distance on, flat color only |
| `quadrature.distant.travelCapture` | none | Delays in milliseconds, `500,2000,5000,12000`: after every jump of the viewer a frame is saved at each, as `travel-<jump>-<delay>ms.png` in the game's screenshots, with the log's summary beside it, and one more when the place is whole, `travel-<jump>-whole-<ms>ms.png` (section 17) |
| `quadrature.distant.writerAudit` | off | A diagnostic: reads a wide list of the driver's state before and after the first three passes into the targets and logs what was left changed (section 16.9) |
| `quadrature.distant.frameTail` | off | A diagnostic: once a second, the tail of the last 120 whole frame intervals as Cubature's preview governor reads it, and what made the long ones (section 19.2) |

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

`-Dquadrature.distant.debug=join` paints in orange, in flat color, each pixel distant terrain
keeps over a column near terrain has meshed: the pixels it trades with near terrain in the
join's band (section 20). Outside the band, and wherever the band is not read, it draws as
usual.

The first box view (section 11) drew those faces at their own depth. Distant ground lies in
the plane of near ground, so depth tied and magenta covered near terrain almost everywhere.

`=level` gives every face a color for its tile's level (0 red, 1 orange, 2 yellow, 3 green, 4
cyan, 5 blue, 6 violet), with no fog on distant terrain and the game's fog on near terrain.

**What near terrain covers.** Fragments inside a box round the camera are discarded: the
loaded cubes' reach on each axis less one cube, since near terrain meshes a cube only among
loaded neighbors and so never the outermost. The reach is the cube source's held box where
the server states one (`ICubeSource.heldBox()`: the cubes it keeps sent, around where it has
the player, which can trail the viewer by a tick or two and can be smaller than the client
asked for), and the client's own view distances around the viewer's cube where it states
none; `NearCover.box` takes either. This box is what is used without the coverage map; with
the map the rule is by column and the box gives only the heights. It is a box, not a column:
above and below the vertical reach distant terrain shows. `NearCover` holds the rule, and a test checks it against
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

### 16.7 What near terrain exposes now, and what it does not yet

In this branch since near terrain's merge at f78878e: `TerrainHook.shadingTargets()` (the
framebuffer, four textures, the depth, their size, a generation that moves each frame they are
drawn), `TerrainHook.materials()` (the table, eight bytes a state) and
`TerrainHook.material(state)` (a state's number, filling its row). Its contract is
[ARCHITECTURE.md](ARCHITECTURE.md), section 6.6, which is the authority for the targets'
layout; where 16.3 differed it is corrected here:

- B is sky light in red, emission in green, block light in blue; alpha 0.375.
- D for a surface that is not a metal is reflectance in red and subsurface in green, blue 0;
  for a metal its linear albedo. Its alpha is occlusion.
- Albedo is made linear by the exact sRGB curve before its square root is taken.

Not there yet: a stage in the frame for distant opaque terrain (it is drawn from the Mixin
that draws the forward pass, which runs after near terrain's last cutout layer); the opaque
color and depth under translucent surfaces; a frame index.

### 16.8 Built: the opaque pass into the targets

Behind `quadrature.distant.shaded`, off by default; not run in a game.

After the forward draw, in every world pass in which near terrain drew the targets
(`ShadedGate`: its count of passes, `TerrainHook.frame()`, equals the targets' `drawnFrame()`),
the opaque and leaf layers are drawn again through the published
framebuffer: its viewport, the alpha test and blending off, nothing cleared, the depth test as
the frame has it, which with reversed depth is nearer-or-equal against near terrain's own
depth; the framebuffer, the viewport and those two states are put back. The faces, the
coverage rule, the fades and the surface are the forward pass's; only the fragment stage's
last step differs (`TARGETS_OUTPUT` for `FORWARD_OUTPUT`):

- A: the face's direction as a world normal; roughness from the table.
- B: the face's sky light over 15, emission from the table, block light 0, 0.375.
- C: the square root of the linear albedo, which is the image or flat color with the biome's
  tint; 1 for a conductor.
- D: reflectance and subsurface from the table, or the linear albedo for a conductor; alpha,
  occlusion on the game's scale, which near terrain writes: 1 open, 0.2 less for each closed
  neighbor of a corner, 0.4 where both edge neighbors are closed. At a coarse level the
  darkening is divided by the cell's size: the mean over the cell of what the blocks along
  its edge would have had, not a deeper value.
- Crowns (`quadrature.distant.shaded.leaves`, default `far`): C holds the far color the
  forward path draws, with occlusion as for any block, which is what near terrain's leaves
  write at a distance: their images are read from smaller copies that average the gaps in.
  `plain` writes the leaves' own albedo, the far color in light over the share of the image
  that is leaf, and multiplies D's alpha by 0.8 for the gaps. The ninth run measured `plain`
  three times brighter than near crowns in albedo (0.155 against 0.052 in green) and 0.80
  against 0.94 in occlusion, so `far` is the default. With `far` the run after it measured
  albedo equal across the join, (0.040, 0.053, 0.021) against (0.039, 0.052, 0.020), and
  occlusion 0.999 against near terrain's 0.936: near crowns' tops are open on 57 % of their
  pixels and closed in by other leaves on the rest, which the game's occlusion shows and this
  mesher's, counting opaque cells only, does not. So in `far` a crown's occlusion is
  multiplied by that mean, 0.936, in the targets only. A per-corner value for leaves over
  leaves would need a place in the face record. Both sides are to go to `plain` when
  near terrain's images keep a leaf's color in their smaller copies; that waits on near
  terrain.

**Settled since, by near terrain and the shader thread:**

- The targets' generation counts how often they were made anew and is no sign of a frame.
  The first version of this pass waited for it to move; the second drew whenever the targets
  stood. Near terrain now publishes the pass the targets were drawn in beside its count of
  passes, and the pass draws when they are equal, which `ShadedGateTest` holds: pass after
  pass with the generation unchanged, and not in a pass the targets were not drawn in.
- The material table is read from near terrain's own storage buffer
  (`TerrainHook.materialsBuffer()`), asked for after distant terrain's materials have had
  their rows filled; where it is 0 the pass is skipped. Distant terrain keeps no copy and
  reads no list of kinds: a state's row is near terrain's to fill.
- The pattern's frame. On the shaded path the fades between levels and of arriving tiles take
  near terrain's count of passes, since a later stage accumulates over frames; on the forward
  path they take 0. The near join's band takes 0 on both paths, because near terrain's half
  of it does, and the two halves must use one pattern.
- Occlusion and leaves as above.
- Sky light by column is accepted: open ground is exactly 15 and under a canopy it falls one a
  block of leaves, which `RunMesherTest` holds (a flat top at 15; 15 less 3 under three blocks
  of leaves).
- A pixel in a fade carries one surface whole in all four targets and depth.
- Block light 0 and no distant water are accepted for now.

**Images of the targets.** `quadrature.distant.shaded.image=true` writes the four targets,
their alphas and the depth as images beside each travel capture, after this pass
(`TargetImages`). Near terrain's own images are written before distant terrain draws and show
near terrain alone.

**The ninth run, planned:** at noon, a view across the near join from above and one from the
ground, each with the targets written, with crowns `plain` and again with `far`; read from
them that a surface has the same albedo, normal, sky light and occlusion just inside and just
outside near terrain, which is the shader thread's test.

### 16.9 The pass as a writer of near terrain's targets

**Registered.** The pass is a writer of near terrain's targets
(`TerrainHook.addTargetWriter`): near terrain calls it after its cutout layer has written the
targets and before its shading stage reads them, stage on or off, in every world pass in
which the targets were drawn. Distant terrain registers it while the shaded path is on and a
terrain preview is attached, and takes it off when the preview goes. Between near terrain
d574a3e, which moved the stage in front of entities, and this registration the pass wrote
after the stage had read the targets: no run from a jar of that span with the stage on is to
be read for distant terrain.

**A pass has two places now.** The writer, where near terrain calls it, and the Mixin's place
at the first draw of entities, where the forward layers and the water are drawn. The pass's
preparation (the view, the tiles that finished, the selection, the frame's list) is done
once, by whichever place the pass reaches first: the writer in a pass whose targets were
drawn, the Mixin's place otherwise. The view is near terrain's own where it has one
(`TerrainHook.mainView()`: the projection and model-view read when its first opaque layer
began and the origin they are relative to), and the driver's matrices with the viewer's
interpolated place only where own terrain is off. Inside the writer's call the pass gives
`TerrainHook.targetsEdge(DistantHook.nearestEdge())` (16.10), also in a pass in which it
drew nothing yet, since the value is the selection's.

**The contract.** `TerrainHook.addTargetWriter(Runnable)` and `removeTargetWriter(Runnable)`: writers are
called on the client thread in the order registered, once in every world pass in which the
targets were drawn, after its cutout layer wrote them and before the stage reads them,
stage on or off. On entry only documented state is promised; on return the writer has put
back what it changed.

**What near terrain says of the call:**

- `TerrainHook.mainView()` is this frame's at the call: it is filled when the frame's first
  opaque layer begins, and writers are called after terrain's layers, in front of entities.
- On entry, from its source: the game's framebuffer bound and the viewport the window's; no
  program and no vertex array; array and element buffers none; sampler objects on units 0
  and 1 none; shader storage points 0 to 3 unbound. From the game's sequence, to be
  confirmed by the state audit: depth test on, depth written, depth function
  greater-or-equal under reversed depth; culling on; blending off; alpha test on. A writer
  therefore sets what it needs and counts on none of it.
- A writer added or removed from inside a call takes effect from the next pass.
- The state audit lists each writer as a stretch of its own, so what one leaves changed is
  listed under it.
- After the writers nothing of near terrain's trusts a binding.
- `TerrainHook.targetsEdge(float)`, called from inside a writer: how far the targets hold
  terrain without an edge (16.10), for that pass only; the largest of several writers'
  stands, and near terrain's own edge where none is given.

**The pass.** It is one piece, `Distant.writeTargets`, held as a `Runnable`. It asks the gate and the material table itself, binds the
targets' framebuffer for drawing, sets the viewport to their size and every switch it
depends on, draws, and puts back what it touched. What it sets and does not merely find:
depth test on, nearer-or-equal (greater-or-equal in reversed depth), depth written, alpha
test off, blending off; the draw turns face culling off.

**Its whole footprint,** all of it read before the pass and written back after where it
reads differently (`WriterState`, through `StateGuard`):

| State | What the pass does with it |
|---|---|
| Draw framebuffer binding | Binds near terrain's targets |
| Read framebuffer binding | Not used; the draw's leaving binds the game's framebuffer to both |
| Viewport | The targets' size |
| Depth test, depth function, depth mask | On, nearer-or-equal, written |
| Blending, alpha test, face culling | Off |
| Program | The targets' program |
| Vertex array | Distant terrain's own, which has no attributes |
| Element buffer and array buffer bindings | Not used; the draw's leaving sets both to none |
| Draw-indirect buffer binding | The frame's commands |
| Shader storage points 0 to 4, and the general binding | 0 the frame's tile records, 1 each arena page in turn, 2 distant terrain's materials, 3 biome tints, 4 near terrain's material table |
| Texture unit 6, 2D | The cover map, an 8-bit unsigned integer texture |
| Texture unit 7, 2D array | The materials' images |
| Sampler objects on units 0 and 1 | Not used; the draw's leaving sets both to none |
| Pack and unpack alignment | Not used; the draw's leaving sets both to 4 |
| Active texture unit | Not changed: textures are bound by unit directly |

It binds no uniform buffer, no transform feedback, no image unit and no atomic counter; it
changes no color mask, stencil, scissor, polygon offset or draw-buffer list, and so draws
with those as found. It writes two buffers of its own by name each call (commands and
records) and uniforms of its own program. Blend functions are read and set only by the water
layer, which this pass does not draw.

Before this the pass put back the framebuffer, the viewport, the alpha test and blending
only. It left the program, vertex array, array and element buffers and samplers 0 and 1 at
none and the alignments at 4, which is what the frame's state envelope asks of a stage
drawing where the game expects its own state; it left storage points 0 to 4, the general
storage binding and the indirect binding at none, and units 6 and 7 bound; and it took the
depth test, function and mask as it found them.

**What a test holds, and what it cannot.** `StateGuardTest` holds the rule over state made
of numbers: every listed slot reads afterwards as found, only changed ones are written, in
the list's order where one write disturbs another, and a slot not listed is not touched.
There is no recording stand-in for the driver in this tree, so nothing offline shows that
each slot reads and writes the driver's state it names, or that the list is complete. The
frame's state audit (`-Dquadrature.stateAudit=true`) reads state at stage boundaries, but in
a window of 120 frames that begins 240 frames after a world is joined, which on a cold
client is before any tile is stored: it does not see this pass draw. So the pass has a check
of its own, `-Dquadrature.distant.writerAudit=true` (`WriterAudit`): the first three times it
draws, a list of state made apart from the pass's own and wider than it (bindings on every
storage and uniform point to 7 and every texture unit to 9, the blend functions, masks,
tests and stacks, 80-odd items) is read just before and just after, and whatever reads
differently is logged, with the state found on entry once.

**The forward layers go first.** Near terrain draws its forward color into the game's
framebuffer and the same layers into the targets; where the stage is shown it then writes its
result over the whole picture, color alone, and the game's depth stays as terrain left it.
Distant terrain does the same: in a pass in which near terrain calls the writer, the forward
opaque and leaf layers are drawn in that call, into the framebuffer bound on entry and under
the same guard of state, and then the targets. The stage's result covers their color and
their depth stays for entities, translucents and the water. In a pass without a writer's
call (the shaded path off, or no targets made) they are drawn at the Mixin's place as
before. Water on the shaded path stays at the Mixin's place, after the stage and out of the
depth (21.2), since the stage does not shade it.

The first build of the registration drew the forward layers at the Mixin's place in every
pass, on the belief that the stage's result would cover them. It is the other way round: the
stage runs when the game enters its entities section and the Mixin's place is the first draw
of entities, after it. The twelfth run showed it: with the stage shown, near ground was lit
by the stage and distant ground was the forward picture, identical on every pixel to the
run with the stage off.

**The twelfth run.** The arrival tour twice on one instance, with the writer registered: the
stage off, then near terrain's stage on and shown. Neither had an error. In both the audit
read 94 items before and after each of the first three passes and found none changed, and
the targets were drawn into in every summary after the first tile. With the stage off the
picture was the eleventh run's but for the fog's nearer end. With it shown the targets held
distant terrain (albedo alike near and far) and the picture showed the fault above. Whole
frames came 66 and 67 s after the joins, 22 s after the jump of 2 km and a little over 2 s
after the steps. Distant terrain took 2.0 ms of GPU time forward and 2.3 ms in the writers'
stretch; the stage's own passes 0.28 ms.

**The thirteenth run, with the forward layers in front of the stage.** The same two runs on
a fresh instance. Neither had an error, and the audit, which now brackets the forward draw
too, again found none of 94 items changed in each of the first three passes. In every
summary after the first tile the forward layers were drawn in the writer's call; the game's
fog there and in front of entities read the same (on, linear, 384 to 20,480 blocks). With
the stage off the far-jump view is the twelfth run's on every pixel of sky and distant
ground. With the stage shown distant ground is lit by the stage as near ground is: the rows
that were the forward picture in the twelfth run now differ from the stage-off run on every
pixel. Distant water is still the forward draw's, unlit by the stage (21.1). The forward
step took 2.0 ms of GPU time as before; the writers' stretch, which now holds it and the
pass into the targets, 4.3 ms; the stage's passes 0.28 ms. Whole frames 64 to 69 s after
the joins, 21 to 22 s after the jump, 2.1 to 2.8 s after the steps.

**Emission.** Target B's green is near terrain's rule in near terrain's own text
(`KindEmission.GLSL`, included in the targets step as near terrain's stage includes it):
from the surface's kind, byte 5 of its row in near terrain's material table, and the linear
albedo this pass writes to C. The row's byte 3 is not read. The rule's worked values are
held by near terrain's `KindEmissionTest` against the Java twin; distant terrain has no copy
of its own to drift. Before this the pass wrote byte 3, which after near terrain's change
to the rule made the two sides differ at the join for every block that gives light.
No run can show the rule on distant terrain yet: Riven's preview gives stone where native
cubes hold lava, since the preview carries no emitting blocks, and a block placed in the
world is not in the preview. Until the preview carries emitters every distant face has a
kind that gives no light and B's green is 0, as it was in the twelfth run on both sides of a
desert without a lamp.

**The eleventh run, the writer alone.** Before the registration, with the stage off: the
arrival tour with the shaded path on, the frame tail and the writer's audit. The audit read
94 items before and after each of the first three drawing passes and found none left
changed. The whole frame came 117.5 s after a cold join, past the last fixed delay of 60 s,
21.6 s after a jump of 2 km, and 2.3 and 2.1 s after steps of 32 blocks. No error; distant
terrain 1.93 ms of GPU time a frame.

### 16.10 The far edge

**Where it is.** A level's square is centered on the nearest even boundary of its tiles to
the viewer (`Rings.squareStart`), not on the viewer, so that boundary is up to one tile from
the viewer on each axis and the square's sides are between the level's reach less a tile and
the reach plus a tile away. For the outermost of 7 levels at a reach of 384 that is 24,576
blocks give or take 4,096: the nearest side can be at 20,480, five sixths of the reach, at
any reach and number of levels.

**What is published.**

- `DistantHook.farthest()`, as before: the reach of the outermost level that has tiles, or
  the distance to the world's farthest side where that is nearer, along x or z. The nominal
  distance: sides lie within a tile of it.
- `DistantHook.nearestEdge()`: the smallest of the distances along x or z from the viewer to
  the four sides of the outermost selected level's square (`FarEdge.distance`). It moves with
  the viewer from pass to pass, between the reach less a tile and the reach, without a jump,
  and is a value of the pass with no generation. A fade into the sky complete by here hides
  the edge. This is the number the target writer is to hand to near terrain for the stage
  (`TerrainHook.targetsEdge`, not yet in this tree and not called).

**Two choices in it.**

- *Of the selection, not of what is stored.* While the outermost level arrives its edge is
  ragged by whole tiles, and a kept tile stands in only where one was stored. The value does
  not shrink to the next level in for that: the stage's fade then lies over sky where tiles
  are missing, which shows nothing wrong, where a value that followed arrival would move the
  fade inward and out again as levels fill.
- *The world's bound is not an edge.* A side of the square at or beyond the world's bound is
  left out, and where all four are the world's the value is the distance to the farthest of
  them. Nothing lies beyond the bound to hide, and with the bound counted a viewer standing
  beside it would have the whole world faded away from a few blocks on.

**The forward path's fog** was complete at the nominal distance, so a side nearer than that
showed through fog not yet complete (83 % at five sixths). `quadrature.distant.fogEnd` now
defaults to `FarEdge.least`, the reach less a tile, 20,480: complete before any side for any
viewer in a world without bounds. The forward picture changes a little in the far distance:
fog is denser everywhere beyond 384 blocks by the ratio of the two spans, and the last
4,096 blocks of the nominal reach are wholly fog. The shaded path writes no fog and is not
changed.

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

**When a place is whole.** One more frame a jump is taken off the clock, the first in which
the place is whole, as `travel-<jump>-whole-<ms>ms.png`, the number being the time since the
jump. Whole (`Travel.whole`) is three things at once:

- The cube source calls its view settled, `ICubeSource.settled(20)`. By Cubature's source that
  is: `quietTicks()` is 20 or more, meaning that for the last 20 client ticks no cube arrived,
  left or changed (blocks, light or biomes, a neighbor's included), the held box did not
  change, and no client light work was left queued at a tick's end; and, where the server
  states a held box, `heldBoxSent()`, meaning the server has said it sent every cube of that
  box. It says nothing of light the server computes after sending a cube, of entities, or of
  anything a renderer has built.
- The held box is this place's: its center within a cube of the viewer's on each axis. The
  center is where the server last moved the player, so in the first ticks after a jump the
  old place's box can still read as settled.
- Every tile the selection wants is stored and none is being built, which the cube source
  cannot know.

Near terrain's meshing is not asked: a place can be whole here with near columns still to
mesh, and the coverage map's counts in the summary beside the frame say how many.

The fixed delays stay, and the last of them is the ceiling: a tour is written to wait that
long at a view, and where a place was not whole by then the log says so. The whole frame is
still taken later if the viewer stays. The lab's own settle before its still captures is the
launcher's, a count of ticks fixed when an instance is prepared, and is not changed by this;
the time in a whole frame's name is what that count can be judged against.

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

**The arrival tour,** `tools/tours/distant-arrival-2485331.json`, made after the seventh run
(17.1) for an instance that settles 3,000 ticks, two and a half minutes, at each view, with
delays of 0.5, 2, 5, 12, 30 and 60 seconds: a cold join seen from 160 blocks up, so the whole
fill of the rings is caught from nothing; then, with everything stored, a jump of 2 km at the
same height, which is the jump the first tour made too early; then two steps of 32 blocks.
All four views keep one height, so the launcher makes one teleport a view.

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

### 17.2 The eighth run

Two runs under one lease on the build at 9b54e86, on Cubature a8ebb701 on both sides, which
joined and ran: the arrival tour, and the same in the level view on a twin instance. No error
in the first; in the second one line from Netty's leak detector on the client's network
thread at the join, not from this mod.

**Cold join**, seconds after distant terrain's first frame, from the log's lines 5 seconds
apart, beside the seventh run and the measurement without a game (section 19):

| | Eighth run | Seventh run | Without a game |
|---|---|---|---|
| First tile | about 35 | about 31 | 19 |
| Coarse cover: the coarsest level's 12 fill tiles | 55 | none | 34 |
| Level 0 whole | 65 | 121 to 125 | 46 |
| Levels 1 to 4 whole | 80, 85, 90, 90 | | 56 to 68 |
| All | 130 | 125 | 107 |

Cubature's pool gave 2 threads at once for the first 50 seconds and 4 after, which with the
game's own loading is the difference from the measurement.

**After a jump of 2 km** with everything stored: 316 tiles of the old place still applied half
a second later; level 0 whole within 12 seconds, all within 30. After a step of 32 blocks:
whole within 2 seconds.

**No wall and no hole** in any frame. At 60 seconds after the join: near terrain, level 0
about it, coarse ground to the horizon. Half a second after the jump, with none of near
terrain's columns meshed, coarse ground under the whole view.

**Cost.** GPU 2.3 to 2.6 ms mean at rest, as before the cover's lookup; 4.3 for a moment 12
seconds after the jump, with 408 tiles of the old place kept and 647 drawn. 764 tiles at
rest, arena 40 MiB. Fill tiles let go: 12 after the join, 56 by the end, most of them tiles
of the old place that lay inside a hole at the new one.

**Still drawn twice:** tiles kept past their ring. Five seconds after the jump the level view
shows several levels' colors mixed on far ground, kept tiles over what replaces them, for up
to 30 seconds. Not seen as a fault in the normal look.

### 17.3 The ninth run: the targets for shading

Three tours on the build at af6332c and Cubature a8ebb701, with near terrain's targets on
(`quadrature.faces`, `quadrature.gbuffer`): the near join from the ground and from above with
the shaded pass on and the targets written as images, crowns `plain`, then a far jump; the
join views again with crowns `far`; and the same with the shaded pass off, as a control. No
error in any.

**The targets hold distant terrain.** They were not refused, the pass drew into them from the
first stored tile, class alpha is 0.376 on every written pixel near and distant, and depth is
above 0 wherever something was written and 0 elsewhere.

**Just inside and just outside the join**, from above, 120 seconds after arriving (albedo is
C squared):

| Surface | Side | Albedo | Normal | Roughness | Sky | Occlusion |
|---|---|---|---|---|---|---|
| Grass, tops | near | 0.156, 0.143, 0.031 | up | 0.851 | 0.995 | 0.992 |
| Grass, tops | distant | 0.158, 0.143, 0.032 | up | 0.851 | 0.995 | 0.990 |
| Crowns, tops | near | 0.039, 0.052, 0.020 | up | 0.851 | 0.995 | 0.936 |
| Crowns, tops, `far` | distant | 0.040, 0.053, 0.021 | up | 0.851 | 1.000 | 0.999 |
| Crowns, tops, `plain` | distant | 0.116, 0.155, 0.061 | up | 0.851 | 1.000 | 0.802 |

What followed from it is in 16.8: `far` is the default, and a crown's occlusion takes near
crowns' mean. This view has no sand or stone by the join.

**The player's picture is the same with the pass on.** With it on and with it off, all else
equal, every row of terrain in both views is identical pixel for pixel; the sky's clouds
differ, and 21 pixels where an animal stood.

**Cost.** The pass adds 1.9 ms of GPU time: the frame's stage that holds both passes reads
3.98 ms with it and 2.05 without. Distant terrain's own timer ends before it.

**After the far jump** the forward pass peaked at 2.7 ms mean and 3.3 at most with 380 tiles
kept, where the eighth run reached 4.3 and 4.9: kept tiles now give way place by place. The
level view of that moment was not taken.

**The lease.** Between the first two tours another project's run took the lease in the gap;
the second and third ran as a turn of their own.

### 17.4 The tenth run: kept tiles, and the targets again

Two tours on the build at b1b9bea and Cubature a8ebb701, neither with an error: the arrival
tour in the level view, and the join views with the targets written.

**Kept tiles give way.** Half a second, 2, 5, 12 and 30 seconds after the far jump every place
shows one level's color. Where the eighth run showed several mixed on far ground there is the
coarsest level's alone until finer tiles are stored, and a tile kept from the old place shows
only where nothing wanted is shown yet. No hole. The forward pass after the jump read 2.0 to
2.7 ms mean and 3.0 at most but for one reading of 5.1.

**That run arrived slowly,** and not through this code: the cold join was not done when the
jump came at 150 seconds, and all tiles after the jump took 66 seconds where the eighth run
took 30. In both runs Cubature's pool gave one thread at once through the cold phase; in this
one each cold tile took 31 seconds on it where the eighth run's took 17. Near terrain was not
meshing and the frame was not slow. Other work on the machine is the likely cause and is not
shown.

**The targets, with the pass on the drawn-frame gate and near terrain's own table.** From the
first stored tile on, every one of the log's summaries says the pass drew. Grass and crowns
read as in the ninth run on both sides of the join, and a crown's occlusion now 0.937 beside
near terrain's 0.936. Against the ninth run's images of the same view the normal and albedo
targets differ on 0.5 % and 1.7 % of pixels, the size of the bands, where the pattern now
moves with the frame; that it moves from one frame to the next was not captured. The player's
picture is identical to the control's on every row of terrain.

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
- Tiles kept past their ring were drawn whole over whatever replaced them, which the eighth
  run's level view showed for up to 30 seconds after a far jump. They now follow the rule too,
  last in line: a kept tile draws a place only where no wanted tile of any level is shown, and
  of two kept tiles over one place, where a band was, the coarser draws (`Cover.keptDraws`,
  `keptHidden`; the fragment stage asks every level's bits for a kept tile). `CoverTest` has
  the jump: the old place's tiles kept, the new place's stored one at a time in both orders,
  every place under any stored tile drawn once and no hole at any step. Not run in a game.
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
seconds does not get shorter in proportion. Profiles with pooled builds, read by method, show
why, and it is not a stage left on one thread. In a little over half of the stack samples
only one or two of the four tile threads were at work, and in most of those the one was not
working either: it stood in `BuildPool.forEach` under `Hydro.prepare`, having handed the
missing hydrology plans to the pool and waiting for them. The pool's threads were all at work
then, in the continental outline's kernels (`ContinentalKernels.Kernel.sample`, `tube`,
`shapeScore`), reached through coast and river shape cells. So with pooled builds the cold
start is already spread over the pool, as wide as the pool is, and what it spends its time on
is sampling the outline. An earlier reading of the same stacks by class took the waiting
thread for a working one and named a stage of the drainage build; there is none.

**Proposal, for Riven's author; not built.** Each needs a decision about the generator and
none changes terrain, the builds being pure:

1. (Built in Riven's branch since, and without effect until Cubature hands a client's preview
   a pool.) On a client, let the preview's builds use a pool: the client's preview is built with
   `inputs.buildPool()`, which could be Cubature's client preview pool or a small pool of its
   own, and the sampler would not mark itself alone there. Gain as measured above. The
   server is not touched: its sampler keeps the mark.
2. Nothing in the drainage build is left to split: with pooled builds its plans already run
   on every pool thread. What would shorten the cold start further is a pool wider than
   four, which is the host's to give, or cheaper sampling of the continental outline, which
   is most of the work and is Riven's generator proper: a matter for its author, with no gain
   measured here. On a server these builds already use the pool, so nothing here adds to its
   load.
3. Hold coast forms, or what they need of drainage, in the regional store, and offer the
   store at a join: 7 to 9 seconds on the first tile with the store as it stands.

A change that only reorders which thread asks for which cell was considered and dropped: the
threads wait on one cell's build, not on each other's cells.

### 19.2 The governor's frames, measured

Cubature's client preview governor (a8ebb701, `ClientPreviewGovernor.update`, fed by
`ClientCubeSource.onRenderTick` and `governWork`) keeps the last 120 intervals from one
frame's start to the next and, once a second while a job ran, halves the jobs it lets run at
once, never below one, when the 95th percentile of them is at or over 25 ms: seven frames in
120. It adds one back at most every two seconds, only while a job waits and the percentile
is under 20 ms. Server ticks count only from an integrated server; a dedicated server's
never reach it.

In the eighth and tenth runs the pool went from 2 to 1 at once 5 to 20 seconds after near
terrain had finished meshing, and stayed there 40 to 75 seconds, while every stage of the
world pass summed to 3 to 11 ms at the 95th. So the long frames were outside the world
pass's stages; the frame log does not hold whole frame intervals and cannot say where.

`-Dquadrature.distant.frameTail=true` (`FrameTailLog`, `FrameTail`) logs once a second, by
the governor's rule over the same 120 intervals: the percentile, how many are at or over 20
and 25 ms, the worst, how many of those over 25 held a client tick and the longest such
tick, how many frames held a tick at all, the time the collectors report for the second,
and how many jobs the pool lets run. A frame costs three readings of the clock and two
array cells; nothing is allocated but the line.

**The eleventh run's cold join, by that log.** Outside bursts the percentile was 9.2 to
9.6 ms at a cap of 120 frames a second, with client ticks of about 1 ms. The cuts came from
bursts of 7 to 10 frames of 57 to 104 ms each: at 23 s (the pool from 3 to 1), 45 s (3 to
1) and 74 s (4 to 2), and one at 55 s while the pool was already at 1. In those seconds no
tile was being placed (none was stored until after the first burst), near terrain had no
mesh waiting, the world pass's stages summed to under 2 ms at the median, the collectors
reported 0 to 20 ms, and the longest client tick in a long frame was 1.2 ms, 3.9 once. Six
of the cold phase's 111 seconds had the percentile at or over 25 ms. So the long frames were
not ticks, not collector pauses as the beans report them, not near terrain's meshing and not
distant terrain's pass, and they came also with one job at once; what they were is not
shown. That nearly every long frame held a client tick says nothing: a frame over 50 ms
always spans one. The pool came back by one every two seconds each time and stayed at 4
from 78 s.

## 20. The near join's fade, distant terrain's side

Built behind `quadrature.distant.joinFade`, off by default, with near terrain's side behind
`quadrature.terrain.fade`; run with both on 2026-11-17 (the end of this section).

**The agreement.** A fragment's distance d is from the view's position along the farther of x
and z, in blocks: a square, as near terrain's columns are. The fade is (d - start) / (end -
start), held to 0 to 1. Near terrain keeps a pixel where the shared pattern's complement keeps
it at that fade; distant terrain where the pattern itself does. Unset, near terrain's band runs
from 32 to 16 blocks inside the view distance, 160 to 176 at 12 chunks, and ends at its last
meshed column.

**What distant terrain does.** Until now a fragment over a column near terrain has meshed was
discarded, and a tile wholly over such columns not drawn at all. With the fade, a fragment over
a meshed column is kept where d is past the band's start and the pattern keeps it at the fade
(`NearCover.joinFade`, and the same in the fragment stage); a covered tile is skipped only if
all of it lies nearer than the start (`NearCover.beforeJoin`). Outside the band the coverage
rule stands as it was.

**The switch follows near terrain's, by what it publishes.** Each frame distant terrain reads
`TerrainHook.fadeBandInUse()`, the band near terrain drew its own fade with, null where its
fade is off, and `TerrainHook.fadeOrigin()`, the place it measured from, which is the camera
and not, in a view from behind, the player. Distant terrain fades only while there is a band
and its own switch is on, over that band and from that place. One side alone is wrong both
ways: distant terrain alone draws its pattern over near terrain's whole ground; near terrain
alone leaves its given-up pixels open. `quadrature.distant.joinFadeBand` forces a band for
looking at this half by itself, measured from the viewer, and is a debug view.

**Where both draw the same surface.** Level 0's cells are blocks, so on plain ground the two
surfaces lie in one plane at one depth, and each pixel is kept by exactly one side: no depth
fight and no gap. Their d is the same because the place is the same.

**Where they do not.**

- *Leaves.* Near leaves are cut out and show what is behind them; distant leaves are solid in
  the far color. In the band a crown goes from cut-out to solid by the pattern: the pixels
  distant terrain keeps are solid. That is the change that happens anyway with distance,
  spread over 16 blocks.
- *Small things.* Near terrain draws plants, slabs and other blocks distant terrain leaves
  out. In the band they thin out by the pattern, and through the pixels they lose the ground
  under them shows, which distant terrain draws.
- *Edges at different depths.* Where the two sides' fragments in one pixel lie at different
  distances, at a silhouette or a step, their fades differ by the difference in d over the
  band's width. A pixel can then be kept by both, which depth settles, or by neither, which
  shows what lies behind: a block's difference over a 16-block band is one pixel in sixteen
  along such an edge.

**Water.** Distant water takes no part in the fade: over a column near terrain has meshed it
is discarded as before, band or no band. Near water is still drawn by the game's own renderer
and does not fade, so a faded distant water would lie under or over it and a pixel would have
two water surfaces. The sea bed under it does fade, being opaque ground like any other. When
near terrain draws and fades its own translucent layer, distant water can join the rule.

**Columns that wait.** A column near terrain has loaded and not meshed is not covered, so
distant terrain draws it whole, band or no band; near terrain draws nothing there to trade
pixels with. The same holds for its outermost ring of columns, which it never meshes: the
band's end is its last meshed column, and past it distant terrain is whole.

**The run.** Instance `quadrature-join-02`: Riven frozen from main at 701311b, Cubature
a8ebb701, this branch at 1b0a58d, view distance 12, both fades on with the shaded path, the
coverage map and face records; `tools/tours/distant-join-fade-2485331.json` with both fades,
then its views again in the join view. No error. Distant terrain read near terrain's band, 160
to 176 blocks, in every pass, from an origin at most 0.05 blocks from its own.

- *From above* (three views, 32 blocks apart): the join view's orange follows near terrain's
  square, 16 blocks deep. In the faded frame of each view the ground passes from near
  terrain's textured surface to distant terrain's flat color across the band with no line at
  either edge, no gap and no step in brightness; no sky shows inside the band. A frame 2
  seconds after each 32-block move equals the settled one.
- *At the ground* (three views at y 104; the tour's first height, 80, is under the ground of
  the current world): the band lies near the horizon and shows only on crowns, which go from
  cut-out to solid by the pattern as described above. No seam.
- Outside the band the faded frame and the join view of a view differ on 0.002 to 0.003 % of
  pixels, so the join view marks the faded frame's band exactly.

**Water, a shore and a cliff** (`quadrature-join-03`, the same build; views found on a terrain
atlas of the world where the band crosses each, from above and at the ground):

- *Open water*, the band all sea: the faded frame shows nothing at the band. The join view
  shows the sea bed's fade through the water as a violet band; the water surface does not
  take part, as described above.
- *A shore*, the band half land and half water, along a cliff above the sea: no hole and no
  line where the band runs down the cliff face or meets the water.
- *A cliff wall facing the viewer* lies at the band's distance as a whole, and all of it shows
  the pattern: near terrain's textured blocks among distant terrain's flat colors, a mottled
  wall and not a seam. How much it shows depends on how near a material's flat color is to
  its image's mean. No pixel open to the sky or to farther ground on the wall or along its
  top.

**In motion** (`quadrature-join-04`, a client-side glide of 16 blocks sideways with an 18-degree
turn over 4 seconds above the band, a frame each tick): the band moves with the ground, with no
line, popping or crawl in consecutive frames. Per pixel and frame, the band changes by 24.7
while moving, against 35.7 for textured ground just nearer, and by 0.16 while holding still,
against 0.25: the pattern fixed to the screen adds no flicker. The first frame equals the
settled still of the same pose.

## 21. Distant water as inputs for shading

Section 14.4 and 16.4 say what the shader wants of water and that distant water has it; this
is how it is to be handed over. The proposal comes first as it was put; 21.1 is what near
terrain, the shader thread and distant terrain agreed on it, and 21.2 the one step built.

**Today.** Distant water is the third draw layer of the forward pass: flat top faces a ninth
of a block under the block's top, no side faces, the biome's water tint times a flat color,
sky light of 4 bits a face, lit and fogged by this mod's own sums, blended over what is drawn
by the mean alpha of the game's water image. It writes depth as the opaque layers do. Over a
column near terrain has meshed it is discarded whole, and it takes no part in the join's fade
(section 20). On the shaded path it is not drawn into the targets, rightly: a target holds
one surface a pixel and water needs two, itself and the bed under it. Near water is still the
game's own draw.

**What a water surface has to give,** from 14.4: that it is water (kind 100), a flat upward
normal, the tint apart from any image, sky light at the surface, cover, and its depth, kept
out of the opaque depth so that the opaque color and depth under it can be read.

**Three ways.**

1. *A water target.* After the opaque pass and before shading, the water layer is drawn into
   a small set of its own that near terrain makes and publishes beside A to D: one color
   target (tint in color, sky light and cover packed in alpha, the kind implied by the
   target) and a depth of its own, the size of the window. The normal is up and is not
   stored. The shader reads opaque depth and water depth side by side. Near water, when near
   terrain draws it, writes the same set, and nearer-or-equal depth settles near against far.
   Cost: one more pass over the water layer's draws, which are few (tops only, coarse beyond
   ring 0); 8 bytes a pixel of targets; a new published set and its drawn-frame gate, as for
   A to D. Limit: one water surface a pixel, the nearest, so water seen through water (a lake
   behind a waterfall's edge) loses the farther. Translucents other than water do not fit.
2. *A published list.* Distant terrain publishes what it would draw: the water layer's
   indirect commands and pages, the buffers' names, the vertex stage's source and uniforms.
   The shader thread's stage binds them and draws water with a fragment stage of its own,
   after shading. Cost here: none in the frame, since nothing is drawn twice. Cost overall:
   the vertex stage's layout (face records, origins, masks, the cover rule's uniforms)
   becomes a contract another project compiles against, and every change to a record or to
   the cover rule is then a change for two projects. Nothing is limited to one surface.
3. *Forward, as now, until near terrain owns translucents.* Distant water stays this mod's
   forward draw, moved after shading on the shaded path, reading the lit opaque color and the
   opaque depth near terrain is to publish (ARCHITECTURE 6.6), with depth writes off. Its
   look is this mod's and not the shader's: no waves, no reflection, its own absorption or
   none. When near terrain draws its translucent layer, with whatever inputs or hooks that
   takes, distant water follows the same route. Cost: nothing new now but the depth mask and
   the place in the frame; near and far water do not shade alike until then.

**Recommended:** the third now and the first as what it turns into. Shading does not exist
yet and near water is still the game's, so anything built today for the first or second way
would be built against nothing that can check it; the ninth and tenth runs showed how much
the opaque pass needed near terrain's real targets to be got right. The one thing worth doing
at once is small and wanted by every way: stop distant water writing depth on the shaded
path and draw it after the opaque pass, so the opaque depth is the bed's. Of the two real
deliveries the first is preferred: it keeps the record layout private, costs one cheap pass,
and gives near and far water one shape, which is what makes them shade alike. The second is
kept only if the shader must have more than one water surface a pixel.

### 21.1 Agreed, 2026-11-16

**Who draws what.** Near terrain will in time draw translucent terrain itself, and water as
stored inputs: a small water set written by near and distant water alike and shaded by a
water pass of the shader's, inside the stage and before its display pass, reading the lit
opaque radiance and the opaque depth. Other translucents stay forward. Distant terrain gets
no second lighting path for water.

**The water set, as drafted.** Near terrain makes and publishes it beside A to D, with the
same drawn-frame gate, when it takes over near water.

| Part | Holds |
|---|---|
| Color, 8 bits a channel | The tint in red, green and blue. Alpha: sky light in the high 4 bits, cover in the low 4 |
| Normal, two channels | Written by near water only; cleared means up, which is all distant water has |
| Depth | Water's own, reversed as the others, cleared to 0 for no water |

- One water surface a pixel, the nearest. The depth of water is the water depth against the
  opaque depth.
- Cover is the share of the pixel's cell that is water, for shores at coarse levels, and not
  the water image's alpha: the stage works out transparency itself.
- 4 bits of sky light are enough.
- A flag for flowing or still water may be added by the shader thread.

**Cover on this side.** Today a water material's cover is the mean alpha of the game's water
image, one value for all water. A tile does not hold the share of a cell that is water: a
column at any level is one column of runs from the sampler, taken as the whole cell, so a
cell is water or it is not, and a shore at level 5 moves in steps of 32 blocks. To give the
share, a coarse tile's build would sample each cell more than once, 2 by 2 or 4 by 4 columns
(four or sixteen times the sampling of a coarse tile, which is most of its cost), or Riven's
preview would answer it for a cell from what it already knows of the water's outline; and
the face record would carry 4 more bits on water faces. Until then every distant water face
writes full cover. Which of the two, and from which level, is open.

**Far water.** Past the distance where the bed no longer reads through the water, about 512
blocks, switched at one of the levels' boundaries so that the fade between levels covers it,
water is written as an opaque surface into A to D: kind 100, albedo the tint undarkened,
normal up, sky light as usual. Two conditions: the bed under it is not written there, and
the distance is one number shared by all three, published by distant terrain beside
`DistantHook.farthest()` for the shader to read. Not to be switched on until the stage
shades water.

**Side faces.** A waterfall's wall or a river's step missing at a distance is an accepted
difference. Later, perhaps: side faces at level 0 where the step is more than a block.

**The frame today.** Opaque and cutout terrain, the shading stage (lighting, then display
into the game's picture), entities, the game's translucents. After the stage the picture is
in display space: nothing of distant terrain's can refract or sample lit color against it.
So for now distant water stays forward, after the opaque layers, writing no depth (21.2).

**What waits on whom.**

| What | Waits on |
|---|---|
| The water set made and published | Near terrain, when it draws near water |
| Distant water written into the set | That, then distant terrain: one pass over the water layer |
| The water pass | The shader thread, once the set exists |
| The share of a cell that is water | Distant terrain, with Riven's preview if it is to answer it |
| Far water into A to D, and the published distance | The stage shading water; then distant terrain |
| Near water fading at the join by the band and pattern | Near terrain, when it draws near water |

**Water at the join, until then.** Near water is the game's and runs whole to the view
distance; distant water must not show where the game's water does. The rule here: a distant
water fragment is dropped over a column near terrain's map calls meshed, band or no band,
and drawn whole elsewhere. What that gives, by the state of a column:

- *Meshed by near terrain, the game's water drawn:* one surface, the game's, in the band as
  out of it. This is nearly all of the near field once it has settled.
- *Meshed by near terrain, the game's water not yet built:* neither draws, and the bed shows
  dry until the game builds its translucent layer there. The game builds that layer on its
  own schedule, which near terrain's map does not see.
- *Loaded and not meshed by near terrain:* distant water draws whole. If the game has built
  its water there, both draw, one blended over the other at the same level. Such columns
  are those that wait while a place arrives, and the outermost ring of loaded columns,
  which near terrain never meshes. Whether the game draws water in that outermost ring was
  not looked at in a run. The lighter patches of water at near terrain's outer edge in the
  fourth and fifth runs lie where this would show; they were put down to near terrain's
  drawing of water then, and which it is has not been shown.
- *Not loaded:* distant water alone.

So the rule gives one water surface over every meshed column the game has built, and has a
case for both and a case for neither around it. Both follow from one thing: the game's water
goes by what the game has built, and the map by what near terrain has meshed. A test by
column loaded, and not meshed, would move the outermost ring from both to one, and turn
waiting columns from both into neither wherever the game is the slower. Neither test is
right until something says where the game's water is drawn, or near terrain draws it. Not
changed; to be looked at in the join run, which shows that ring.

### 21.2 Built: water after the targets, out of the depth

On the shaded path only (`quadrature.distant.shaded=on`): the forward draw holds the opaque
and leaf layers, the targets are written, and the water layer is then drawn alone, blended
as before, with depth writes off and the mask put back as found. The opaque depth under
distant water is then the bed's. This holds in every frame of the shaded path, also one in
which the targets were not drawn. With the shaded path off, one draw holds all three layers
with the same state as before; that code is not touched. The pass's own timer ends before
the targets are written and so no longer counts the water on the shaded path. Not yet run in
the game.
