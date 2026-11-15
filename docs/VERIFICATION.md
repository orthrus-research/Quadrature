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

Measured 2026-11-14 on a fresh instance prepared like the near-terrain reference, with the
Cubature build that carries the cube source. The forest tour was captured three times: the
game's terrain to settle the instance, the game's terrain again, then
`-Dquadrature.terrain=own`. The third run is compared with the second under `near.json`.

| Result | |
|---|---|
| Frames passing | 17 of 17 |
| Worst changed fraction | 0.0225% (`15-temperate_rainforest`) |
| Changed pixels, all 17 views | 1,251, of which 935 have no changed neighbor |

| View | Changed | View | Changed | View | Changed |
|---|---|---|---|---|---|
| 01 | 0.0081% | 07 | 0.0124% | 13 | 0.0129% |
| 02 | 0.0022% | 08 | 0.0041% | 14 | 0.0013% |
| 03 | 0.0080% | 09 | 0.0056% | 15 | 0.0225% |
| 04 | 0.0058% | 10 | 0.0015% | 16 | 0.0059% |
| 05 | 0.0160% | 11 | 0.0092% | 17 | 0.0151% |
| 06 | 0.0042% | 12 | 0.0010% | | |

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

What remains is single pixels with no direction: as many match the game's image moved one
pixel up as down, left as right. In a test that let the driver compose the same matrices
and used the fixed-function transform, 465 pixels differed, 375 of them on the river in
`07-boreal_forest`. The rest of the 1,251 is therefore attributed to composing the matrices
in single precision outside the driver and multiplying by them in two steps in the shader.
That attribution comes from the code, not from a measurement. The river is the game's
translucent terrain and differs between two game-drawn runs of one instance, by 0.055% of
that view.

### What Quadrature draws that the game does not

The game finds what to draw by a search outward from the camera's chunk, through chunks
whose geometry lets a line of sight pass. Quadrature draws every meshed cube in the view
frustum. Set side by side on settled frames of the run above, 127 times across the tour:

| | |
|---|---|
| Cubes the game draws and Quadrature does not | None |
| Cubes drawn, median per frame | Quadrature 1,145, the game 400 |
| Left out by the game: none of the cube's six neighbors was reached | 93.8% |
| Left out: every reached neighbor's geometry blocks the line of sight | 4.2% |
| Left out: blocked by line of sight from some neighbors and by direction from the others | 1.4% |
| Left out: reaching it would mean the search travelling back the way it came | 0.7% |

The cubes the game leaves out change no pixels. With Quadrature drawing only the cubes the
game selected, on the same settled instance, 14 views are pixel-identical to Quadrature
drawing every cube; `03-cold_rainforest` differs in 3 pixels and `07-boreal_forest` by
0.017%, on the river. In `15-temperate_rainforest` the camera's pitch differed between the
two captures, so that view says nothing. The cubes cost draws: over the tour's settled
frames Quadrature issued 2,272 draws a frame on average and 3,628 at most, against 930 and
1,366 for the selected cubes alone. At the last view opaque terrain took 1.92 ms of CPU
time with every cube drawn and 0.69 ms with the selected cubes, against 0.77 ms for the
game's own draw; these three were timed with the comparison above running.### Diagnostics

Own terrain carries four switches for finding where a frame differs. Each is a system
property, read once, and costs nothing when unset.

| Property | Effect |
|---|---|
| `quadrature.terrain.compare=true` | On one settled frame in 240, logs the cubes Quadrature draws beside the chunks the game selected, and for each cube only Quadrature draws, which of the game's conditions left it out |
| `quadrature.terrain.gameSelection=true` | Quadrature draws only the cubes the game selected |
| `quadrature.terrain.gameSelection=inverse` | Quadrature draws only the cubes the game did not select |
| `quadrature.terrain.probeRect=x0,y0,x1,y1` | On one settled frame in 240, logs the nearest cubes holding faces in that rectangle of a captured frame, with each face's position, facing and light, and whether the held mesh is identical to one made afresh from the cube source. Add `,width,height` when the capture's size is not the viewport's |
| `quadrature.terrain.probePoints=x,y;x,y` | With `probeRect`: logs the color and depth the frame holds at those pixels after each opaque layer |

A settled frame is one with no cube waiting to be meshed.
## Limits

- The harness cannot wait for a settled state; it relies on discarding the first run.
- The first-run lighting difference, the light that differs between instances and the
  settled horizon difference are unexplained.
- One tour, one machine (RTX 3060), one resolution.
- Captured frames and references are kept out of the repository.
