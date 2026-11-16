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
python tools/frames.py check RUN OTHER_RUN_OF_THE_INSTANCE
```

`compare` matches frames by file name and exits 0 only if every frame passes. A pixel
counts as changed when any channel differs by more than the profile's `channelThreshold`;
a frame passes when its changed fraction and its mean absolute difference are within the
profile's limits. A missing, extra or resized frame fails, and so does an empty comparison.
With `--out` it writes `report.json` and, for each failed frame, the reference in gray with
changed pixels in red.

`measure` reports the differences between repeated captures without judging them. It is
how a profile's limits are set: a limit below the noise of two identical runs cannot pass.

`check` names the frames of a run that differ from another capture of the same instance in
a fifth of their pixels or more, and exits 0 only if there are none. Such a frame was
captured facing elsewhere. Twice on 2026-11-15 a game-drawn reference held one: 76% and 87%
of pixels apart from every other run of the view, in a different view each time. The
client's log shows the same teleports at the same times as the good runs, no step retried
and every cube loaded; only the game's count of chunks drawn differs, 403 for 411 and 315
for 319. The camera stood in the right place and faced another way; the cause is not
established. A reference is checked against the settling run before anything is scored
against it, and retaken when a frame is named. `compare` marks such a frame as not the
same view.

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
### Terrain memory and submission

Meshes lie in an arena of pages and each layer is drawn with one indirect multi-draw per
page ([ARCHITECTURE.md](ARCHITECTURE.md), sections 6.3 and 6.4). Measured 2026-11-15 on
three fresh instances prepared like the near-terrain reference; every own-terrain run is
compared under `near.json` with a game-drawn run of its own instance, and all were timed
with the selection comparison running. Times are for the opaque terrain stage: the median
and the most over the reports of a tour, one every five seconds.

Pages mapped for writing for as long as they exist were built first.

| One instance | Game | Mapped pages, indirect | Mapped pages, one call per cube and layer | A buffer per cube and layer |
|---|---|---|---|---|
| Frames passing | reference | 17 of 17 | 17 of 17 | 17 of 17 |
| Worst changed fraction | | 0.0224% | 0.0581% | 0.0571% |
| Draws per settled frame, average and most | | 34 and 42 | 928 and 1,370 | 904 and 1,370 |
| CPU, median and most | 0.64 and 1.22 ms | 0.96 and 6.50 ms | 0.66 and 6.09 ms | 0.65 and 5.86 ms |
| GPU, median and most | 1.54 and 2.36 ms | 7.40 and 28.11 ms | 6.58 and 16.77 ms | 1.73 and 2.32 ms |
| Frames per second at the captures, least | 120 | 43 | 57 | 119 |

The image is the same in all three; the GPU time is not. With mapped pages it follows the
vertices drawn, 2.4 to 3 ms per million whatever the number of pages, where a buffer per
cube and layer draws the last view's 7.1 million in 2.3 ms. The driver's own messages name
the cause. For a mapped page: "has been mapped WRITE_ONLY in SYSTEM HEAP memory" and "will
use SYSTEM HEAP memory as the source for buffer object operations". For a page created
with dynamic storage only, never mapped and written through the driver: "will use VIDEO
memory as the source for buffer object operations". On a second instance, with nothing
else changed between two runs:

| Second instance | Mapped pages | Pages written through the driver |
|---|---|---|
| GPU, median and most | 5.36 and 21.52 ms | 1.76 and 2.32 ms |
| CPU, median | 0.77 ms | 0.41 ms |
| Placing a mesh, each and the worst frame | 0.007 and 3.19 ms | 0.009 and 1.57 ms |
| Frames per second at the captures, least | 47 | 120 |

Pages are therefore written through the driver. Scored on a third instance:

| Third instance | Game | Indirect | One call per cube and layer |
|---|---|---|---|
| Frames passing | reference | 17 of 17 | 17 of 17 |
| Worst changed fraction | | 0.0507% (`07-boreal_forest`) | 0.0214% (`15-temperate_rainforest`) |
| Changed pixels, all 17 views, and outside the river | | 1,593 and 1,255 | 1,263 and 1,239 |
| Cubes drawn, median per frame | 365 | 383 | 383 |
| Draws per settled frame, average and most | | 35 and 42 | 938 and 1,370 |
| CPU, median and most | 0.63 and 1.34 ms | 0.58 and 6.05 ms | 0.59 and 6.01 ms |
| GPU, median and most | 1.62 and 2.40 ms | 1.43 and 2.33 ms | 1.57 and 2.45 ms |
| CPU and GPU at the last view | 0.71 and 2.40 ms | 0.40 and 2.15 ms | 0.56 and 1.97 ms |
| Frames per second at the captures, least | 120 | 120 | 119 |

- **The two ways of drawing agree.** They differ in no view by more than 0.0868%, 770 of
  904 changed pixels on the river; five views are identical byte for byte. In 122
  comparisons the game drew no cube that Quadrature did not.
- **Commands.** 968 a settled frame on average and 1,370 at most, for 396 cubes, written
  in 0.11 ms. The light view's list, written and not drawn, is 3,888 commands on average
  and 4,872 at most, in 0.12 ms.
- **Memory.** 14 pages, 896 MiB, for 592 MiB in use at the end of the tour, 584 MiB of
  meshes and 8 MiB lost to rounding sizes up to a granule. Up to 714 MiB was freed and
  free for reuse during the tour and 292 MiB at its end: a freed block is reused only by
  a mesh of the same size. No mesh was refused.
- **Reuse.** At most two fences were open at once, and no more than 0.4 MiB was waiting on
  one.
- **Placing.** 0.012 ms a mesh. The worst frame spent 8.7 ms placing in one run and 2.5 ms
  in the other, against the budget of 1 ms; a buffer per cube and layer spent 19.4 ms in
  its worst. What the worst frames hold is not established.
- **The first game-drawn reference of the third instance was retaken.** Its view
  `09-boreal_highlands` showed another place, 76% of pixels apart from every other run of
  that view, while the own-terrain runs matched the settling run's there within 0.006%.
  The cause in the capture is not established.

Free blocks were then split and joined, pages made ahead of need, and placing capped at
1 ms a frame. Scored on a fourth instance, the two ways of reusing blocks in one build:

| Fourth instance | Game | Reuse by exact size | Blocks split and joined |
|---|---|---|---|
| Frames passing | reference | 16 of 17 | 17 of 17 |
| Worst changed fraction | | 0.1158% (`07-boreal_forest`) | 0.0310% (`07-boreal_forest`) |
| Changed pixels, all 17 views, and outside the river | | 2,190 and 1,264 | 1,391 and 1,240 |
| Pages and memory taken | | 14, 896 MiB | 10, 640 MiB |
| In use at the end and at most | | 592.1 and 594.5 MiB | 592.1 and 594.5 MiB |
| Taken over in use, at the end | | 151% | 108% |
| Draws per settled frame, average and most | | 35 and 42 | 18 and 30 |
| CPU, median and most | 0.63 and 0.94 ms | 0.53 and 5.78 ms | 0.49 and 5.66 ms |
| GPU, median and most | 1.57 and 2.31 ms | 1.79 and 2.38 ms | 1.72 and 2.32 ms |
| Placing a mesh, each and the worst frame | | 0.012 and 3.47 ms | 0.010 and 1.79 ms |
| Frames per second at the captures, least | 119 | 120 | 120 |

- **The frame that fails** does so on the river: 926 of the tour's 2,190 changed pixels lie
  in its box, and outside it the two runs differ from the game by 1,264 and 1,240 pixels.
  The two runs differ from each other in no view by more than 0.0352%, 284 of 426 changed
  pixels on the river; five views are identical byte for byte.
- **Memory follows the most in use, not what is in use.** Between views everything is
  unloaded and use falls as low as 15 MiB; pages are not given back. With joining the
  free memory lies in few ranges: 1 to 20 through the tour and 96 at its end, the largest
  40 MiB.
- **Fewer pages are fewer draws**, since a layer is drawn with one call per page.
- **Making a page** takes 0.05 ms on average and 0.12 ms at most, and with pages made
  ahead none was made in the way of a mesh. It is not what the worst frames hold.
- **The worst frame held one mesh**, 37.4 KiB, in both runs, and placing it took 3.47 and
  1.79 ms; the cap stopped meshing in one frame of each run. A cap between meshes cannot
  bound one placement.
- **The first game-drawn reference was retaken**: its view `04-cold_rainforest-near` was
  captured facing elsewhere, which `frames.py check` names.

Where that one placement's time goes was looked for on a fifth instance, with every write
through the driver timed and those over 0.5 ms logged, and beside it a second way of
writing: a frame's meshes staged in one buffer whose storage is replaced each frame, then
copied into their pages on the GPU.

| Fifth instance | Written into the page | Staged and copied |
|---|---|---|
| Placing a mesh, each | 0.011 ms | 0.039 ms |
| The worst frame | 1.91 ms, one mesh of 37.4 KiB | 5.38 ms, one mesh of 859 KiB |
| GPU, median and most | 1.13 and 2.25 ms | 1.27 and 2.29 ms |
| Frames per second at the captures, least | 115 | 120 |

- **The driver's write is not the stall.** One write in the tour took over 0.5 ms: 0.56 ms,
  the first into the first page. The worst frame's 1.91 ms is therefore spent in placing
  outside the driver. In four runs it was the same event: one mesh of 37.4 KiB within the
  first seconds after the world starts, about the thousandth placed, and no later frame
  exceeded it. What it is has not been established.
- **A further run of the same build, scored:** 17 of 17 against a game-drawn run of the
  instance, the worst `07-boreal_forest` at 0.0648%, 1,701 changed pixels and 1,246 outside
  the river; CPU 0.44 ms and GPU 1.57 ms at the median against the game's 0.63 and
  1.71 ms. Its worst frame placed one mesh of 250 KiB in 2.81 ms and two frames were
  stopped by the cap. Five writes took over 0.5 ms and none over 0.81 ms: two were the
  first into a page, three went to a page drawn from in the frame before.
- **Copying loses.** It takes longer per mesh and in its worst frame, and for each of the
  10 pages the driver reported "is being copied/moved from VIDEO memory to HOST memory".
- **Writing a page once when it is made** was built and not measured: the first write into
  a page is not what takes long.

The driver also warns, on every frame of every run, that texture object 0 is bound to
texture units 0 to 3 without a base level. The warning is raised outside the terrain draw.

### The light's view

The view along the light is drawn for depth alone ([ARCHITECTURE.md](ARCHITECTURE.md),
section 4.7). Measured 2026-11-15 on a fresh instance: the game's terrain twice, own
terrain, and own terrain with `-Dquadrature.shadow=true`. Times are for the opaque terrain
stage, in which the light's view is drawn.

| | Game | Own terrain | Own terrain, the light's view drawn |
|---|---|---|---|
| Frames passing | reference | 17 of 17 | 17 of 17 |
| Worst changed fraction | | 0.0214% (`15-temperate_rainforest`) | 0.0495% (`07-boreal_forest`) |
| Changed pixels, all 17 views, and outside the river | | 1,233 and 1,230 | 1,561 and 1,241 |
| CPU, median and at the last view | 0.80 and 0.93 ms | 0.72 and 0.67 ms | 1.05 and 0.70 ms |
| GPU, median and most | 1.58 and 2.23 ms | 1.61 and 2.22 ms | 2.64 and 4.51 ms |
| Frames per second at the captures, least | 119 | 119 | 119 |

- **The player's image is unchanged.** With and without the light's view, 13 of 17 views
  are identical byte for byte; 384 pixels differ over the tour, 362 of them on the river
  and 22 elsewhere.
- **What it costs.** Between its own two timestamps the draw takes 2.08 ms of GPU time on
  average and 3.81 ms at most. On the client thread the draw takes 0.18 ms and writing
  its commands 0.24 ms. The first frame, which makes the target and compiles the program,
  took 22.6 ms.
- **What it draws.** Every meshed cube the client holds: at the last view 4,090 cubes and
  8,749 commands in 30 draws, where the player's view drew 497 cubes.
- **The game's threshold** for a cutout layer, read where the game sets it, was 0.5.
- **The depth as an image.** With `-Dquadrature.shadow.image=true` the depth is written as
  a gray image, nearer the light brighter, once for each place the player stands still at:
  10 over the tour. About half of each holds depth, the square of cubes the client holds
  inside the larger box, with the terrain's relief and tree crowns in it.
- **The generation** changed on every frame, since the sun moves. A second generation,
  added after this run, changes only when a mesh changes or the box moves to another
  texel.
- **The driver** reported no error. While the light's view is drawn it repeats its warning
  that texture object 0 is bound to texture units 1 to 3; the depth program reads unit 0.

### The light's depth kept, and the scene for lighting

Measured 2026-11-16 on a fresh instance: the game's terrain twice, own terrain, and own
terrain with the light's view, the scene for lighting and its buffers all on
(`-Dquadrature.shadow=true -Dquadrature.scene=true -Dquadrature.scene.gpu=true`).

| | Game | Own terrain | Own terrain, all three on |
|---|---|---|---|
| Frames passing | reference | 17 of 17 | 17 of 17 |
| Worst changed fraction | | 0.0214% (`15-temperate_rainforest`) | 0.0536% (`07-boreal_forest`) |
| Changed pixels, all 17 views, and outside the river | | 1,295 and 1,236 | 1,599 and 1,234 |
| CPU, median | 0.64 ms | 0.45 ms | 0.17 ms |
| GPU, median and most | 1.68 and 2.27 ms | 1.71 and 2.28 ms | 1.72 and 4.00 ms |
| Meshing a cube | | 1.112 ms | 1.122 ms |
| Frames per second at the captures, least | 120 | 120 | 112 |

- **The player's image is unchanged:** 13 of 17 views are identical byte for byte with and
  without the three; 829 pixels differ, 789 on the river and 40 elsewhere.
- **The light's depth** was drawn in 14,047 frames and kept in 29,510. Nearly every draw
  was for a mesh placed or released while terrain arrived after a teleport; 42 were for
  another texel and none for the light turning, since the tour holds the hour still. Over
  all frames the draw took 0.58 ms of GPU time a frame, 1.80 ms in a frame that drew.
  Client-thread time fell below own terrain's without it, because a frame that keeps the
  map neither searches the light's view nor writes its commands.
- **The scene** held 13,179 of the 15,625 cubes loaded at the last view; the 2,446 others
  are loaded and not meshed. 5,617 cubes had cells of their own, 46.6 MiB, and at most
  6,406, 53.2 MiB; the rest held one state.
- **The buffers:** 5,617 bricks in use at the end, none waiting; 43,829 bricks and 210,137
  entries copied over the tour, the whole lookup 19 times; 0.019 ms a frame on the client
  thread on average and 17.5 ms in the worst, a jump of the box.
- **The driver** reported no error.

Each cell's state was then published as well (`-Dquadrature.scene.states=true`), cubes
taken from the scene 512 a frame, and what the buffers hold read back and set beside the
scene (`-Dquadrature.scene.check=true`). On a further instance, the same four runs:

| | Game | Own terrain | Own terrain, scene with states, and the light's view |
|---|---|---|---|
| Frames passing | reference | 17 of 17 | 16 of 17 |
| Worst changed fraction | | 0.0550% (`07-boreal_forest`) | 0.1434% (`07-boreal_forest`) |
| Changed pixels, all 17 views, and outside the river | | 1,611 and 1,230 | 2,427 and 1,269 |
| CPU, median | 0.56 ms | 0.48 ms | 0.19 ms |
| GPU, median and most | 1.73 and 2.28 ms | 1.41 and 2.22 ms | 1.70 and 4.01 ms |
| Meshing a cube | | 1.120 ms | 1.166 ms |
| Frames per second at the captures, least | 120 | 119 | 119 |

- **The frame that fails does so on the river**, 1,158 of its pixels in the river's box.
  The two own-terrain runs differ in no view by more than 0.0387%: 12 of 17 are identical
  byte for byte, and of 358 changed pixels 351 are on the river.
- **What the buffers hold is what the scene holds.** 72 times over the tour the lookup
  and 16 bricks were read back, 1,136 bricks in all, each cell followed through its
  cube's list to its state number: no difference.
- **No cube was clamped,** and no state failed to fit 16 bits. The state table was sent
  289 times, once for each time a state was first met.
- **Slots in use:** 6,406 at most, 5,617 at the end, of the 15,625 there is room for.
- **Copying** took 0.027 ms a frame on the client thread on average. The worst frame took
  13.1 ms, where the jump of the box took 17.5 ms before cubes were taken a bounded
  number a frame; what that frame holds is not established, and the first frame makes
  78 MiB of buffers.
- **Meshing a cube** took 0.05 ms longer with the scene in this pair of runs and 0.01 ms
  in the pair before; meshing time varies by about that between runs.

### Coverage, the search's edge and the walk

One instance, the forest tour, each run scored against the game's terrain in the same
instance (2026-11-16).

| Run | Passing views | Worst view | Against the run with every switch off |
|---|---|---|---|
| Every switch off | 17 of 17 | 07 | |
| Coverage and the walk's check | 17 of 17 | 07, 0.0374% | 17 of 17, 12 views identical, 193 pixels changed |
| Faces | 17 of 17 | 07, 0.0379% | no pixel changed |

- **Coverage.** Of 625 columns at a settled frame, 552 have their surface meshed and 73
  have it waiting: the outermost columns, whose cubes lack a neighbor. None reads as
  having no surface or as not loaded. The map was worked out 1,146 times, in 0.46 ms on
  average and 2.5 ms at most; 166,481 cubes that cannot be meshed were asked whether they
  are empty.
- **The edge.** At the last view the search came to four of the box's six faces in 729
  steps, through no cube that is not loaded.
- **The walk.** 58,018 cubes were meshed by the walk and by the game's dispatcher and no
  layer of any differs by a byte; of 201 million blocks none was left to the dispatcher.
  The check doubles meshing, 2.1 ms a cube.
- **Faces.** The run draws through the walk and is identical to the default, but of 28.6
  million quads none became a record: 26.4 million whole faces were refused because their
  texture coordinates are not the sprite's own corners. Drawing from records, and a
  vertex array with no attributes, are not exercised by it. The builder has since taken
  the rectangle from the quad; see below.

### The cube source at commit 24f5ad89

One instance prepared with the cube source at that commit, the forest tour, own terrain
with every switch off scored against the game's terrain in the same instance (2026-11-16).

- The game's two runs: no view captured facing elsewhere; no error, no Mixin that failed
  to apply and no linkage error in the client's or the server's log.
- Own terrain: 16 of 17 views pass. View 07 fails at 0.1125% on the river alone: 885
  changed pixels in the river's box and 152 outside it, where the game's own two runs on
  this instance differ by 399 in the box.
- In 111 comparisons of what is selected, no cube the game draws is missing from what
  Quadrature draws.
- Timings are not recorded: a build on the same machine overlapped the run.
### Faces drawn from records

The instance of the section above, each run scored against the game's terrain in it
(2026-11-16).

| Run | Passing views | View 07, in the river's box and outside | Against the faces run |
|---|---|---|---|
| Faces | 17 of 17 | 0.0870%: 653 and 149 | |
| Faces, coverage, the light's depth and the scene | 16 of 17 | 0.1075%, failing: 829 and 162 | 17 of 17; 65 pixels in view 07 |

- **Records.** 25.7 of 27.9 million quads are records, 92.1%; the rest are not a whole
  face of their cell. None was refused for its texture coordinates, and no record failed
  to give its vertices back. 836 rectangles of texture.
- **Drawing.** No error from the driver and no message during a terrain draw, in the main
  pass or the light's depth pass. Against own terrain with every switch off, no pixel of
  fifteen views differs past the threshold, and no channel by more than 3 levels.
- **Memory.** 164.8 MiB of records in 3 pages and 44 MiB of kept vertices in 2, where the
  game's vertices take 592 MiB in 10.
- In 118 and 120 comparisons of what is selected, no cube the game draws is missing.
- With faces on the walk is not also compared with the dispatcher; that comparison stands
  as made above.
- Timings are not recorded: builds on the same machine overlapped both runs.

### Targets for shading

One instance, the forest tour, the cube source at 24f5ad89 (2026-11-16). Reversed depth,
which the targets need, changes a picture a little by itself, so each run is set beside the
run that differs from it in one thing.

| Run | Against | Passing views | Note |
|---|---|---|---|
| The game, reversed depth | The game | 17 of 17 | Worst 0.056%; scattered distant pixels |
| Faces, reversed depth | The game | 16 of 17 | View 07 fails at 0.150% on the river: 1,180 in its box, 205 outside |
| Faces, reversed depth | The game, reversed depth | 16 of 17 | View 07: 1,297 in the box, 77 outside |
| Faces and the targets, reversed depth | Faces, reversed depth | 17 of 17 | 13 views identical to the byte; view 07 0.079%, the river |
| Faces and the targets, reversed depth | The game | 17 of 17 | |

- **The player's picture is unchanged by the targets.**
- **The program.** Every stage compiles and links; the framebuffer is complete; no error
  from the driver. The targets were drawn in 42,992 frames.
- **What the targets hold,** from ten sets of images written where the tour stops, three
  measured: B's alpha is 0.375 on every pixel of terrain and 0 on every other, with no
  third value; normals are of unit length and lie along the axes a camera facing south
  can see (none faces south), plants' quads between them; roughness 0.85, reflectance
  0.04 and subsurface 0 or 0.55 as forest has them; cutout edges are sharp in all four.
- **The forward color worked out again from the targets** (the texel and tint from C, the
  game's shade from the normal, its occlusion from D) on pixels in full sky light, set
  beside the captured frame: a median error of 2 levels of 255. Inside a forest every
  pixel of an axis-aligned face is within 12 levels. In wide views 84 to 94% are; the
  rest are distant pixels in fog and plants' quads, which the game does not shade by the
  way they are turned.
- **Not seen, so not verified:** glass, emitters, block light and metals. The tour shows
  none.
- The list of kinds in the build these runs used was revision 3, in which lamps' states
  were named the wrong way about and lava's emission was 0.95. Neither bears on whether
  the program writes the targets as the table says. Revision 4's two changes, and reading
  the list from a folder named at start, are unit tested and have not been run in the game.
- **Cost, one view, not a measurement:** opaque terrain 2.21 to 3.37 ms on the GPU and
  0.45 to 0.67 ms on the CPU at the median, for drawing each opaque layer a second time.

Own terrain with every switch off, the same instance: 17 of 17 against the game, view 07
at 0.021%. Stage medians at the last view: opaque terrain 0.375 ms on the CPU and 2.207 ms
on the GPU; in sum 0.746 and 2.511 ms. 59,043 meshes built at 1.130 ms each. A measurement
on the same machine began 13 seconds before the run's end.
### Diagnostics

Own terrain carries switches for finding where a frame differs and what a choice costs.
Each is a system property, read once, and costs nothing when unset.

| Property | Effect |
|---|---|
| `quadrature.terrain.compare=true` | On one settled frame in 240, logs the cubes Quadrature draws beside the chunks the game selected; for each cube only Quadrature draws, which of the game's conditions left it out; for each cube only the game draws, whether a mesh is held, whether the cube is loaded and whether the view sees it |
| `quadrature.terrain.visibility=all` | Quadrature draws every meshed cube in the view's volume and reach, with no search |
| `quadrature.terrain.gameSelection=true` | Quadrature draws only the cubes the game selected |
| `quadrature.terrain.gameSelection=inverse` | Quadrature draws only the cubes the game did not select |
| `quadrature.terrain.probeRect=x0,y0,x1,y1` | On one settled frame in 240, logs the nearest cubes holding faces in that rectangle of a captured frame, with each face's position, facing and light, and whether the held mesh is identical to one made afresh from the cube source. Add `,width,height` when the capture's size is not the viewport's |
| `quadrature.terrain.probePoints=x,y;x,y` | With `probeRect`: logs the color and depth the frame holds at those pixels after each opaque layer |
| `quadrature.terrain.draw=direct` | Each layer of each cube is drawn with a call of its own, its matrix in a uniform, from the same pages |
| `quadrature.terrain.pages=mapped` | Pages stay mapped for writing and meshes are copied into them |
| `quadrature.terrain.blocks=classes` | A freed block serves only a mesh of its own size and is neither split nor joined |
| `quadrature.terrain.write=copy` | A frame's meshes are staged in one buffer and copied into their pages on the GPU |
| `quadrature.terrain.touch=true` | A page is written once, whole, when it is made |
| `quadrature.terrain.storage=buffers` | No arena: each layer of each cube has a buffer of its own, drawn with a call of its own |
| `quadrature.coverage=true` | Works out the coverage map and adds its counts and cost to the terrain summary |
| `quadrature.faces.count=true` | Counts over every cube meshed the quads that are whole faces of a cell |
| `quadrature.faces.check=true` | Meshes every cube by the walk and by the game's dispatcher and logs any layer that differs |
| `quadrature.faces=true` | Draws from the walk's quads sorted into records and kept vertices; needs indirect draws |
| `quadrature.gbuffer=true` | With faces and reversed depth: each opaque layer is drawn again into the targets for shading |
| `quadrature.gbuffer.image=true` | Writes the targets as images into the client's `quadrature-gbuffer/` where the player stands still |
| `quadrature.materials.audit=true` | Logs each block where the list of kinds and the rule differ, each the rule gives a kind that the list does not name, and the states of blocks that hold a material in each state |
| `quadrature.glDebug=true` | Logs the driver's messages of every severity, each distinct one when first seen and again when its count reaches ten, a hundred and so on, marked with whether it arrived during a terrain layer's draw |

A settled frame is one with no cube waiting to be meshed.
## Limits

- The harness cannot wait for a settled state; it relies on discarding the first run.
- The first-run lighting difference, the light that differs between instances, the river
  in view 07 and the settled horizon difference are unexplained.
- One tour, one machine (RTX 3060), one resolution.
- Captured frames and references are kept out of the repository.
