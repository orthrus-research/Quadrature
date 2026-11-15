# Distant terrain

Status: the part with no graphics in it is built, in `core` (`cc.orthrus.quadrature.core.lod`):
the run tile, the run mesher, the face record and the ring selection, with tests. Nothing draws
yet: there is no arena, no program and no stage for distant terrain, and nothing here needs
the game. Section 7 of [ARCHITECTURE.md](ARCHITECTURE.md) states the structure this fills in.

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

- The distant arena, the program that expands records, the draw after near terrain, and the
  stage that owns them.
- Crossfade between levels, the coverage map that hands columns to near terrain, the light's
  view of distant tiles.
- Water's side faces: a water tile beside lower ground shows no wall of water.
- Detail runs tinting the top under them.
- The overlay of edited terrain, and with it any use of the exposure rule.
- Filling tiles: the sampler arrives through Cubature's cube source (its terrain preview),
  and the tasks that sample and mesh run on Cubature's executor, which does not exist yet.

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
