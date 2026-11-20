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

## Stage times by view

A client run with `-Dquadrature.frameLog=true` logs a stage report every five seconds: the
median and 95th percentile of each stage's CPU and GPU time over the last 256 frames, about
two seconds, and the terrain summary. A tour captures a view once the player has held still
for the settle time, 400 ticks, so the last report before a capture is that view.

```sh
python tools/stage_views.py views LOG [--tour TOUR] [--stage NAME]
python tools/stage_views.py summary LOG [LOG ...] [--stage NAME] [--json OUT]
```

`views` prints a row per captured view: the stage's times, the meshes still waiting, the
cubes the main view saw and the vertices of the last frame. A view is settled when no mesh
waits at its report. A view whose terrain is still arriving draws less and reads cheaper,
so `summary` takes its statistics over the views settled in every log it is given, which
makes runs of different settings comparable view for view. It also gives the median over
all of a log's reports, the statistic stage reports were read by before 2026-11-18, which
mixes settled views with the loading between them.

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

### Coverage brought up to date

One instance with the cube source at commit a8ebb701, which gives surface heights; the
forest tour, own terrain, each run scored against the game's terrain in the same instance
(2026-11-16).

| Run | Passing views | Passes | Mean | Worst | Checks that differed |
|---|---|---|---|---|---|
| Coverage with its check | 17 of 17 | 786 | 0.606 ms, the check within it | 6.129 ms | 0 of 745 |
| Coverage | 16 of 17 | 847 | 0.182 ms | 3.714 ms | |

- **The map brought up to date is the map filled anew**: in 745 of 745 passes that
  brought it up to date, a second map filled anew held the same state for every column.
- **No column is left without an answer**: at every summary of both runs, no column with
  its surface meshed lacked heights from the cube source. At the last view 552 of 625
  columns have their surface meshed and 73 have it waiting.
- **Cost.** Filling the map anew on every pass, with heights asked in the pass, took
  0.558 ms on average; bringing it up to date takes 0.182 ms, of which 0.100 ms is the
  scan and 0.078 ms asking for heights. The machine was under a load that was not
  identified during the second run, in which meshes were built at a third of the first
  run's rate at the slowest, so 0.182 ms is an upper bound. The mean is within 0.46 ms.
- **The worst pass is open.** 3.714 ms came in the first 15 passes, while the world was
  loading, and one ask of the cube source for a column's heights took up to 3.345 ms of
  it. A worst pass under 2.5 ms depends on that call.
- The second run's view 03 differs by 2.66%: it was captured with 11,608 meshes still
  waiting under that load, and lacks the farthest terrain. It is not a difference of
  coverage, which draws nothing.

### A stage handed over in a folder

One instance, the forest tour, own terrain with faces, reversed depth and the targets for
shading, each run scored against the game's terrain in the same instance (2026-11-16).
The folder's manifest is of the fifth version: two passes, lighting and display, and a
diagnostic pass that probes the shadow lookup.

| Run | Passing views | View 07 |
|---|---|---|
| The check, the stage not run on the frame | 17 of 17 | 174 pixels |
| The stage run on the frame with the light's depth, not shown | 17 of 17 | 481 pixels |

- **The check passes.** At 480 by 270, with files standing for the host's inputs: of
  128,063 pixels compared, lighting's output differs from the expected at none, by at most
  0.0019 a channel; display's at none, by at most one level of 255; of 129,600, the
  probe's at none, by at most 0.0005. The driver reports no error.
- **The frame's block is laid out as it is filled.** For each of the three programs the
  driver gives the block 592 bytes at binding 0 and every one of its 13 members the
  offset the host fills it at.
- **Run on the frame**, the stage's two passes ran in all 42,966 frames at 1280 by 720
  and dropped history in 27, the tour's teleports and the first frame. Not shown, it
  leaves the player's picture as the game's. Its targets take 7.0 MiB and its display
  3.5 MiB.
- **Cost, medians over the run:** 0.043 ms on the CPU and 0.176 ms on the GPU for the
  stage, timed as a stage of its own; the two passes are not timed apart.
- **An earlier folder failed its check and the fault was the folder's.** Against a
  manifest of the third version the host's output agreed at 99.0% of pixels and showed no
  shadow at the other 1,283. The folder's program found the light view's layer in a
  function that used its output parameter as the loop's counter and returned from inside
  the loop; on this driver the caller was left with the next layer, whose scale is zero,
  and the depth's span read as nothing. The block, its offsets and the textures were as
  the host gives them now.
- **Not verified here:** the stage shown, the built scene, a light that is not straight
  overhead, a target
  kept from frame to frame, an asset of three dimensions and a target smaller than the
  window. The folder has none of the last three.

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

### The cube source at commit a8ebb701

One instance prepared with the cube source at that commit, the forest tour, scored
against the game's terrain in the same instance (2026-11-16).

- The game's two runs: no view captured facing elsewhere; no error, no Mixin that failed
  to apply and no linkage error in the client's or the server's log. The client joins and
  stays, so the messages this cube source adds between server and client are understood
  on both sides.
- Own terrain with every switch off: 16 of 17 views pass. View 15 fails at 0.41%, 3,747
  changed pixels in a strip at the horizon, where own terrain shows sky and the game shows
  terrain: a CPU-heavy build on the same machine overlapped the capture, and 11,400 cubes
  were still waiting to be meshed shortly before it. In the next run on the same instance,
  own terrain with coverage on, views 13 and 15 pass at 0.013% and 0.021%.
- That next run: 16 of 17, view 07 failing at 0.13% on the river alone, 1,060 changed
  pixels in the river's box and 152 outside it.
- In 109 and 105 comparisons of what is selected, no cube the game draws is missing from
  what Quadrature draws.
- 57,871 and 58,537 meshes were built over a tour, against 59,043 on the cube source
  before: the light this cube source sends again when it changes does not multiply meshes.
- Timings of those two runs are not recorded: builds on the same machine overlapped both.
- Own terrain with every switch off again, with nothing else at work on the machine but a
  build of 11 seconds two and a half minutes in: 17 of 17, view 07 at 0.023%, views 13 and
  15 at 0.013% and 0.021%. 59,658 meshes built at 1.141 ms each, against 59,043 at 1.130 ms
  on the cube source before: about one mesh in a hundred more, for the light sent again.
  Stage medians at the last view: opaque terrain 0.435 ms on the CPU and 2.217 ms on the
  GPU; in sum 0.890 and 2.521 ms, against 0.746 and 2.511 ms on another instance before.
- Faces and the targets for shading, reversed depth: 17 of 17 against the game, view 07 at
  0.087%. The targets measure as on the cube source before.

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
  the program writes the targets as the table says. Revision 4's two changes are unit
  tested and have not been run in the game.
- **The list read from a folder named at start** (2026-11-16, one instance): revision 6 of
  the list, written over continued lines, gives 519 names, the count the list states; its
  hash is the one the folder's manifest gives; of 1,058 blocks the list and the rule differ
  on 179 and the rule gives no kind to a block the list does not name. A reader that took
  each line by itself read 75 of the 519, which nothing else showed: a list read short is
  now logged as `MATERIAL KINDS FAILED`.
- **Cost, one view, not a measurement:** opaque terrain 2.21 to 3.37 ms on the GPU and
  0.45 to 0.67 ms on the CPU at the median, for drawing each opaque layer a second time.

Own terrain with every switch off, the same instance: 17 of 17 against the game, view 07
at 0.021%. Stage medians at the last view: opaque terrain 0.375 ms on the CPU and 2.207 ms
on the GPU; in sum 0.746 and 2.511 ms. 59,043 meshes built at 1.130 ms each. A measurement
on the same machine began 13 seconds before the run's end.

### Opaque terrain against the near radius

Measured 2026-11-18 on an RTX 3060 12 GB, driver 610.88, at 1280 by 720, with one build.
One instance at the lab's baseline had its horizontal radius raised to 16 and 20 for a run,
its vertical radius held at 12; a second was prepared with the reduced profile near-8, 8
each way. Two configurations were run at each radius: drawn forward (own terrain, reversed
depth, face records), and with the targets for shading as well. The light's depth, the
stage and every diagnostic were off.

The forest tour does not settle past radius 12. At 20 none of its 17 views had its terrain
meshed at the capture: 11,000 to 31,000 cubes were waiting. Meshing runs on the client
thread within 4 ms a frame, so a place takes longer to fill than the tour's 20-second views.
The runs used a tour of three of its places instead. Each place is held for five captures
9 blocks apart before its far and near views, and the forest tour's last view ends the
tour. Every measured view of every run was settled. Times are `stage_views.py summary` over
the seven measured views: the median of the views' medians, and the worst view.

| Horizontal radius | 8 | 12 | 16 | 20 |
|---|---|---|---|---|
| Vertical radius | 8 | 12 | 12 | 12 |
| Cubes the main view sees, median | 623 | 1,401 | 2,666 | 4,267 |
| Opaque terrain drawn forward, GPU, median and worst | 0.96 and 1.94 ms | 1.81 and 2.30 ms | 2.23 and 2.49 ms | 2.32 and 2.73 ms |
| With the targets as well | 2.15 and 2.69 ms | 2.72 and 3.37 ms | 2.86 and 4.59 ms | 3.74 and 6.73 ms |
| CPU, median, drawn forward | 0.13 ms | 0.19 ms | 0.27 ms | 0.45 ms |
| Records and kept vertices in use | 64 and 15 MiB | 165 and 43 MiB | 307 and 85 MiB | 497 and 129 MiB |
| A new place settles in | 8 to 9 s | 10 to 18 s | 30 to 43 s | 70 to 88 s |
| Cubes waiting, most after arriving | 2,700 | 15,000 | 36,000 | 68,000 |

- **The forward draw grows far more slowly than what it draws.** From radius 8 to 20 the
  cubes seen grow 6.8 times and the GPU time 2.4 times; from 16 to 20, 1.6 times and 1.04
  times. Most of the stage's time does not follow the cubes drawn. Distant cubes add
  triangles that cover few pixels.
- **The targets' second draw grows past radius 12:** on average over the views it adds
  0.85, 0.74, 1.07 and 1.81 ms, and 4.0 ms at the last view at radius 20.
- **Memory** is 0.33 to 0.37 MiB a column held at a vertical radius of 12, 626 MiB at
  radius 20. The arenas' capacity was 704 MiB at 20, inside near terrain's 1.5 GiB.
- **Filling a place limits the radius before drawing does.** While cubes are waiting the
  client thread meshes or finds empty about 1,000 cubes a second at every radius, so the
  time to settle grows with the volume held: 70 to 88 seconds at radius 20.
- The terrain summary's vertices are only the kept vertices, about 8% of quads; records
  are not counted. The line gives no figure for the geometry a frame draws.
- Radius 8 ran on its own instance: its views compare with the others by place.
- These runs were measured before the card's clock was found to follow its load (the tier
  benchmark, below), and record no clock state: under the game alone the card runs at 1.3 to
  1.4 GHz or lower, and its times read up to a quarter above a settled card's. For
  calibration the tier benchmark's table at settled clocks supersedes this one.

### Meshes built on Cubature's build pool

Measured 2026-11-18 on one fresh instance at the lab's baseline, with Cubature at the pinned
commit and its build pool of 4 threads. The radius figures before are those of the section
above, from builds on the client thread within 4 ms a frame.

- **The picture.** Own terrain against a game-drawn run of the same instance, forest tour
  at radius 12: 17 of 17 views pass `near.json`, the worst view 07 at 0.039%. The
  reference was checked against its settling run; no view was captured facing elsewhere.
  No driver message came during a terrain draw.
- **What is drawn** is unchanged at every measured view of the dwell tour: the same cubes
  seen, vertices and memory. Opaque terrain drawn forward takes 2.23 ms on the GPU at
  radius 16 either way, and 2.61 against 2.32 ms at radius 20, on another instance.
- **Settling.** Every view of the forest tour at radius 12 was settled at its capture,
  where builds on the client thread left 3,000 to 5,000 cubes waiting at two views.

| Dwell tour, a new place | Builds on the client thread | On the build pool |
|---|---|---|
| Radius 20, settled in | 74 and 87 s | 44 and 37 s |
| Radius 20, cubes waiting at most | 65,000 and 68,000 | 21,000 and 20,000 |
| Radius 16, settled in | 34 and 42 s | 24 and 18 s |
| Radius 16, cubes waiting at most | 33,000 and 34,000 | 12,000 and 11,000 |

- **The client thread.** Opaque terrain took 0.12 to 0.20 ms of CPU at the settled views
  of the forest tour, where a view whose terrain was still arriving took 5.4 ms with
  builds on the client thread. While a place arrives the frame's terrain setup takes
  0.77 ms at the median and 1.5 ms at the 95th percentile. Its worst frame took 30.5 ms
  in the pool's calls during an arrival, which is not explained, and 11.8 ms in the first
  frames of the world, as the pool started.
- **Empty cubes** are settled from the source's own count, without a snapshot: 330,268
  times over the forest tour, against 68,847 builds. Builds on the client thread took a
  snapshot for each of the 60,283 empty cubes of a tour.
- **The light's depth** with both layers and the check every 600 frames: 137 checks, none
  with a texel off by a step of depth. 5,680 meshes put in place with the shape of the
  one they replaced kept their tiles.
- **Builds a second.** At radius 20, with 4 permits and 20,000 builds waiting, the pool
  built about 950 a second, 1.16 ms each: it takes snapshots only when it is pumped, for
  as many builds as the permits and one a thread, and is pumped about once a frame, so its
  threads stood idle for most of each frame.
- **What the client allocates.** On the lab's former G1 profile with an 8 GiB heap, the
  client's long frames were its collector's pauses, wherever they landed. Measured on
  2026-11-18 over a radius-20 dwell tour, with a build of Cubature whose pool keeps its threads busy: before
  the walk made its own face test, 61% of what the client allocated was on the build
  threads, 47% of everything a position the game's face test makes for each face. With the
  face test below and the consumer's offset kept at the origin, the client allocates 57 GB
  over the tour where it allocated 128 GB, and pauses of 20 ms or more fall from 40 to 29;
  the build threads' share falls to 25%. The face check meshes 110,866 cubes both ways with
  none differing. The longest pauses remained mixed collections with the heap near full,
  once with an evacuation failure at 7.6 GiB.
- **Kept cube copies and the collector.** Cubature 45e636a6 lets go of a cube's copy for
  snapshots once no snapshot has asked for it in 200 ticks: the client's live heap after a
  place settles falls from 1,492 to 1,777 MiB to 838 to 1,132 MiB at radius 20, at the cost
  of 28% more copies (0.35 s of client time over the tour). G1's pauses were unchanged by
  it: their length followed the young generation's survivors while a place arrived. On ZGC
  with the same fixed 8 GiB heap, the lab's default since Cubature 19f9961a, the longest
  pause is 0.07 ms on the client and 0.03 ms on the server, against 179 and 140 ms on G1,
  with no allocation stalls; places settle in the same 24 and 27 s, client CPU is the same,
  and the worst frame in the build pool's calls falls from 32.8 to 9.1 ms.

### The handoff from OpenGL to Vulkan

Measured 2026-11-19 on the RTX 3060 12 GB, driver 610.88, with commit 8c8b3e1 (which merges
feat/build-pool 089e9e9) on one fresh instance, behind `-Dquadrature.handoff=measure`
(ARCHITECTURE.md 4.9). The window was 1920x1080 or 2560x1440, the render scale two thirds
(1280x720 and 1707x960 inputs). The handoff's parts took turns in segments of 120 frames over
the forest tour (1440p) and over the forest tour followed by the Nether and back (1080p): nothing,
the round trip alone, the round trip with the stand-in, OpenGL's copies alone, and all of it.
An `nvidia-smi` sampler every 250 ms tagged each frame with the GPU's power state; the card sat
in P3 (885 to 997 MHz) for most frames and in P0 (1,282 and 1,395 MHz median) for the rest, so
P0 is given first and P3 after. The lab caps the game at 120 frames a second without vsync.

| Median GPU time, P0 (P3) | 1920x1080 | 2560x1440 |
|---|---|---|
| Round trip alone: OpenGL's signal to its resumption | 0.374 (0.499) ms | 0.355 (0.466) ms |
| of which signal to Vulkan's start | 0.197 (0.261) ms | 0.192 (0.248) ms |
| of which Vulkan's end to resumption | 0.173 (0.234) ms | 0.162 (0.210) ms |
| Round trip with the stand-in | 0.477 (0.679) ms | 0.525 (0.739) ms |
| of which the stand-in on Vulkan | 0.115 (0.162) ms | 0.182 (0.263) ms |
| The stand-in's copies: in, out | 0.113, 0.083 ms | 0.150, 0.118 ms |
| All of it | 0.651 (0.913) ms | 0.773 (1.048) ms |
| Client time a frame: round trip alone, all of it | 0.056, 0.102 ms | 0.058, 0.104 ms |
| GPU memory: the images (their texels) | 31.9 (29.9) MiB | 56.8 (53.1) MiB |

- **The handoff costs a fixed latency, not bandwidth.** The round trip with no work on Vulkan
  takes 0.36 to 0.37 ms at P0 and 0.47 to 0.50 ms at P3 at both sizes, split nearly evenly
  between the way into Vulkan and the way back; the barriers that hand the images between the
  queues take no measurable time of their own (Vulkan's batch, 0.000 ms). The 99th percentile
  of the round trip is 0.76 to 0.78 ms at P0. On the client, the round trip costs 0.014 ms in
  `vkQueueSubmit`, 0.028 ms in OpenGL's semaphore calls and 0.004 ms on fences.
- **Against DLSS's published cost.** NVIDIA gives the RTX 3060 1.45 ms at 1080p and 2.39 ms at
  1440p with presets J and K in Performance mode, 2.81 and 5.05 ms with preset M (research R1,
  1.9). With the round trip measured here, a DLSS evaluation in this handoff would take about
  1.82 and 2.75 ms (J and K) or 3.18 and 5.41 ms (M) at P0, before its own inputs at Quality
  mode's larger render size. The copies are the stand-in's: in the bridge OpenGL draws into the
  shared images and the post effects read the output, so they do not count.
- **Memory.** Opening the Vulkan device takes 5 to 6 MiB of GPU memory; the images take what
  their allocations say, 31.9 and 56.8 MiB, so the handoff holds about 38 and 63 MiB beside
  DLSS's own 85.8 to 119.3 MB at 1080p and 143.5 to 207.4 MB at 1440p. VK_EXT_memory_budget's
  usage for the process rose 246 to 455 MiB over a run with the handoff's allocations unchanged:
  on this driver it counts the process's OpenGL memory too.
- **The frame interval** stays at the cap, 8.33 to 8.34 ms in every part: under the cap the
  handoff's time fits in the frame's slack, so its cost shows in a GPU-bound frame only.
- **Checks.** 81 of 81 stamp checks passed at 1080p and 73 of 73 at 1440p, with no OpenGL error
  on a checked frame and no result dropped; the first two round trips were proved in 1.2 to 1.5 ms.
- **Formats.** The game's depth renderbuffer is `GL_DEPTH24_STENCIL8`, shared as
  `VK_FORMAT_D24_UNORM_S8_UINT`; color and output as `RGBA16F`, motion as `RG16F`. The driver
  accepted every image with every usage its format supports and the mutable-format bit, and
  prefers, without requiring, dedicated memory for them.
- **The picture.** With the whole round trip on every frame and the output not written into it,
  at 1280x720, 17 of 17 forest views pass `near.json` against a game-drawn run of the same
  instance, 15 of them with no changed pixel and the worst view 07 at 0.0679%. The reference was
  checked against that run; no view was captured facing elsewhere.

Failure modes, on commit 2f2595e and a fresh instance of its own (its handoff differs from
8c8b3e1 only in the frame's wall-clock column and in how the check reads the stamp):

- **A device that does not match.** Every opening lists each Vulkan device with its verdict; the
  integrated UHD 770 beside the card is refused because its driver UUID differs. Forced onto it
  (`quadrature.handoff.device=other`), the handoff opened a Vulkan device there and OpenGL
  refused the first handle it was given, the semaphore's, with `GL_INVALID_VALUE`; the handoff
  turned off with that reason and the frame went on, with no hang.
- **A window resize** (to 1440x810 and back, with Vulkan's validation layer on): the images were
  made again in 8.0 and 6.8 ms, and the next round trip's check passed each time.
- **A world change** (the Nether and back, and leaving the world): the images were kept and the
  next round trip's check passed each time, on both instances. Nothing in the handoff depends on
  the world.
- **Validation.** Over the smoke run, Vulkan's validation layer reported nothing.

The first build measured, 75509b6, never opened: the RTX 3060's driver lists 276 device
extensions, and reading them on LWJGL's 64 KiB thread stack overflowed it. LWJGL reports that
as an `OutOfMemoryError`, which escaped into the game's loop, and the game dropped the world.
Since ea636b6, Vulkan's enumerations read into memory allocated for them, image sizes are
bounded before anything is allocated, and no error leaves the handoff for the game.

Not measured: DLSS itself, which needs its SDK; a GPU-bound frame, where the round trip's time
adds to the frame's; a queue of compute alone; binary semaphores.

### DLSS through the handoff

Measured 2026-11-19 on the RTX 3060 12 GB, driver 610.88, with the DLSS SDK 310.9.16's
`nvngx_dlss.dll`, on one fresh instance, behind `-Dquadrature.handoff=measure
-Dquadrature.handoff.evaluate=dlss` (ARCHITECTURE.md 4.9) in Performance mode at a render scale of
one half: 960x540 to 1920x1080 and 1280x720 to 2560x1440, the sizes DLSS's optimal settings ask
for. The handoff's parts took turns in segments of 120 frames as in the handoff's own measurement,
over a five-view tour that visits the Nether and comes back; the parts that evaluate ran DLSS. An
`nvidia-smi` sampler every 250 ms tagged each frame with the GPU's power state. P0 held most frames,
its median graphics clock 1,605 MHz in the 1080p K run and 1,920 to 1,927 MHz in the others. DLSS
evaluated on the game's frame copied down, with zero motion and no jitter: enough to time it, not to
judge its picture.

| Median GPU time at P0 | 1080p, preset K | 1080p, preset M | 1440p, preset K | 1440p, preset M |
|---|---|---|---|---|
| NGX's own commands | 1.544 ms | 2.553 ms | 2.201 ms | 4.531 ms |
| their 99th percentile | 3.249 ms | 5.848 ms | 3.587 ms | 8.795 ms |
| NVIDIA's published cost, RTX 3060 | 1.45 ms | 2.81 ms | 2.39 ms | 5.05 ms |
| Round trip with DLSS | 1.887 ms | 2.852 ms | 2.514 ms | 4.844 ms |
| Round trip alone, same run | 0.380 ms | 0.291 ms | 0.285 ms | 0.282 ms |
| All of it (copies in, round trip, copy out) | 1.985 ms | 2.944 ms | 2.646 ms | 4.979 ms |
| DLSS's feature, GPU memory | 82 MiB | 124 MiB | 142 MiB | 209 MiB |

- **DLSS costs here what NVIDIA publishes, and the handoff adds its fixed latency.** NGX's own
  commands come within 0.09 to 0.52 ms of the guide's figures, under them except at 1080p with
  preset K. The round trip with DLSS exceeds NGX's work by 0.30 to 0.34 ms in every
  configuration, the handoff's cost measured alone, so the handoff's measurement predicted
  DLSS's cost through it.
- **The clock.** NGX's time is the same within 0.3 ms at every graphics clock the sampler saw
  above 1,000 MHz (1440p, preset M: 4.52 to 4.54 ms from 800 to 1,999 MHz). Either the 250 ms
  samples do not follow the clock within a frame, or the evaluation does not depend on it; these
  runs cannot tell which.
- **The frame interval** at P0 stays at the cap, 8.32 to 8.38 ms, in every part of every run,
  preset M at 1440p included: under the cap DLSS fits in the frame's slack. The longer intervals
  at P3 come in the parts without DLSS too (11.3 ms with the handoff off at 1080p).
- **The client** spends 0.096 to 0.103 ms a frame in recording and submitting the DLSS batch,
  against 0.015 ms for the stand-in's prerecorded one, since NGX records its evaluation into the
  batch each frame; the handoff in all takes 0.19 to 0.20 ms a frame.
- **Memory.** Starting NGX adds about 19 MiB to the Vulkan device (24 MiB against 5 to 6).
  DLSS's feature is the fall in the driver's available memory over making the images and the
  feature, less the images' 26.25 and 45.00 MiB; the guide gives 85.8 to 119.3 MB at 1080p and
  143.5 to 207.4 MB at 1440p.
- **Checks.** Every stamp check passed, 18 to 22 a run, with no OpenGL error on a checked frame
  and no result dropped; the stamp is written into the output's first pixel after DLSS, so the
  check proves the round trip, not DLSS's picture. Over three world changes a run, DLSS's history
  started afresh each time and the images were kept. NGX's logs show it mapped the project's
  identifier as an engine of its own, loaded the SDK's `nvngx_dlss.dll` 310.9.16 from the path the
  run gave, and took the preset asked for in every quality mode.
- **Validation.** On the first build, Vulkan's validation layer reported 14 messages, all of them
  NGX using buffer device addresses on a device that had not enabled them: NGX's list of the
  extensions it needs does not name the feature. With the feature enabled for DLSS, a validated
  run with three world changes reported nothing. NGX's time on the first build matched the second
  (1.550 against 1.544 ms at 1080p with preset K).
### DLSS taking the scaler's picture

Measured 2026-11-19 on the RTX 3060 12 GB, driver 610.88, with the DLSS SDK 310.9.16's
`nvngx_dlss.dll` and preset K, on one fresh instance at 2560x1440 with own terrain, over the
scaler's short tour (three places far and near and the glowing golem, seven views), scored by
`tools/frames.py quality` against own terrain at the output's size with both switches off, run in
the same instance and checked against its settling run. DLSS took the scaler's picture in every
composite of its runs (17,950 at 0.67, 17,915 at 0.5), with the offset taken as the scaler makes
it, x +1 and y +1.

| Against native, short tour | Drawn | PSNR | SSIM | Edge detail | GPU for the picture |
|---|---|---|---|---|---|
| Own scaler, 0.67 with the temporal pass | 1715x965 | 28.31 dB | 0.811 | 0.513 | 1.03 ms |
| DLSS, Quality mode, 0.67 | 1715x965 | 30.37 dB | 0.897 | 0.773 | 2.91 ms |
| DLSS, Performance mode, 0.5 | 1280x720 | 29.21 dB | 0.858 | 0.709 | 2.77 ms |

The GPU time is the sum of the medians of the scaler's passes that ran: the depth copies, motion,
and either the temporal and scale passes or the handoff, and the depth stretch. The clocks differ:
while the GPU worked, the own scaler's run sat in P3 for 500 of 574 samples, at a median 1,020 MHz,
and both DLSS runs in P0 for every frame, at 1,927 MHz.

- **DLSS makes the better picture, at either scale.** At 0.67 it scores 2.06 dB and 0.086 in
  structural similarity above the own scaler, on every view; at 0.5, drawing 44% fewer pixels,
  it still scores 0.90 dB above the own scaler at 0.67. The own scaler's scores repeat its
  measurement on another instance to the third decimal (28.30 dB, 0.811, 0.512).
- **What it costs.** NGX's own work takes 2.246 ms at 0.67 and 2.215 ms at 0.5, all at P0: set by
  the output's size, not the drawn one, as in DLSS through the handoff (2.20 ms at this output in
  Performance mode). The round trip adds 0.26 ms, the copies in 0.14 ms at 0.67 and 0.09 ms at
  0.5, the copy out 0.09 ms, so the picture costs 1.9 ms more than the own scaler's at 0.67, and
  the client 0.17 ms a frame. The own scaler's time was taken at about half DLSS's clock, so at
  equal clocks the difference is larger; and DLSS's load held the card in P0, where the own
  scaler's let it fall to P3. The frame stayed at the cap, 8.33 ms.
- **Mode and range.** NGX takes render sizes from 1280x720 to 2560x1440 for this output in
  Quality mode, so the scaler's 1715x965 at 0.67, a few pixels above Quality's optimal 1707x960,
  is accepted; 0.5 is Performance mode's optimal size. The scaler cycled through 18 and 32 offsets.
- **The offset's sign, shown.** With the y offset's sign flipped DLSS scores 27.44 dB and 0.793,
  and with both flipped 26.57 dB and 0.746, against 30.37 dB and 0.897 as the scaler makes it.
  Edge detail rises as the sign goes wrong (0.796 and 0.840): misplaced samples alias, which adds
  gradient, so edge detail alone does not rank a scaler.
- **Validation.** Over a validated run of the tour, Vulkan's validation layer reported nothing.
- **The refusals.** With the stand-in asked for beside the scaler, and with DLSS asked for beside
  a scale of 0.67 without the temporal pass, the handoff was turned away with its message and
  opened no Vulkan device, and the scaler ran: 7 of 7 views each.
- **Not measured.** The views are still; a picture in motion is not scored, and the scaler's
  motion is the camera's, so entities and particles carry none (D21). No texture LOD bias is
  applied for the scale, which DLSS's guide recommends.
### The scaler's costs

Measured 2026-11-19 on the RTX 3060 12 GB, driver 610.88, on one fresh instance with own terrain
over the scaler's short tour, at 2560x1440 and 3840x2160 (the window ran at the full size). The
jar, built from tree d06c9696, carried three cuts to the scaler's cost, and with
`quadrature.scale.compare=true` ran every other 120 composites with the programs and barrier as
they had been, so both were timed in the same seconds: the temporal pass's nine neighborhood
weights computed once a frame on the CPU against nine exponentials a pixel, the scale pass's 16
taps read straight from its shared tile against copied into registers first, and a barrier for
framebuffer access, texture fetches and texture updates against one for everything. A pass's time
is the median over the run's reports of each report's median, by power state.

| Median GPU time at P0, ms, with the cut / without | Temporal | Scale (at 1, the copy) | Barrier |
|---|---|---|---|
| LOW, 0.67 of 2560x1440 | 0.317 / 0.317 | 0.385 / 0.386 | 0.002 / 0.002 |
| MEDIUM, 0.77 of 2560x1440 | 0.361 / 0.360 | 0.335 / 0.334 | 0.002 / 0.002 |
| HIGH, the temporal pass alone at 2560x1440 | 0.493 / 0.493 | 0.095 / 0.095 | 0.001 / 0.001 |
| LOW, 0.67 of 3840x2160 | 0.483 / 0.484 | 0.607 / 0.609 | 0.001 / 0.001 |
| MEDIUM, 0.77 of 3840x2160 | 0.643 / 0.646 | 0.617 / 0.617 | 0.001 / 0.001 |
| HIGH at 3840x2160 | 1.081 / 1.086 | 0.182 / 0.182 | 0.001 / 0.001 |

- **None of the three cuts saves time**: with and without, the passes agree within 0.005 ms at
  P0 and within 0.011 ms either way at P3. The programs stay as they were; the barrier is
  narrowed to what follows. With all three cuts the picture at 0.67 of 2560x1440 scored 28.31 dB,
  SSIM 0.811 and edge detail 0.513 against own terrain at the output's size in the same instance,
  as the programs before them did on another (DLSS taking the scaler's picture).
- **The scaler's whole cost** at P0, its two depth copies, motion, the temporal pass, the scale or
  copy, the barrier, the depth's stretch and what lies between them: LOW 0.982 ms at 2560x1440 and
  1.520 at 3840x2160, MEDIUM 0.972 and 1.781, HIGH 0.957 and 2.010. The scaler stage takes 0.008
  to 0.014 ms more than its passes; the 0.28 ms counted between them before came from medians
  over different mixes of clock states. For two and a quarter times the pixels the passes take
  1.55 times as long at LOW, 1.83 at MEDIUM and 2.10 at HIGH.
- **At 3840x2160 the scaler does not pay on today's frame.** Over the short tour's seven views,
  opaque and translucent terrain took 2.357 ms at the output's size; with the scaler, terrain and
  the scaler stage took 2.807 ms at 0.67 and 3.293 ms at 0.77. Opaque terrain, mostly a cost per
  vertex, fell only from 1.189 to 0.949 and 1.104 ms; translucent terrain, the water filling
  pixels, from 1.168 to 0.424 and 0.521 ms.
- **DLSS at 3840x2160**, taking the scaler's picture at 0.5 in Performance mode: NGX's own work
  4.820 ms (NVIDIA gives 5.05), the round trip 5.095 ms, the whole step 5.482 ms, all at P0; the
  frame held the cap.
- **The tier model** (ARCHITECTURE 12.1.1) now carries each tier's scaler as a line through its
  two outputs, and DLSS as 0.39 ms fixed and 0.61 ms an output megapixel from its two steps. On
  the stored run its unit test holds, this card's choices stand: HIGH to 2560x1440, at 5.65 ms of
  the 7 there (a fit of 1.24, before 1.37, as HIGH's temporal pass at the output's size had been
  carried at 0.41 ms), and MEDIUM at 3840x2160, at 6.31 ms (a fit of 1.11, before 1.07). DLSS fits
  above HIGH at 1920x1080 (1.19) and not at 2560x1440 (0.84).
- **Not run on its own:** the jar committed, which is the programs before the cuts with the
  narrowed barrier; each part ran above, the programs in DLSS taking the scaler's picture and the
  barrier here.

### The light scene's slots by place

Measured 2026-11-19 with a jar built from commit e341eb5, own terrain and the scene's buffers
(`quadrature.scene=true`, `quadrature.scene.gpu=true`) at reach twelve, a box of 15,625 cubes, on
fresh instances without distant terrain: seed 8675309 at the default scale for Riven's own tours
of caves (24 views from a chasm down to a lava river) and of water (reef, kelp and waterweed
underwater, three shallows), and seed 101 for its rock forms (fin arches, tower karst, tsingy,
hoodoos, basalt), standing in for a built-up area. The most slots in use over each run, against
6,406 over the forest tour:

| Run | Most slots in use | Held at the captures |
|---|---|---|
| Caves | 11,632 | 3,246 above the chasm to 11,632 in the ink-cap cave at y -52; 8,656 to 10,304 at every view from y -150 down to a lava river at -466 |
| Water | 9,262 | 2,405 to 9,262 |
| Rock forms | 5,377 | 529 to 5,377, most at the tsingy |

- **A cave system holds most of the box as bricks**, three quarters of it at the worst view: what
  lies around a deep view is mixed cubes in every direction. One and a half times 11,632 is
  17,448, more than the box, so the buffers keep room for every cube (ARCHITECTURE 5.1).
- **The copy kept up**: at the end of each run no cube or entry waited, and the most one frame's
  copy took on the client thread was 19.7 ms in the caves and 13.2 ms over water.
### Why cubes are built

Measured 2026-11-19 with a jar built from tree 915d399b (commit e341eb5 and the build causes) on one
fresh instance of seed 8675309 without distant terrain, own terrain at reach twelve, with
`quadrature.terrain.causes=true`: eight views of Riven's underground tour at falling depth (the chasm
from above to a lava river 466 blocks down), then nine of its standard surface views. Each hand-in is
counted under the first of the events since the cube's last one: its arrival, a resend, a change of
blocks, light or biomes, or a change of a cube touching it alone (the source's NEIGHBOR, raised when a
touching cube arrives, leaves or changes at their boundary, and carrying the whole cube). Each
rebuild's vertices and records are hashed and compared with the mesh it replaced.

| Hand-ins | Underground, 8 views | Surface, 9 views |
|---|---|---|
| In all | 156,085 | 136,579 |
| First builds, the cube's arrival | 68,036 | 53,431 |
| A touching cube alone | 86,851 | 82,203 |
| Light | 1,336 | 1,527 |
| Resends, blocks, biomes | 0 | 0 |
| Rebuilds after a touching cube that drew the same as before | 76,129 of 83,203 | 71,648 of 78,485 |
| Rebuilds after light that drew the same | 485 of 1,156 | 628 of 1,262 |

- **About half of all building is redone for nothing.** A touching cube alone brings 56% of the
  hand-ins underground and 60% at the surface, and 91% of those rebuilds draw exactly what they
  replace: 49% and 52% of every build, at 1.2 ms each on Cubature's threads.
- **The source raised 8.3 and 7.8 NEIGHBOR events a cube's arrival** (872,309 and 1,151,114 events
  for 105,266 and 147,817 arrivals), merged into a cube's pending hand-in where several come before it.
- **Underground costs more by its volume:** 19,500 hand-ins a view against 15,200 at the surface,
  as more of the cubes around a cave view are mixed.
### Side-aware rebuilds

Measured 2026-11-19 with a jar built from tree 88e42c5b (commit 1bfc3cf and the side-aware rebuilds),
own terrain at reach twelve with `quadrature.terrain.causes=true`, against the rule before it
(`quadrature.terrain.neighbors=all`) in the same jar. Caves: eight views of Riven's underground tour,
seed 8675309, on one instance after a run that generated them, and on a second, fresh instance whose
first run generated them. Forest: the forest tour on a fresh instance of seed 2485331 at scale four,
the game's terrain twice (settling, reference), then own terrain with each rule.

| | Builds | Rebuilt for a touching cube | Of them drawing the same | All air settled again | Build time |
|---|---|---|---|---|---|
| Caves, generating, old rule | 149,126 | 75,478 | 69,269 | 99,523 | 202 s |
| Caves, generating, side-aware (another instance) | 99,577 | 27,432 | 18,559 | 20,822 | 163 s |
| Caves, generated, old rule | 88,875 | 16,987 | 14,156 | 83,409 | 125 s |
| Caves, generated, side-aware | 79,155 | 6,603 | 2,916 | 22,311 | 109 s |
| Forest, old rule | 73,349 | 15,482 | 12,915 | 292,910 | 119 s |
| Forest, side-aware | 63,043 | 5,433 | 3,051 | 70,832 | 103 s |

- **Most of what was redone for nothing is no longer done**: rebuilds that drew the same fell by
  73% to 79%, and builds in all by a third while the world generates around the player and by 11% to
  14% through terrain already generated, where far fewer touching changes come. Changes passed by as
  unread: 835,995 while generating, 787,356 and 1,523,111 through generated caves and forest.
- **Generation is where touching changes come from**: through the same eight cave views the old rule
  rebuilt 75,478 cubes for a touching cube while the world generated and 16,987 once it had.
- **The pictures are unchanged.** Own terrain passes all 17 forest views against the game's own draw
  with either rule (the side-aware run's worst view 07, 0.0401%), and the two rules' runs pass against
  each other, 8 of 17 identical byte for byte and the worst view 07 at 0.0311%, within the 0.0387% two
  runs of one rule differ by. In the caves the two rules' runs pass at seven views, closer than two
  runs of the old rule; at the eighth, a lava lake, they caught the lava's animated texture at another
  frame, with the same geometry.
- **Reading through the wrapper costs nothing measurable**: a build took 1.41 and 1.37 ms through the
  generated caves and 1.62 and 1.63 ms through the forest, old rule and side-aware. The generating runs,
  1.36 and 1.64 ms, ran on two instances while their servers generated.
- **What is still redone for nothing** (2,916 to 18,559 rebuilds) comes from cubes that read the
  touching cube's side and drew the same anyway: a change's bounds reach a whole face where the cube
  read one layer of it, and a change of light over the whole cube reaches every side.

### The game's chunk rebuild without the opaque layers, and the walk as the default mesher

Measured 2026-11-19 with a jar built from tree 0cc05c0f (commit 1287618 with PLAN item 12a and the
walk as the default mesher for model blocks), the forest tour on a fresh instance of seed 2485331 at
scale four: the game's terrain twice (settling, reference), then own terrain with both changes, then
own terrain with both switched back (`quadrature.terrain.gameOpaque=build`,
`quadrature.terrain.mesh=dispatcher`). The game's rebuilds are timed in the rebuild itself, on the
clock, from start to end.

| | Game's rebuilds on its threads | Each | On the client thread | Quadrature's builds | Each |
|---|---|---|---|---|---|
| Game's terrain (reference) | 83,847, 101.4 s | 1.209 ms | 1,027, 0.59 s | | |
| Own terrain, both changes | 80,973, 44.1 s | 0.545 ms | 970, 0.14 s | 62,817 | 1.609 ms |
| Own terrain, both switched back | 76,070, 111.1 s | 1.460 ms | 943, 0.65 s | 63,060 | 2.083 ms |

- **The game's rebuilds take 60% less time** (44.1 against 111.1 s over the tour) once they leave out
  the opaque layers, and those it does at once near the player on the client thread 78% less (0.14
  against 0.65 s). Every rebuild under own terrain left them out (81,943 of 81,943).
- **The pictures are unchanged.** Own terrain passes all 17 views against the game's own draw with
  both changes (worst view 15, 0.0214%) and with both switched back (worst view 07, 0.0257%); the two
  own-terrain runs are identical byte for byte at 12 views and differ by at most 0.0012% at the rest.
- **Quadrature's build time is not settled by these runs.** A build took 1.609 ms with both changes and
  2.083 ms without them, but the configuration without them took 1.62 to 1.63 ms on the side-aware runs
  of the same day, so a build's time on the clock moves by a quarter between runs, and the run with
  both changes also had the game's threads doing 60% less beside it. The walk's vertices are the
  dispatcher's, as the face check shows; what it saves is left to measures a run's other load does not
  move: the build threads' own CPU, and the face check timing both ways on the same blocks.

### The light matrix from a table of the cube

`TableLighter` fills Forge's smooth lighter's matrix from a table of the cube, each cell asked once.
Checked first offline against Forge's own lighter, by a program kept with the run's evidence: random worlds of
proxy states in four mixes of opacity, light, occlusion and covered sides, every block of 40 cubes a seed
lit both ways, every value of the matrix compared bit for bit: 491,520 blocks over three seeds, none
differing; with a fill rule or the corner rule changed by one character, 144,827 and 155,025 of 163,840
differ, so the comparison sees a wrong matrix.

Then in the game, 2026-11-19, a jar from tree 362b8b38 (commit 3c9fddc with the table, the build
threads' own CPU in the terrain line, and the face check timing both ways), on the generating cave tour
of seed 8675309, each run on a fresh instance with Cubature cb64f367:

- **The same vertices.** With the face check every cube is meshed through the game's dispatcher and
  through the walk, which lights from the table: 92,566 cubes, none differing in any layer.
- **No time saved that shows.** On those same blocks, one after the other on the same threads, the
  dispatcher took 107,725 ms and the walk with the table 107,668 ms, the walk's notes for face records
  included. A check run on another fresh instance with `quadrature.terrain.lightTable=off` separates the
  table's own share, each run's walk time taken over its own dispatcher time: 1.029 without the table
  (121,364 against 117,952 ms over 95,896 cubes, none differing), 0.9995 with it. The table saves about 3%
  of the walk's time on the same blocks, about what the walk's notes for face records cost.
- **Without the check**, a build took 1.291 ms on the clock and 1.220 ms of its thread's CPU over 97,555
  builds; the game's rebuilds left the opaque layers out in all 126,663, 50.7 s on its threads, 29.3 s of
  their CPU, and 0.76 s on the client thread.

The table and the walk are kept for being exact and for what the walk does not allocate; neither is
counted as a saving of time.
### Rays through the scene

Measured 2026-11-18 and 19 on the RTX 3060 12 GB, driver 610.88, with the benchmark of ARCHITECTURE
5.2 (`-Dquadrature.rays.bench=true -Dquadrature.rays.check=true`) and the scene's buffers on, on three
fresh instances, over the dwell tour of three places: radius 12 with jars from f8aa79e, radius 20 and
12 again from b2c32e7, radius 16 and 20 again from 19df819. Each set traces three classes at three
lengths in each tier's batch: probe rays from a grid about the player in directions spread over the
sphere; shadow
rays from just above the first thing met straight down at each point of a square 256 blocks across,
toward the hour's light, which the tour holds overhead at noon; and the same toward a sun 20 degrees
above the horizon ("low sun"). GPU clocks were sampled beside every tour.

**The trace is the reference's, to the bit.** `BrickTrace`'s eight unit tests require it to end
where a walk of single cells over the scene as defined ends, outcome, distance and cell, on terrain,
scattered cells at three fills, a wrapped origin and a moved box, and to agree with points along the
ray in double precision except where a cell is crossed in under two of their steps; with the tie rule
broken, four of the eight fail. On the GPU, after each measurement two runs of 256 rays and their
results were read back and traced again on the CPU over the buffers the trace read:

| Run | Sets | Rays compared | The same word for word | Differing | Passed over |
|---|---|---|---|---|---|
| Radius 12, dwell tour | 21 | 580,608 | 580,608 | 0 | 0 |
| Radius 12, underground | 41 | 1,133,568 | 1,133,568 | 0 | 0 |
| Radius 20, dwell tour | 5 | 138,240 | 138,240 | 0 | 0 |
| Radius 12, dwell tour, again | 20 | 552,960 | 552,960 | 0 | 0 |
| Radius 16, dwell tour | 17 | 470,016 | 470,016 | 0 | 0 |
| Radius 20, dwell tour, again | 12 | 317,952 | 317,952 | 0 | 13,824 |
| Radius 16, dwell tour, the walk as shipped | 17 | 470,016 | 470,016 | 0 | 0 |
| Radius 20, dwell tour, the walk as shipped | 12 | 317,952 | 317,952 | 0 | 13,824 |

Every compared word was equal, 3,981,312 rays in all: the distance's bits, the cell, the outcome
and the nodes visited, through storage blocks and through buffer textures in the first four runs,
from the scene's buffers and from a copy of them on the GPU in the last four. The first six traced
the earlier walk, which also passed blocks of two cells a side by the bricks' coarser bits; the last
two, from 71e966d, the walk as shipped, which reads no coarser bit (ARCHITECTURE 5.2). The rays passed over
were traced while a cube arrived, under another generation than the copy read back. The light scene
checks of the first four runs found no difference between the scene and its buffers.

**Memory.** The lookup and room for a brick for every cube of the box:

| Box | Lookup | Brick room | Bricks in use, most seen | With each cell's state as well |
|---|---|---|---|---|
| Radius 12, vertical 12 | 61 KiB | 8.6 MiB | 5,863 (4,273 underground) | 77.8 MiB |
| Radius 16, vertical 12 | 106 KiB | 15.0 MiB | 10,969 | 135 MiB |
| Radius 20, vertical 12 | 164 KiB | 23.1 MiB | 17,401 | 208 MiB |
| Radius 20, vertical 8 | 112 KiB | 15.7 MiB | | 142 MiB |

The trace reads the lookup and bricks alone. The benchmark's own buffers take 96 MiB, room for the
largest batch.

**What disturbed the first timings.** Two things, each found from evidence the runs carried:

- *The clock.* Under the game's light load the card idles in P5 or P3 (memory at 810 or 5,001 MHz)
  and reaches P0 (1,935 MHz, memory 7,501 MHz) 0.7 to 2.0 seconds into a set's load, once 3.3. In the
  first sets probe rays ran first and so met the lower clocks. Sets now trace in rounds, every
  measurement once a round, untimed for two seconds; within a set the five repeats then agree to a few
  percent.
- *Where the driver keeps the brick buffer.* With the clock settled, the same probe work still ran 3 to
  12 times slower in some sets than in others, with the same node counts and the same longest rays,
  while coherent rays in the same sets held steady and GPU utilization was no higher. With
  `-Dquadrature.glDebug=true` the driver reported moving buffers from video to host memory: the
  benchmark's rays, results and census, which the check read back after every measurement, over a
  thousand times a run; the scene's lookup and bricks, which the light scene check and the end of each
  set read back, ten to twenty times. In both runs at radius 12 the scene's brick buffer was first moved
  to "DMA cached" memory 11 and 12 seconds before the slow stretch began, and the sets before it were
  fast. A trace whose rays miss the cache reads such memory across the bus; coherent rays mostly hit
  the cache. The CPU now reads only staging buffers filled by copies on the GPU.

**With neither disturbance** (radius 16 and 20, the light scene check off, every read through a
staging copy, the driver's moves counted per set): no buffer was moved in any of 29 whole sets, and
the same work took the same time wherever it was measured. Probe rays at 256 blocks in HIGH's batch
took 2.36 to 3.08 ms at the far views of every place at radius 16 (1.48 to 1.94 ms at the near views,
where more probes begin among trees) and 2.42 to 2.80 ms at radius 20, where a set at radius 20 had
taken 30 ms before. Each measurement was also timed from a copy of the lookup and bricks made on the
GPU at the set's start and never read back. Over 783 measurements the ratio of the copy's median to
the scene's was 1.000 at its middle and within 2% in 88% of them at radius 16 and 95% at 20; the few
further apart fell either way, the copy faster in 30 and slower in 40. The scene's buffers, made with
`glNamedBufferStorage` and `GL_DYNAMIC_STORAGE_BIT` and written with `glNamedBufferSubData`, are as
fast to trace as a copy the CPU never touches, so long as the CPU does not read them. The light scene
check (`-Dquadrature.scene.check=true`) reads them every 600 frames through a staging buffer filled by
`glCopyNamedBufferSubData`, as the ray check does; a buffer used only as a copy's source was not moved
in any measured set.

**Speed.** GPU time of a batch at radius 20, the median over 12 sets of each set's median of five,
from the scene's own buffers: the walk as shipped (from 71e966d, 2026-11-19); in brackets the earlier
walk with coarser bits, at P0 clocks (from 19df819). The walk as shipped was run without clock
samples, the sampler not having been started for those tours; the five repeats of each of its sets
agreed as closely as the earlier walk's did at P0: the most less the least, over the median, was
0.6 to 0.7% at its median over the measurements, against 0.8 to 1.6%.

| Class, length in blocks | 65,536 rays (MEDIUM) | 524,288 (HIGH) | 2,097,152 (MAX) | Nodes a ray, MAX's batch |
|---|---|---|---|---|
| Probes, 16 | 0.062 ms (0.057) | 0.339 ms (0.308) | 1.056 ms (0.917) | 5.8 (4.0) |
| Probes, 64 | 0.220 ms (0.176) | 1.413 ms (1.183) | 4.892 ms (3.928) | 17.3 (11.3) |
| Probes, 256 | 0.528 ms (0.409) | 3.340 ms (2.676) | 12.170 ms (9.344) | 34.1 (23.6) |
| Low sun, 16 | 0.058 ms (0.072) | 0.359 ms (0.385) | 1.353 ms (1.328) | 15.1 (9.3) |
| Low sun, 64 | 0.197 ms (0.190) | 1.038 ms (0.948) | 3.550 ms (3.192) | 31.5 (18.3) |
| Low sun, 256 | 0.336 ms (0.285) | 1.615 ms (1.476) | 5.438 ms (4.784) | 40.5 (25.6) |
| Noon, 16 | 0.051 ms (0.055) | 0.294 ms (0.276) | 1.179 ms (1.054) | 14.6 (8.4) |
| Noon, 64 | 0.097 ms (0.081) | 0.514 ms (0.426) | 1.952 ms (1.604) | 21.3 (13.2) |
| Noon, 256 | 0.137 ms (0.114) | 0.781 ms (0.653) | 2.955 ms (2.502) | 32.1 (24.0) |

In million rays a second, MAX's batch as shipped: probes 1,985, 429 and 172 at 16, 64 and 256
blocks; toward the low sun 1,550, 591 and 386; at noon 1,780, 1,075 and 710.

- **Without the coarser bits** the walk visits 1.3 to 1.7 times the nodes and takes 1.07 to 1.30
  times as long for probe rays and 0.79 to 1.22 times as long for rays toward the sun: a cell's step
  costs less than a block's, so the time grows more slowly than the nodes. It ends every ray in the
  same cell at the same distance.
- **Probe rays run at well under half the rate of coherent ones per node.** In MAX's batch at 256
  blocks a probe ray visits 34.1 nodes and a ray toward the low sun 40.5, but the batch takes 12.2
  against 5.4 ms: 5.9 against 15.6 billion nodes a second (with coarser bits, 5.3 against 11.2).
- **The radius barely matters.** Probe rays at 256 blocks in MAX's batch, as shipped: 11.44 and
  12.17 ms at radii 16 and 20; with coarser bits 7.40, 9.08 and 9.34 ms at 12, 16 and 20, where 43%
  of them left the box at radius 12, 18% at 16 and 13% at 20.
- **Small batches leave the card idle:** 65,536 rays run at 50 to 74% of the rate of two million.
- **Noon is the easy case.** The tour holds the sun overhead; rays toward it climb out of the box,
  97 to 100% leaving it, so far shadows are priced from the low sun.
- **Where each ends, probes:** at 256 blocks in the MEDIUM and HIGH batches 54 to 60% hit; in every
  batch 9 to 23% began in a cell that blocks (probes are not moved out of terrain), and 0.2 to 5% met a
  cube not known; in HIGH's batch 11 to 27% left the box.
- **Underground**, at y 22 and 30 inside rock, with the earlier walk, 97% of probes begin in a cell
  that blocks and every probe batch takes under 0.6 ms (the shadow classes start from the surface and
  cost what they do above); the tour's capture never fires inside rock, so only this one place was
  measured, and no open cave was found for this seed.

**Each tier's batch as it would run**, the walk as shipped, at its own radius (HIGH 16, MAX 20;
MEDIUM at 16 as well, radius 12 not being measured again: with coarser bits it took 0.18 and 0.37 ms
there against 0.18 and 0.41 at 16), and spread over frames; in brackets the earlier walk:

| Tier | Batch | Whole update | A quarter a frame |
|---|---|---|---|
| MEDIUM | 16x8x16 probes, 32 rays: 65,536 rays at 64 blocks; at 256 | 0.22 ms; 0.53 ms (0.18; 0.41) | 0.06 ms; 0.13 ms |
| HIGH | 32x8x32 probes, 64 rays: 524,288 rays at 64 blocks; at 256 | 1.45 ms; 3.47 ms (1.19; 2.66) | 0.36 ms; 0.87 ms |
| MAX | two cascades of 32x8x32 probes, 128 rays: the near one (4 blocks apart) at 64 blocks and the far one (16 apart) at 256 | about 8.5 ms (6.6) | about 2.1 ms |
| MAX far shadows | a ray toward the low sun at 256 blocks, a million of them | 2.59 ms (2.28) | 0.65 ms |

MAX's two cascades were measured together at one length (4.89 ms at 64, 12.17 ms at 256); the line
above takes half of each. The far-shadow line is per million rays: a whole 2560 by 1440 picture is
3.7 million.

### The tier benchmark

Measured 2026-11-18 and 19 on an RTX 3060 12 GB, driver 610.88, window 1280 by 720, with
`-Dquadrature.tierBench=calibrate`, which runs the benchmark wherever the player holds still with
near terrain caught up, three times a place, and appends each run to the client's
`quadrature-tier-runs.jsonl`. The card's clocks were sampled beside every tour by `nvidia-smi`,
as evidence only. Instances are fresh, seed 2485331, scale 4, without distant terrain;
`quadrature-tier-02` ran an exploratory build without the settling load, `quadrature-tier-03`
and `-04` a build of the committed branch (b110221), and `quadrature-tier-05` the calibrated
build (bd0d324).

**The picture is unchanged with the benchmark on.** Own terrain with the benchmark running
(54 runs over the tour) against the game's terrain in the same instance, forest tour at radius
12: 17 of 17 views pass `near.json`, the worst view 07 at 0.024%. The reference was checked
against its settling run; no view was captured facing elsewhere. The captures fall seconds
after each place's runs, so they also show that the probes leave no state behind. No driver
error came during the tour.

**The card's clock decides what a probe reads.** One place, one build, the view held still, a
radius of 20, six runs: the first three timed while the card sat in a low state, the last three
once it had reached its top one.

| Runs | Bandwidth, 256 MiB | Opaque terrain at 1280 by 720 | At 2560 by 1440 | At 3840 by 2160 | The light's box |
|---|---|---|---|---|---|
| 1 to 3 | 137 to 193 GB/s | 2.51 to 2.64 ms | 2.96 to 4.25 ms | 5.36 to 7.50 ms | 7.27 to 9.11 ms |
| 4 to 6 | 348 to 351 GB/s | 1.305 to 1.309 ms | 1.714 to 1.726 ms | 3.11 to 3.13 ms | 4.04 to 4.11 ms |

- Under the game alone the card moves among P5, P3 and P0, graphics 510 to 1,440 MHz and
  memory 810, 5,001 or 7,501 MHz, and reaches about 1,950 MHz and 7,501 MHz only under added
  load. A whole run left in P3 reads the bandwidth kernel at two thirds, the ratio of the
  memory clocks.
- At one place and one state the runs agree to a tenth of a percent: over the forest tour's
  places, the median spread of a figure over a place's runs was 0.03 to 0.07%.
- Without a settling load the first run at each place was the slow one; the benchmark now runs
  one until two series of its own times agree (ARCHITECTURE 12.1.1).
- **A place whose cubes have not arrived** reads as caught up, since nothing waits to be
  meshed: 13 of the first tour's 65 runs drew no terrain. A run now also needs the count of
  meshes held to stay the same, and above none, while the view stands still.

**Repeatability, with the settling load.** The committed branch at b110221 on two fresh
instances, the forest tour at radius 12, own terrain drawn forward with both layers of the
light's depth and the light scene on the GPU: 100 runs, three a place, 93 of them settled and
drawn. The other 7 never met the 2% agreement then asked of the settling load, with times at
the top state's; the rule now asks 5%. The settling load agreed after 13 to 15 frames in most
runs and within 40 in nearly all. A place's first run now reads as its later ones: 0.841 ms at
1280 by 720 at the first place, where an instance's settled runs before read 0.838.

| The score's spread, (max - min) / median | Median | Worst |
|---|---|---|
| The runs at one still view, one instance | 0.46% | 18.3% |
| One place, two fresh instances, each place's median | 0.48% | 15.2% |
| The 17 places, each place's median | 26.3% | standard deviation 6.8% |

- By probe, across places: terrain a standard deviation of 8.6%, the light's box 7.1%,
  bandwidth 1.9%, the trace 26.5%. At one place the trace read 4,267 million rays a second,
  against 290 to 460 at the rest: its rays met the light scene's copy before it had caught up
  (below, the light scene's copy), which the benchmark now waits for.
- **The band exceeds the spread.** At 15% of the budget it is thirty times the spread at one
  place and on two instances, and 2.2 standard deviations of the spread between places. A card
  that sits exactly on a boundary changes tier on a run at another place about once in seventy
  runs; elsewhere, less.
- The worst cases at one place came from interference that recurs every few frames landing on
  one resolution, since each round took the sizes in one order: 1280 by 720 read 1.39 ms where
  1920 by 1080 read 1.23. The order now turns each round.
- The card's score against the calibration fitted on these runs (bd0d324): 0.97 at the median,
  0.86 to 1.17 over the 93 runs; against the one refitted over three radii (ddd8c29), 0.96, 0.85
  to 1.14 over 128.

**Radii 16 and 20, and the calibrated choice.** The calibrated build (bd0d324) on a fresh
instance, the short dwell tour (two waits a place, 13 views) at radius 20 and then 16, with the
light's depth, the scene on the GPU and the trace probe. Opaque terrain at the views where the
dwell tour and the forest tour stand alike, against radius 12 on the two instances above:

| Against radius 12, the same views | 1280 by 720 | 1920 by 1080 | 2560 by 1440 | 3840 by 2160 |
|---|---|---|---|---|
| Radius 16, seven views, median | 1.46 | 1.39 | 1.37 | 1.24 |
| Radius 20, two views (this tour's one sound view and the exploratory build's) | 1.95 | 1.83 | 1.72 | 1.44 |

- The step falls with the resolution: a farther radius adds primitives, and their share of the
  time falls as the pixels rise. The table holds a curve for each radius.
- **At 1280 by 720, the window, every counted run chose HIGH** for speed and MAX for memory,
  8,637 MiB being free at the first screen; the scores ran 0.78 to 1.06, 0.98 to 1.06 at most
  places. DLSS had room above HIGH there.
- **At radius 20 four runs measured a place still arriving.** The count of meshes held still for
  the 240 frames between batches while the view's search passed through about 9,600 cubes not
  loaded; the probe drew about 5,000 primitives, read the card at 3.6 times the reference and
  chose MAX, which the band does not stop, before the place's later runs chose HIGH again. A run
  now needs the search to meet no cube that is not loaded, or that count to stand still for 15
  seconds, and one whose terrain drew under 200,000 primitives chooses nothing (below).
- **The calibration was then refitted** over 128 runs at the three radii, with item 4's trace
  costs and item 6's costs of own temporal anti-aliasing and the scaler priced in. That commit
  changes no code path: this tour's choices were made with bd0d324's table, and a unit test
  feeds the new table this card's measured run (radius 16, the rainforest's high view) at four
  outputs. It takes HIGH at 1280 by 720, 1920 by 1080 and 2560 by 1440, as bd0d324's table did,
  and MEDIUM at 3840 by 2160 as bd0d324's did, a boundary case with LOW (below, the walk and the
  scaler as they ship).

**The final code on the GPU.** The branch at 6067440, its settle rule and its floor of
primitives with ddd8c29's table (the scaler's and the rays' costs since changed under the test
above, below), on a fresh instance: the dwell tour at radius 20, five waits a place, then the
forest tour at radius 12.

| Tour | Runs | Caught up, after the view came to rest | Meshes held then | Primitives drawn | Tier at 1280 by 720 |
|---|---|---|---|---|---|
| Dwell, radius 20 | 14 at 5 views | 6 to 19 s, every one with no cube of the box not loaded | 12,668 to 18,456 | 1.22 to 7.76 million | HIGH in all, memory MAX |
| Forest, radius 12 | 50 at all 17 places | 0 to 13 s, every one with no cube of the box not loaded | 2,609 to 4,651 | 0.27 to 3.53 million | HIGH in all, memory MAX |

- No run settled by the count standing still, so no run of a place partly arrived; no run drew
  fewer than 200,000 primitives, and none was turned away. At radius 20 the rainforest's place
  caught up only at its last view: its earlier views had cubes still to come when they ended.
- The scores ran 0.91 to 1.05 at radius 20 and 0.88 to 1.13 at 12, the window's choices those
  the test asserts. No driver error, no out-of-memory notice.
- 8 of the 50 forest runs reached the settling load's 300 frames without its times agreeing
  within 5%, at the top state's times all the same (the arithmetic pass 2.01 to 2.23 ms against
  2.03 at the median of the rest); one late reading off by more kept the agreement from holding,
  and the cap let them go on.

**The walk and the scaler as they ship.** With item 4 merged at 1230d57, the table takes the
rays' costs of the walk that reads no coarser bits (rays through the scene, above; that run
sampled no clocks, and its figures are taken as the top state's): MEDIUM 0.53 ms a frame, HIGH
0.87, MAX's cascades 2.13 and its far shadows 5.43. The scaler's are its plain kernel's, the one
it ships, at 2560 by 1440 output: 0.92 ms of passes at 0.67 and 0.78 at 0.77, the 0.28 ms
between them held fixed. The unit test of this card's measured run gives the tiers it gave
before: HIGH at 1280 by 720, 1920 by 1080 and 2560 by 1440, with fits of 2.29, 1.82 and 1.37,
and MEDIUM at 3840 by 2160 with 1.07 (the table before, 1.03); memory MAX at all four. On the
reference card MEDIUM takes 6.62 ms of the 7 at 3840 by 2160, or 6.97 ms were the gaps to
follow the pixels too, and this run MEDIUM either way. The trace probe walks the same way, so
its reference rate, measured on the walk that read the coarser bits, is scaled by the probe's
batch's own times on the two walks, 0.180 against 0.222 ms, to 305 million rays a second, and
the run's trace in the test by the same factor (ARCHITECTURE 12.1.1); the benchmark's version is
2. No run was made on the GPU for it.

**The light scene's copy.** On the merged branch the benchmark ran 51 times on a fresh instance
over the forest tour at radius 12, at version 2: every run chose HIGH at the window, and at 15
of the 17 places the trace read 0.78 to 0.82 times the final code's run above (median 0.79,
against 0.81 from the walk's own times), 290 million rays a second at the median against the
305 of the reference. Place by place the two set apart one place of the final code's run (runs 7
to 12, two views at -15873.5): its trace took 0.015 ms for 65,536 rays, 4,267 million a second,
where the merged jar read 230 to 290 million. The scene's copy says why: after the view's jump
there it held no brick, with about two million cubes queued to be taken from the scene, 512 a
frame, and held none for about 35 s while near terrain stood caught up, so the rays left at
once. The queue held each cube many times over, a box being 15,625 cubes. The same held for ten
runs of the repeatability tours, at two places on two instances, the copy holding no brick with
100,000 to 1.8 million cubes queued; nine of them are among the 128 calibration runs, whose
median trace is the same without them.
The queue now holds each of the box's entries at most once, and an entry is taken for the cube that
lies there when it is taken (c960183). Under the same conditions, a dwell at radius 20 and then the
forest tour at radius 12 on one fresh instance, the queue held at most 15,113 cubes for the box of
15,625; the copy caught up within 0.026 s of near terrain all 33 times a place came to rest; the
light scene's check found no difference in 17 checks; and the trace at the two views that had met the
empty copy read 0.228 to 0.291 billion rays a second.

- **Nothing is timed until the copy has caught up**: nothing left to take from the scene or to
  copy, and some bricks held (`TerrainSettle`, through one read-only figure of the copy's,
  `TerrainHook.sceneCopyWaiting`). A unit test holds a place whose meshes have all arrived and
  whose copy works through two million queued cubes to running nothing until the copy is done.
- **A trace far from the other probes chooses nothing.** Over the 128 runs, the 118 others
  read the trace within 1.29 times of the geometric mean of the run's other ratios, a standard
  deviation of 1.14 times in the logarithm; the runs that met the empty copy stood 10.6 to 12.7
  times above, and the first run at one place at radius 16 3.5 times below (0.110 billion rays
  a second, then 0.309 and 0.315 at the same view). A run whose trace stands past twice or half
  the others now chooses nothing: 5.2 standard deviations past the sound runs and far inside
  both kinds of fault. A unit test feeds two of those runs' measured figures to the table, and
  neither chooses; this card's run of the tiers test agrees. With both changes the benchmark is
  at version 3.
- **The settling load does not wait on the copy for its own sake.** It begins only once the
  place has caught up, the copy included, so it waits by construction. Its high times at the
  first run of a place (5 of the 50 runs: 2.99 to 4.49 ms of arithmetic against 2.01 to 2.20)
  are a lower clock state held steady, not the copy's work: in four of them the pass over the
  large set read 1.15 ms against 0.77, 1.50 times, the ratio of the memory clocks of 7,501 and
  5,001 MHz. Three
  of them came with the copy at rest, one while it was still copying bricks and one with it
  empty and doing nothing on the GPU; one run at the empty copy settled at 2.01 ms. Each of those
  runs' probes read as its place's later runs (bandwidth 0.99 to 1.00): the warm-up repetitions
  and the untimed load brought the clocks up before the timed ones.

**A driver update, left to be run.** Every run so far is on driver 610.88. A driver is the
user's to install, so the repeat across one is a step left:

1. With the new driver installed, confirm its version (`nvidia-smi --query-gpu=driver_version
   --format=csv`) and that the capability dump's driver fingerprint has changed.
2. Play one tour on an instance that holds a stored result from 610.88, with
   `-Dquadrature.tierBench.firstLaunch=true` and the light's and the scene's switches: the log
   must say the first-launch path runs because the stored result is under another key, the
   same GPU and another driver, and that the band holds to the stored tiers.
3. Play the forest tour with `-Dquadrature.tierBench=calibrate` on that instance and on a
   freshly prepared one, and set the scores beside 610.88's at the same places: the driver's
   effect is the difference of the medians against the spread at one place, and a tier may move
   only where a fit lies past the band.

The same path was run without a driver change, on the calibrated build (bd0d324) on a fresh
instance, by replacing the stored result's driver and key between two tours. The second tour's
gate said: "the first-launch path runs the benchmark: the stored result is under another key,
the same GPU, another driver; the band holds to speed HIGH and memory MAX". Its 33 runs chose
HIGH, and the result was stored again under the true key.

**Switches.** `-Dquadrature.tierBench=calibrate` (every still place, `.repeat` runs a place,
3; `.stillFrames`, 240; `.heights`, 720,1080,1440,2160) or `once`;
`-Dquadrature.tierBench.firstLaunch=true`, the player's path; `-Dquadrature.tier.memoryLimitMiB`
takes the card to have that much memory; `-Dquadrature.tier.<group>=<tier>` overrides a group.

### Own temporal anti-aliasing and scaler

Measured 2026-11-19 at 2560 by 1440 on one fresh instance, with a jar built from commit
e8e15dd, on the RTX 3060 with driver 610.88 and `quadrature.exitOnOutOfMemory=true` on every
run. The scaler is Keys' separable cubic, the plain kernel, which that jar ran behind a switch
since removed; the shader shipped computes the same, without the luma lane only the other
kernel read. A kernel steered along edges by the structure tensor was measured beside it in
the same jar and declined (D23); its figures are kept as the comparison, marked *steered*. The
short tour has seven views: six of the forest tour, three places far and near, and one of a
glowing iron golem on a red team. Quality is scored against own terrain at the output's size
with both switches off, by `tools/frames.py quality`: PSNR, structural similarity, and edge
detail, the frame's mean luma gradient over the native frame's.

- **A first design failed**, on an instance before this one. It shrank every viewport of the
  display's size inside the world pass. GTCEu Reloaded's bloom copies the game's picture into
  framebuffers of its own and draws it back through viewports of the display's size every
  frame, so the scene came out shrunk twice, 1151 by 649 in a corner. The framebuffer and
  display size that stand in for the pass replaced it (ARCHITECTURE 4.8).
- **Both switches off.** Own terrain against a game-drawn run of the same instance over the
  full forest tour: 16 of 17 views pass `near.json`. View 07 differs in 0.2046% of its pixels,
  within the 0.2131% by which the game-drawn run differs from its own settling run there (the
  river in view 07).
- **Motion from depth.** With `quadrature.taa.check=true`, every reading of the motion target
  at the still views, 29 or 30 a run, had a mean of at most 0.00005 px and a greatest of
  0.0001 px.
- **Where the picture lands.** The global shift of each scaled view against the native one
  (`tools/frames.py shift`) is 0.00 px in six of the seven views at 0.67 and at 0.77, the
  golem's view among them, and in the last, a near view, 0.20 px down at 0.67 and 0.25 px down
  at 0.77, 0.05 px left at both; the steered kernel gave the same. The measure finds a real
  frame moved by a known fraction of a pixel to the twentieth of a pixel it resolves.
- **Glowing entities.** The golem's outline is laid on at the output's size in place, after
  the scaler: in frames captured at the end of the game's frame, after the outlines, the native
  run and a run at 0.67 (steered) show the same 3,064 red pixels with the same bounds. Tour
  captures are taken in the last world drawing, before the game lays outlines on, so they show
  none at any scale.
- **Framebuffers.** 29 are made at startup with the scaler on and none afterward, counted in
  each timing report.

The scaler's passes at the median of the short tour's views, GPU ms. The `scaler` stage holds
the composite, passes from the depth copy on; the terrain depth is copied at the entities
boundary. The rest of the stage, 0.1 to 0.4 ms, is the work between the timed passes.

| Pass | 0.67 | 0.77 | 0.67, steered | 0.77, steered |
|---|---|---|---|---|
| Terrain depth copy | 0.035 | 0.040 | 0.033 | 0.040 |
| Depth copy | 0.035 | 0.040 | 0.033 | 0.040 |
| Motion | 0.059 | 0.061 | 0.056 | 0.061 |
| Temporal | 0.313 | 0.294 | 0.287 | 0.293 |
| Scale | 0.385 | 0.274 | 0.613 | 0.475 |
| Depth stretch | 0.096 | 0.072 | 0.088 | 0.071 |
| The `scaler` stage | 1.000 | 1.072 | 1.386 | 1.328 |

- **What the frame saves.** Opaque and translucent terrain take 2.00 and 2.03 ms native on
  the two native runs, and 1.24 ms at 0.67 and 1.44 ms at 0.77 scaled (1.08 and 1.26 ms in the
  steered runs). Opaque terrain differs by 0.13 ms between the plain and the steered run at
  each scale, though the kernel runs after it: these stage times vary between runs by about
  that much. The frame's GPU sum rises from 2.11 ms native to 2.39 ms at 0.67 and 2.58 ms at
  0.77 (2.45 and 2.64 ms steered): on this tour the world is too light for the composite to pay
  for itself.
- **Break-even.** The composite pays for itself once the world's stages that scale with the
  drawn size take more at the output's size than the figures below. Scaling with the pixel
  count is the best case; this tour's terrain saved less, since part of its cost is per vertex.

| World time at the output's size to break even, ms | 0.67 | 0.77 | 0.67, steered | 0.77, steered |
|---|---|---|---|---|
| If the stages scale with the pixel count | 1.8 | 2.6 | 2.5 | 3.3 |
| At the share this tour's terrain saved | 2.6 | 3.9 | 3.0 | 3.6 |

| Against native, short tour | PSNR, dB | SSIM | Edge detail |
|---|---|---|---|
| 0.67 | 28.30 | 0.811 | 0.512 |
| 0.77 | 28.89 | 0.837 | 0.559 |
| 0.67, steered | 28.22 | 0.808 | 0.500 |
| 0.77, steered | 28.83 | 0.835 | 0.548 |

- **The steered kernel** scored no better than the plain one at either scale, nor over the six
  views both 0.67 runs share with one native run (28.44 against 28.50 dB, 0.802 against 0.805,
  0.497 against 0.508), and its scale pass cost 0.23 ms more at 0.67 and 0.20 ms more at 0.77.
  Against the plain kernel at 0.77 it is 47.2 dB and SSIM 0.997. It is declined (D23).
- **Under motion**, from frames captured at the end of the game's frame along one scripted
  path, native against 0.67 with the temporal pass, from the same pose. That run's scaler was
  the steered kernel; the kernel works on the temporal pass's result and never feeds the
  history, so the recovery and the trailing below are the plain kernel's too. While the camera
  holds still the structural similarity is 0.677, and a frame changes from the last by 0.37
  levels of 255 against 0.16 native: the offset leaves 0.2 levels of flicker. After a cut the
  similarity recovers in four frames, 0.624, 0.654, 0.668, 0.673, then 0.675 to 0.678; a resize
  drops the history and recovers the same way. Panning 0.23 degrees a tick, about 4 px at the
  center for the default field of view, the scaled frame lies 0.41 px behind the native frame
  of the same tick on average; the two runs captured at different points within the tick, and
  the poses' difference accounts for 0.54 px, which leaves -0.12 px, from -0.34 to +0.15: no
  trailing beyond a third of a pixel. The similarity while panning, 0.610, carries the same
  difference in pose and does not measure ghosting by itself.
- **Not measured:** the picture with a mod's post-processing that keeps the game's framebuffer
  from before the pass. Variance clipping is not used and its gain is not measured (D23).

### The four together

Measured 2026-11-19 at 1280 by 720 on one fresh instance, with a jar built from commit de5bd03, which
merges the rays through the bricks, the tier benchmark, own temporal anti-aliasing and scaler, and the
handoff to Vulkan onto the build pool.

- **Every switch off.** Own terrain against the game-drawn run of the same instance: 17 of 17 forest
  views within `near.json`, 15 of them under 0.02% changed; view 07 reads 0.0779%, where two
  game-drawn runs of the instance differ by 0.0698%.
- **The scaler at 0.67, plain kernel.** The picture's shift against own native is within 0.05 px on
  all six views of the short tour; history is kept in 17,676 of 17,694 composites; motion reads
  0.0001 px at most in still views. Quality at this output is not that of 2560 by 1440 and is not
  compared with it.
- **The tier benchmark** (`calibrate`, radius 12): 51 runs, each choosing HIGH at the window. The
  trace probe's rate per place is 0.78 to 0.82 times that of the walk with coarser bits at 15 of 17
  places (median 0.79), against 0.81 for the probe's batch in the ray benchmark.
- No out-of-memory notice, failed check, OpenGL error or exception from Quadrature in any run.

Since that jar, the tier benchmark waits for the light scene's copy and turns away a trace far from
the run's other ratios (5f0cd3a); those paths are unit tested, and no run above traced outside the
bound. The handoff's refusal beside the scaler ran later the same day, on that code with the
DLSS shim beside it: with the scaler at LOW and DLSS asked for, and at HIGH with the stand-in,
the handoff turned itself off at its first frame with one warning and opened no Vulkan device,
and the scaler ran, 5 of 5 views each. Since then DLSS takes the scaler's picture instead (DLSS
taking the scaler's picture).

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
| `quadrature.coverage.check=true` | With coverage: each time the map is brought up to date, fills a second map anew and logs any column that differs; its time is counted in the pass |
| `quadrature.faces.count=true` | Counts over every cube meshed the quads that are whole faces of a cell |
| `quadrature.faces.check=true` | Meshes every cube by the walk and by the game's dispatcher and logs any layer that differs |
| `quadrature.faces=true` | Draws from the walk's quads sorted into records and kept vertices; needs indirect draws |
| `quadrature.exitOnOutOfMemory=true` | After the `CLIENT OUT OF MEMORY` notice, the client quits, so a launcher waiting on it fails at once instead of waiting on a world the game has dropped. Every lab run sets it |
| `quadrature.testOutOfMemory=<seconds>` | That many seconds after the first world is entered, the game loop asks for an array no heap holds, to show the notice and the quit in a real run |
| `quadrature.gbuffer=true` | With faces and reversed depth: each opaque layer is drawn again into the targets for shading |
| `quadrature.gbuffer.image=true` | Writes the targets as images into the client's `quadrature-gbuffer/` where the player stands still |
| `quadrature.materials.audit=true` | Logs each block where the list of kinds and the rule differ, each the rule gives a kind that the list does not name, and the states of blocks that hold a material in each state |
| `quadrature.glDebug=true` | Logs the driver's messages of every severity, each distinct one when first seen and again when its count reaches ten, a hundred and so on, marked with whether it arrived during a terrain layer's draw |
| `quadrature.rays.bench=true` | With the scene's buffers: at a settled view, traces probe and shadow rays at three lengths in each tier's batch, from the scene's buffers and from a copy on the GPU, and logs GPU times, how the rays ended and the driver's moves of each buffer per set. `quadrature.rays.bench.settle`, `.every` and `.warm` set its seconds (3, 20, 2) |
| `quadrature.rays.check=true` | With the benchmark: traces a sample of each batch's rays again on the CPU over the buffers read back through staging copies, and logs `RAYS CHECK FAILED` where any word differs |
| `quadrature.terrain.neighbors=all` | Every change of a touching cube rebuilds a cube, as before the side-aware rebuilds: the source's own NEIGHBOR changes are followed and what a build read is not consulted |
| `quadrature.terrain.causes=true` | Counts each cube's hand-in to be built by the first of its causes (arrival, resend, blocks, light, biomes, a touching cube alone) and whether each rebuild drew the same as the mesh it replaced, by a hash of its vertices made on the build's thread; appended to the terrain's summary |
| `quadrature.rays.fetch=texture` | The trace reads the lookup and bricks through their buffer textures instead of storage blocks |

An out-of-memory error from the game loop is one the game catches itself: it frees what it can,
drops the world, shows its memory screen and logs nothing, so a run then waits on views that never
come. Quadrature logs it always, switch or none: `CLIENT OUT OF MEMORY in the game loop`, with the
error's stack trace, which names the allocation that failed, and the heap and direct buffers in use
once the game has freed what it could. An out-of-memory error on one of Cubature's build threads,
which fails that build, is logged the same way, as `in a build thread`.

In a real run, one forest tour with `quadrature.testOutOfMemory=45` and
`quadrature.exitOnOutOfMemory=true`, two views were captured with the hooks applied. The notice
then came once, 45 s after the world was entered, naming the forced allocation, with 574 of
8,192 MiB of heap and 70.7 MiB in 400 direct buffers in use. The client stopped within 3 s, and the
launcher failed at once, 2 min 21 s into the run. In that last second the game's warnings about
packets for the dropped world and four NullPointerExceptions appear: its own handling, ended by
the quit.

A settled frame is one with no cube waiting to be meshed.
## Limits

- The harness cannot wait for a settled state; it relies on discarding the first run.
- The first-run lighting difference, the light that differs between instances, the river
  in view 07 and the settled horizon difference are unexplained.
- One tour, one machine (RTX 3060), one resolution.
- Captured frames and references are kept out of the repository.

### When another card is measured

PLAN's capability work items 1 and 8 wait for a Turing card and an RTX 3050 8 GB. Each card's first runs
check predictions written before it arrives, in this order, on a fresh instance of the forest tour:

1. **The capability dump** (`-Dquadrature.capabilities=true`): the client's `quadrature-capabilities.txt`,
   set beside the RTX 3060's line by line. Expected to differ on a Turing card: the mesh shader limits
   (smaller task and mesh payloads), sparse page sizes, and memory; expected the same: every extension
   the capability set names (D20). A missing capability is a gate refusal, recorded as such, not worked
   around.
2. **The tier benchmark** (`-Dquadrature.tierBench`): its stored result against the prediction the
   calibration makes from the card's published speed relative to the RTX 3060 (ARCHITECTURE, 12.1.1, the
   four clusters). A score more than the band away from the prediction means a probe stands for its groups
   less well on this card than on the reference, which is looked for probe by probe before the tiers are
   trusted.
3. **The lowest tier's budgets** (D22): LOW's groups at 1920 by 1080 and 2560 by 1440 under the card's own
   8 GB, measured, against the budgets scaled from the RTX 3060 under an enforced 8 GB
   (`quadrature.tier.memoryLimitMiB`). The memory tier is read from the eviction count and the 1% low over
   the tour, as at run time.

A card is recorded under its own heading here, with its driver branch, and the four clusters' bounds are
revised from it.
