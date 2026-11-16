# Distant terrain

Status: the part with no graphics in it is built and tested, in `core`
(`cc.orthrus.quadrature.core.lod`): the run tile, the run mesher, the face record, the ring
selection, the schedule and the draw list. The first frames are written and compile, in
`cc.orthrus.quadrature.distant` (section 8): sampling, materials, an arena and the draw.
**None of that has run in a game.** No driver has compiled its shader and no game has applied
its Mixin; section 9 is the plan for the first run. Section 7 of
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
- The block images: materials are flat colors, and leaves are drawn solid.
- Water's side faces: a water tile beside lower ground shows no wall of water.
- Detail runs tinting the top under them.
- The overlay of edited terrain, and with it any use of the exposure rule.
- Cubature's client work executor. Until it exists the tasks that sample and mesh run on a
  pool of distant terrain's own (section 8), against the rule that Quadrature starts no
  threads for terrain work.
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

Records 6 to 9 below belong to the first frames (section 8).

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

Off unless `-Dquadrature.distant=own`, which needs `-Dquadrature.terrain=own`. Written and
compiled; not yet run.

| Setting | Default | |
|---|---|---|
| `quadrature.distant.reach` | 384 | How far ring 0 reaches, in blocks; a multiple of 128 |
| `quadrature.distant.levels` | 7 | Levels 0 to 6, which reach 24.5 km |
| `quadrature.distant.world` | none | Half the side of the world's square in blocks; no tile is built outside it |
| `quadrature.distant.threads` | 2 | Threads that sample and mesh, 1 to 8 |

**Source.** The terrain preview comes from Cubature's cube source (`terrainPreview()`). It is
looked for once a frame: the source has one listener, which near terrain holds, so its
changed event is not used. A new world, or a preview that came or went, lets every tile go
and refuses, by a generation number, whatever is still being built. A withdrawn preview's
exception ends a task quietly.

**Building.** A task samples a tile's 66 by 66 columns, each at its cell's middle with the
cell's width as its footprint, joins the buried strata, meshes, and leaves faces and tints in
one buffer on a queue. The client thread stores finished tiles for at most a millisecond a
frame. `TileSchedule` hands tiles out in the selection's order, coarsest level first and
nearest first, at most four at a time for two threads. A tile that leaves every ring is
dropped if it was waiting, told to stop at its next row if it is being sampled, and refused
if it arrives. A stored tile that leaves the rings is kept, and drawn, until the tiles of
the selection over the same ground are stored, so ground does not turn empty while its
replacement is built; for that long two levels are drawn over one another there.

The threads are a stand-in. Quadrature's rule is that terrain work runs on Cubature's
executor; Cubature's client executor does not exist yet, so distant terrain keeps a pool of
its own, at the lowest priority, until it does.

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
draw layers: opaque with the skirt, leaves (solid for now), water (an opaque tinted color
for now).

A tile is not drawn when all of it lies in the box near terrain covers, when the view's
volume misses its bounds, and, group by group, when its faces turn away from the eye. Faces
are not culled by winding.

**Where it is drawn, and depth.** At the point where the game goes from its opaque terrain
layers to its entities, after near terrain, with the matrices the driver holds for the
world. Depth state is left as the game set it, so the draw is correct with reversed depth
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
distance sets it. The game's fog distances are not touched.

**What near terrain covers.** Fragments inside a box round the camera are discarded: the
loaded cubes' reach on each axis less one cube, since near terrain meshes a cube only among
loaded neighbors and so never the outermost. It is a box, not a column: above and below the
vertical reach distant terrain shows.

**Light.** Sky light and corner occlusion from the record, the day's brightness, and a fixed
shade for each direction. No fog.

**What this simple rule will show.**
- A hard change at the box's edge from textured, lit near terrain to flat colors.
- The game's fog on near terrain's far edge in front of unfogged distant terrain.
- Gaps where a cube inside the box is not yet loaded or meshed, and at the box's edge if
  the cube arithmetic is off by one from what near terrain meshes.
- Empty ground wherever a tile has not arrived: on joining, most of the view for the first
  minute or more in a 32 km world.
- No block light, no shadows, no water surface, leaves as solid blocks.

**Log.** With `-Dquadrature.frameLog=true`, every five seconds: tiles wanted and stored by
level, being built, kept past the rings, resident; tiles, faces and draws of the last frame;
GPU time from timestamp queries and the client thread's time; tiles built, sampled and
meshed milliseconds a tile, stored, abandoned, failed, refused, let go; the arena's pages
and bytes; materials. The frame log's "opaque terrain" stage includes distant terrain's
draw. With `-Dquadrature.glDebug=true` the driver's messages are logged as near terrain's
are, marked "during distant terrain".

## 9. The first run

Not done. One GPU turn under Riven's lease, two tours.

**Builds**, each from its clean branch head, none needing the lease:

```
# Cubature feat/lod-client-sampler (802bab6d): the pinned jar already stands in
#   <Quadrature>/pins/Cubature-1.12.2-0.1.4.0-cleanroom.jar, sha256 f73ed467...788c
# Riven feat/lod-client-sampler (its lock pins the same jar):
python tools/build_riven.py --freeze lod-first-frames --no-daemon
# Quadrature feat/lod:
./gradlew build --no-daemon
```

**Instance**, from the Riven checkout of that branch, without Distant Horizons, with a
long settle before each capture (3,600 ticks, three minutes) so tiles can arrive:

```
python tools/runtime.py prepare --name quadrature-distant-01 --seed 2485331 --scale 4 \
  --no-distant-horizons --capture-ticks 3600 \
  --artifact .local/frozen/lod-first-frames/riven-cleanroom.jar \
  --cubic-artifact <Quadrature>/pins/Cubature-1.12.2-0.1.4.0-cleanroom.jar \
  --client-mod <Quadrature>/build/libs/quadrature-0.1.0-dev.jar
```

**Tour.** Three views close together, so the rings and the regional data persist from one
to the next; the landmass tour's first place:

```json
[
  {"label": "overview_down", "x": -4599.5, "y": 340, "z": 13200.5, "yaw": 180.0, "pitch": 45,
   "commands": ["time set 6000", "gamerule doDaylightCycle false", "weather clear 100000",
                "execute {player} -4600 100 12600 riven"]},
  {"label": "overview_horizon", "x": -4599.5, "y": 340, "z": 13200.5, "yaw": 180.0, "pitch": 8},
  {"label": "overview_east", "x": -4471.5, "y": 340, "z": 13200.5, "yaw": 270.0, "pitch": 12}
]
```

**Runs**, each `play --name quadrature-distant-01 --tour <that file>` with
`JAVA_TOOL_OPTIONS` set, and the client's `logs/latest.log` copied after each:

1. Reference, distant off: `-Dquadrature.terrain=own -Dquadrature.reversedDepth=true
   -Dquadrature.frameLog=true`.
2. Distant on: the same, and `-Dquadrature.distant=own -Dquadrature.distant.world=16384
   -Dquadrature.glDebug=true`.

About 12 minutes each (startup, three views of three minutes, shutdown): 25 to 30 minutes
under the lease.

**Capture:** the three frames of each run, both logs, and GPU memory sampled once a second
beside run 2.

**Pass, for first frames:**

1. The game starts and finishes the tour in both runs; no Mixin failure in the log.
2. Run 2's log says distant terrain is on and is sampling the preview, and never that it
   failed; no shader error. On the client it must say Riven's preview was built from the
   server's definitions, not declined.
3. Tiles arrive: stored counts rise at every level through the run, failed 0, refused 0.
4. No driver message of error severity during distant terrain.
5. In run 2's horizon views terrain shows beyond near terrain where run 1 shows sky or fog,
   in plausible colors and the right place: the coast and mountains the terrain atlas shows
   for those directions.
6. Inside the near box the two runs' frames agree within `near.json`, apart from pixels
   where distant terrain shows through gaps. A difference there is a depth or far-distance
   fault and fails the run.
7. Recorded, not judged: GPU time of the draw, faces and draws a frame, arena bytes, sample
   and mesh times, the time from joining to the coarsest level stored.

**Likely first failures**, in the order to look: the shader failing to compile (the log
names the line; distant terrain turns itself off and the run goes on); nothing drawn
because the preview is null on the client (the join message or the factory); faces in the
wrong place or mirrored (the corner tables in the vertex stage); a ring of sky at the box's
edge (the one-cube allowance).
