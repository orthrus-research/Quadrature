# Verification

How rendered frames are captured and compared. This describes what exists; requirement 13
in [REQUIREMENTS.md](REQUIREMENTS.md) states what verification must eventually cover.

## Capturing

Frames come from Riven's launcher (`../Riven`, `tools/runtime.py`), which Quadrature reuses
instead of duplicating:

1. `prepare --name <fresh-name> --seed <seed> --scale <scale>` builds an instance and
   records its inputs; `play` refuses the instance if they change. Adding
   `--no-distant-horizons` leaves Distant Horizons out, so frames hold near terrain only.
2. `play --name <name> --tour tools/tours/<tour>.json` starts a server and a client,
   teleports through the tour's views, saves one screenshot per view, and closes both.
   A tour fixes the time of day and the weather with its first view's commands.

Each run's frames land in a timestamped directory named by view.

An instance that runs Quadrature is prepared with `--client-mod` naming Quadrature's built
JAR and, for own terrain, `--cubic-artifact` naming the Cubature JAR in Quadrature's `pins/`:
the one `platform.json` pins by commit and hash and the build compiles against. The game
then runs the JAR the build read, and the instance records its hash.

## Comparing

`tools/frames.py` needs the packages in `tools/requirements.txt`.

```sh
python tools/frames.py compare REFERENCE CANDIDATE --profile tools/comparison/repeat.json --out OUT
python tools/frames.py measure RUN_A RUN_B [RUN_C ...]
```

`compare` matches frames by file name and exits 0 only if every frame passes. A pixel
counts as changed when any channel differs by more than the profile's `channelThreshold`;
a frame passes when its changed fraction and its mean absolute difference are within the
profile's limits. A missing, extra or resized frame fails, and so does an empty comparison.
With `--out` it writes `report.json` and, for each failed frame, the reference in gray with
changed pixels in red.

`measure` reports the differences between repeated captures without judging them. It is
how a profile's limits are set: a limit below the noise of two identical runs cannot pass.

| Profile | Use |
|---|---|
| `tools/comparison/exact.json` | Frames must be identical |
| `tools/comparison/near.json` | Near terrain only, no Distant Horizons: 0.1% changed, mean 0.05 |
| `tools/comparison/repeat.json` | With Distant Horizons, settled: 2% changed, mean 1.0 |

## Measured repeatability

Measured 2026-11-13: the forest tour (`forests-2485331`, 17 views, 1280 by 720) captured
three times on each of two fresh instances (seed 2485331, scale 4, vanilla near renderer),
channel threshold 8.

| Instance | Pair | Identical frames | Worst changed fraction | Worst mean difference |
|---|---|---|---|---|
| Near terrain only | Run 1 against run 2 | 3 of 17 | 0.93% | 0.116 |
| Near terrain only | Run 2 against run 3 | 13 of 17 | 0.055% | 0.018 |
| With Distant Horizons | Run 1 against run 2 | 0 of 17 | 21.9% | 11.05 |
| With Distant Horizons | Run 2 against run 3 | 1 of 17 | 1.67% | 0.73 |

What changed in each case:

- **Distant terrain, first run.** Views with a horizon changed by 1.8% to 21.9%: distant
  terrain was coarse placeholders in run 1 and refined in run 2. A capture fires after a
  fixed number of ticks, before refinement finishes; later runs load it from disk.
- **Distant terrain, settled.** Up to 1.67% still changes on the horizon. The source has
  not been identified.
- **Near terrain, first run.** One view (`16-temperate_rainforest-near`) changed by 0.93%:
  patches of ground under the canopy lit differently. The same 0.9297% appeared on both
  instances between runs 1 and 2 and was gone between runs 2 and 3. The two instances'
  run 1 frames are pixel-identical to each other, as are their run 2 frames, so the
  first-run state is deterministic; in every changed pixel run 1 is the darker. Light on
  freshly generated terrain differs there from light after a save and reload. Every other
  view changed by 0.086% at most.
- **Near terrain, settled.** One view changed by 0.055%; the rest by nothing, or by a few
  pixels under the threshold.

What this establishes:

- The capture process repeats. Without distant terrain, a settled instance reproduces
  13 of 17 frames exactly.
- The first run on a fresh instance is not a valid reference, with or without distant
  terrain. Capture a tour once to settle the instance and discard that run.
- `near.json` passes run 2 against run 3 on the near-terrain instance and fails run 1
  against run 2 on view 16. `repeat.json` passes run 2 against run 3 with Distant Horizons
  and fails run 1 against run 2 on nine frames.

## Quadrature against the vanilla reference

Measured 2026-11-13, at the commit that added the timed baseline graph. Quadrature was
installed from its JAR on a fresh instance prepared like the near-terrain reference (seed
2485331, scale 4, no Distant Horizons), the forest tour was captured twice, and the second
run was compared with the reference's settled run under `near.json`.

| Result | |
|---|---|
| Frames passing | 17 of 17 |
| Pixel-identical frames | 6 |
| Worst changed fraction | 0.039% (`07-boreal_forest`, the same view that differs between two vanilla runs) |

At this commit Quadrature observes the game's world pass and draws nothing, so this shows
that observing it does not change the frame. Stage times from that run, at the last view,
medians: opaque terrain 0.73 ms on the CPU and 2.55 ms on the GPU; translucent terrain
0.12 ms and 0.31 ms; all stages together 1.09 ms and 2.89 ms.

## A stage of Quadrature's own

Measured 2026-11-13 on one instance prepared like the near-terrain reference, with reversed
depth on, the forest tour captured after a settling run, under `near.json`.

| Comparison | Result |
|---|---|
| Probe drawing into its own framebuffer, against no probe | 17 of 17 pass; 16 identical, one at 0.077% |
| Probe also drawing into the frame: reversed against conventional depth | 16 of 17 pass, worst 0.042%; one view at 1.6%, where the conventional run shows two coplanar surfaces fighting |

## Own terrain against the game's terrain

Own terrain is scored against a game-drawn run of the same instance, not against the
near-terrain reference instance, because light around some terrain differs between
instances prepared identically. In `07-boreal_forest` a tree's trunk and the grass at its
foot are 1.6 times brighter in 5 of 24 instances than in the other 19, about two sky-light
levels; an instance shows the same value in every run, in the game's own draw as in
Quadrature's. A game-drawn run of a bright instance fails that view against the reference
at 0.31% and differs in no other view by more than 0.013%. The cause is not established;
it lies in the world's light, which is Cubature's.

Measured 2026-11-15 on a fresh instance prepared like the near-terrain reference, with the
Cubature build that carries the cube source. The forest tour was captured four times: the
game's terrain to settle the instance, the game's terrain again, `-Dquadrature.terrain=own`,
and own terrain drawing every meshed cube in the view (`-Dquadrature.terrain.visibility=all`).
The third run is compared with the second under `near.json`.

| Result | |
|---|---|
| Frames passing | 17 of 17 |
| Worst changed fraction | 0.0624% (`07-boreal_forest`, 452 of its 575 changed pixels on the river) |
| Changed pixels, all 17 views | 1,680, of which 1,228 lie outside the river |

| View | Changed | View | Changed | View | Changed |
|---|---|---|---|---|---|
| 01 | 0.0077% | 07 | 0.0624% | 13 | 0.0125% |
| 02 | 0.0022% | 08 | 0.0041% | 14 | 0.0013% |
| 03 | 0.0078% | 09 | 0.0059% | 15 | 0.0214% |
| 04 | 0.0056% | 10 | 0.0015% | 16 | 0.0059% |
| 05 | 0.0154% | 11 | 0.0092% | 17 | 0.0144% |
| 06 | 0.0042% | 12 | 0.0009% | | |
Three things the game does are reproduced to reach this:

- **The light map's filter.** The game restores the light map's linear filter inside the
  draw Quadrature replaces, so Quadrature sets it itself; without that, faces flickered
  between two light levels and up to 6.8% of pixels differed.
- **When a cube is meshed.** Beyond 24 blocks from the player the game meshes a cube only
  once the six cubes sharing a face with it are loaded, and a cube that cannot be meshed
  keeps the mesh it has. Without the rule Quadrature drew terrain at the horizon that the
  game does not, and 11 of 17 views passed against the reference instance.
- **The chunk matrix.** After moving to a chunk the game multiplies by a matrix each render
  chunk carries: a scale of 1.00000095 and an offset of 7.62939453e-06 on each axis. It
  moves a vertex by at most 2.3e-05 blocks, a few thousandths of a pixel on a face one and
  a half blocks away, which is enough to carry a boundary between two rows of a texture
  across a pixel center. Without it 14 of 17 views passed, 9,580 pixels differed, and the
  differences were rows one pixel high, up to 433 pixels long, where Quadrature showed the
  game's row above. Quadrature applies the matrix while the vanilla image is the reference.

What remains outside the river is single pixels with no direction: as many match the
game's image moved one pixel up as down, left as right; on the instance measured when the
matrix was added, 935 of 1,251 changed pixels had no changed neighbor. In a test that let
the driver compose the same matrices and used the fixed-function transform, 465 pixels
differed, 375 of them on the river. The rest is therefore attributed to composing the
matrices in single precision outside the driver and multiplying by them in two steps in
the shader. That attribution comes from the code, not from a measurement.

### The river in view 07

The river in `07-boreal_forest` is the game's translucent terrain, drawn by the game in
every run. Inside a box around it (x 1000 to 1279, y 540 to 719, 5.5% of the frame) two
captures differ by anything from nothing to 1,158 pixels, and the amount does not follow
what was changed between them:

| Pair, one instance each | Changed in the box | Changed elsewhere in the view |
|---|---|---|
| Game against game, four instances | 46, 202, 398, 497 | 6 to 203 |
| Game against own terrain, as committed, three instances | 3, 452, 1,158 | 111 to 164 |
| Own terrain against own terrain, one setting changed, four instances | 0, 10, 127, 228 | 5 to 415 |

At 1,158 the view fails `near.json` at 0.143% while its terrain differs by 0.019%. The
cause is not established. The view is scored whole; a run in which it fails on the river
alone is reported as failing, with the count inside and outside the box.
### What a view sees

What is drawn is found once per view per frame by a search outward from the cube the eye
is in, through the faces of cubes that see each other ([ARCHITECTURE.md](ARCHITECTURE.md),
section 6.4). Set beside the chunks the game selected, on settled frames of the run above,
121 times across the tour:

| | With the search | Every meshed cube in the view |
|---|---|---|
| Cubes drawn, median per frame | 383, the game 365 | 1,145, the game 400 |
| Drawn by Quadrature and not by the game, all comparisons | 446 | 90,583 |
| Draws per settled frame, average and most | 963 and 1,370 | 2,243 and 3,631 |
| Opaque terrain at the last view, CPU | 0.74 ms | 1.26 ms |
| Opaque terrain at the last view, GPU | 2.27 ms | 2.48 ms |

The game's own draw took 0.65 ms on the CPU and 2.36 ms on the GPU at that view. On an
earlier instance the search's draw took 0.54 ms against the game's 0.69 ms. All were timed
with the comparison running.

- **The frames agree.** The two own-terrain runs differ in no view by more than 0.0275%,
  and outside the river by no more than 0.0075%; four views are pixel-identical and two
  more have no pixel over the threshold. The cubes the search leaves out change no pixels.
- **What the search still draws beyond the game.** 197 of the 446 because none of the
  cube's six neighbors was reached by the game, 88 where the game was blocked from some
  neighbors by line of sight and from the others by direction, 87 where the game would have
  had to travel back the way it came, 60 where every reached neighbor blocked the line of
  sight, and 14 whose box lies just outside the game's frustum. The search admits ways on
  that the game's does not; none of these cubes changes a pixel.
- **What the game draws and Quadrature does not.** One cube in one comparison of 121, in
  the second a teleport lands: the cube beside the camera, which the cube source did not
  report as loaded while the game still held a mesh for it. No search can draw it. Every
  other comparison has none.
- **The search costs** 0.25 ms when it runs, and is kept while neither the view nor any
  cube's links change: 29,498 of 43,621 frames reused the last one. Finding the face links
  adds nothing measurable to meshing: 1.15 ms a cube.
- **A second view.** A view along the sun's direction holding what the player's view sees
  is found every frame and not drawn: 1,914 cubes on average, 2,315 at most, in 0.15 ms.
### Diagnostics

Own terrain carries five switches for finding where a frame differs. Each is a system
property, read once, and costs nothing when unset.

| Property | Effect |
|---|---|
| `quadrature.terrain.compare=true` | On one settled frame in 240, logs the cubes Quadrature draws beside the chunks the game selected; for each cube only Quadrature draws, which of the game's conditions left it out; for each cube only the game draws, whether a mesh is held, whether the cube is loaded and whether the view sees it |
| `quadrature.terrain.visibility=all` | Quadrature draws every meshed cube in the view's volume and reach, with no search |
| `quadrature.terrain.gameSelection=true` | Quadrature draws only the cubes the game selected |
| `quadrature.terrain.gameSelection=inverse` | Quadrature draws only the cubes the game did not select |
| `quadrature.terrain.probeRect=x0,y0,x1,y1` | On one settled frame in 240, logs the nearest cubes holding faces in that rectangle of a captured frame, with each face's position, facing and light, and whether the held mesh is identical to one made afresh from the cube source. Add `,width,height` when the capture's size is not the viewport's |
| `quadrature.terrain.probePoints=x,y;x,y` | With `probeRect`: logs the color and depth the frame holds at those pixels after each opaque layer |

A settled frame is one with no cube waiting to be meshed.
## Limits

- The harness cannot wait for a settled state; it relies on discarding the first run.
- The first-run lighting difference, the light that differs between instances, the river
  in view 07 and the settled horizon difference are unexplained.
- One tour, one machine (RTX 3060), one resolution.
- Captured frames and references are kept out of the repository.
