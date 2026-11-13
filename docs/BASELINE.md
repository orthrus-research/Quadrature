# Baseline

What the current stack measures, as the bar Quadrature's phases are compared against.
Captures use Riven's observability harness (`../Riven`, `tools/riven_observe.py`); how
frames are compared is in [VERIFICATION.md](VERIFICATION.md).

## Conditions

Measured 2026-11-13.

| | |
|---|---|
| Renderer | Vanilla near renderer with Distant Horizons 3.3.3 (256-chunk radius) |
| World | The harness's benchmark world, seed 8675309; view distance 12 |
| Builds | Riven and lab observer frozen 2026-11-12; Cubature at Riven's pin |
| Runtime | Cleanroom, Java 26.0.2.1, 8 GiB heap on each side |
| Machine | 32 processors, NVIDIA GeForce RTX 3060 (12 GB), driver 610.88, Windows 11 |
| Frame cap | 120 frames per second (8.33 ms) |

GPU memory and utilization were sampled once a second from the driver beside each capture;
the harness itself records neither.

## Flight

Workload `cube-flight-lifecycle-v3`: load a full view, fly for 60 s at 16 blocks per second
as a creative player, settle for 30 s, then save and unload. Two captures; both passed.

Client, during the 60 s of flight:

| Measure | Capture 1 | Capture 2 |
|---|---|---|
| Frame time, median | 8.33 ms | 8.33 ms |
| Frame time, 95th percentile | 12.99 ms | 13.95 ms |
| Frame time, 99th percentile | 21.19 ms | 22.60 ms |
| Frame time, worst | 66.3 ms | 63.9 ms |
| Frames over 16.7 ms | 165 of 6,822 | 209 of 6,718 |
| Frames over 33.3 ms | 27 | 29 |
| Render work per frame, median | 5.84 ms | 6.23 ms |
| Render work per frame, 95th percentile | 9.93 ms | 10.28 ms |
| Render work per frame, 99th percentile | 13.56 ms | 14.50 ms |

Client, other scenarios (capture 1, capture 2):

| Scenario | Frame 99th percentile | Render work median | Render work 95th percentile |
|---|---|---|---|
| Full view loading (about 9 s) | 15.64, 19.15 ms | 1.94, 1.89 ms | 6.96, 6.86 ms |
| Settle (30 s) | 11.51, 11.80 ms | 4.42, 4.86 ms | 7.05, 7.50 ms |

Server tick (capture 1, capture 2):

| Scenario | Median | 95th percentile | 99th percentile | Worst |
|---|---|---|---|---|
| Full view loading | 13.4, 15.4 ms | 47.0, 43.1 ms | 79.8, 74.6 ms | 129, 99 ms |
| Flight | 6.4, 6.6 ms | 34.0, 35.3 ms | 39.5, 43.1 ms | 82, 101 ms |
| Settle | 3.5, 3.5 ms | 7.7, 7.8 ms | 15.7, 17.7 ms | 27, 36 ms |

Memory and load (capture 1, capture 2):

| Measure | Value |
|---|---|
| Client process, peak working set | 7.81, 7.76 GiB |
| Server process, peak working set | 7.22, 7.37 GiB |
| GPU memory, peak above the level before launch | 534, 529 MiB |
| GPU utilization, mean and peak | 26% and 51%, 25% and 48% |
| Cubes loaded at the end of flight | 72,065 and 73,334 |

## Celeritas, for comparison

The same flight workload, builds and settings with the shader pins on the client, so
Celeritas draws near terrain (Demonica present, no shader pack enabled). Two captures;
both passed. Phase 2's bar is the vanilla baseline above (decision D14); these show where
an established replacement renderer sits.

Client, during the 60 s of flight:

| Measure | Vanilla (two captures) | Celeritas (two captures) |
|---|---|---|
| Render work per frame, median | 5.84, 6.23 ms | 2.62, 2.44 ms |
| Render work per frame, 95th percentile | 9.93, 10.28 ms | 5.10, 4.63 ms |
| Render work per frame, 99th percentile | 13.56, 14.50 ms | 7.61, 7.06 ms |
| Frame time, 95th percentile | 12.99, 13.95 ms | 11.12, 11.22 ms |
| Frame time, 99th percentile | 21.19, 22.60 ms | 18.97, 16.52 ms |
| Frames over 16.7 ms | 165, 209 | 117, 67 |
| Frames over 33.3 ms | 27, 29 | 6, 8 |
| Frames drawn | 6,822, 6,718 | 7,192, 7,205 |
| Client process, peak working set | 7.81, 7.76 GiB | 7.55, 7.65 GiB |
| GPU memory, peak above pre-launch | 534, 529 MiB | 565, 559 MiB |
| GPU utilization, mean | 26%, 25% | 26%, 26% |

Server tick times are the same within the spread between captures, as expected: the
renderer is client-side.

### With a shader pack

The same again with Complementary Reimagined r5.9.3 enabled through Demonica. Two captures;
both passed, and the client log confirms the pack loaded (2.3 s to build its pipeline).

| Measure, during the 60 s of flight | Celeritas, no pack | Celeritas with the pack |
|---|---|---|
| Render work per frame, median | 2.62, 2.44 ms | 4.52, 4.55 ms |
| Render work per frame, 95th percentile | 5.10, 4.63 ms | 8.25, 8.28 ms |
| Render work per frame, 99th percentile | 7.61, 7.06 ms | 11.31, 11.68 ms |
| Frame time, 95th percentile | 11.12, 11.22 ms | 11.50, 11.76 ms |
| Frame time, 99th percentile | 18.97, 16.52 ms | 19.07, 19.89 ms |
| Frames over 16.7 ms | 117, 67 | 122, 117 |
| Frames over 33.3 ms | 6, 8 | 17, 17 |
| Frames drawn | 7,192, 7,205 | 7,068, 7,042 |
| Client process, peak working set | 7.55, 7.65 GiB | 7.81, 7.71 GiB |
| GPU memory, peak above pre-launch | 565, 559 MiB | 822, 812 MiB |
| GPU utilization, mean | 26%, 26% | 35%, 36% |
| GPU utilization, peak | 60%, 57% | 69%, 63% |

The pack adds about 2 ms of render work per frame on the client and about 250 MiB of GPU
memory, and raises mean GPU utilization by about ten points. Its render work stays below
the vanilla renderer's (4.5 ms against about 6 ms). The frame cap still holds: the client
does not fall below 120 frames per second at the median. While the full view loads, the
99th-percentile frame time rises to 30.86 and 22.78 ms, against 16.82 and 16.86 ms without
the pack.

Render work is measured on the client thread. GPU time spent in the pack's passes is not
in it; GPU utilization is the only view of that here.

Render work is measured by observer hooks written against the vanilla renderer. Celeritas
moves terrain work off that path, so the two render-work columns may not span the same
work; the ratio is indicative. The frame counts do not depend on those hooks and point the
same way.

Frames: the forest tour on a Celeritas instance without Distant Horizons differs from the
vanilla reference on every view, by 4.4% to 60.3% of pixels at threshold 8, with a mean
difference of 3 to 13. Celeritas renders about 6 to 8% darker overall, with a different sky
gradient; geometry matches. Two working renderers are therefore not pixel-equal, and a
comparison of Quadrature's own terrain against vanilla needs either identical shading or a
tolerance that admits an even offset.

## Stationary

Workload `terrain-pipeline-fixed-v1`: load a full view, then idle. One capture; passed.

| Scenario | Frame 95th | Frame 99th | Render work median | Render work 95th |
|---|---|---|---|---|
| Full view loading (9.9 s) | 12.84 ms | 19.83 ms | 2.08 ms | 7.25 ms |
| Idle (11.9 s) | 10.73 ms | 12.71 ms | 5.36 ms | 7.94 ms |

Client peak working set 5.62 GiB, server 6.62 GiB. The GPU sampler covered only part of
this capture; its peak of 381 MiB above the starting level is a lower bound.

## Reading these numbers

- The median frame time is the frame cap, so it shows the client is capped, not how much
  headroom it has. Compare percentiles and render work per frame.
- Two flight captures agree within about 7% on render work and on the 95th and 99th
  frame percentiles. Two captures are not enough to state a variance.
- The working-set figures include the 8 GiB heap reservation; they are not live heap use.

## What this baseline does not cover

- **GPU time.** Nothing here times the GPU; with a shader pack that is where much of the
  cost is.
- **Other packs.** One pack is measured; Riven also pins Complementary Unbound and BSL.
- **What render work spans under Celeritas.** See the caution in that section.
- **The canonical 32 km world.** The capture command takes no seed or scale; this is the
  benchmark world.
- **The 8 GB floor.** The card measured has 12 GB.
- **Mesh shaders against compute culling.** That measurement needs a terrain prototype.
- **Frames.** Reference images are in VERIFICATION.md's two tour instances, not here.

Captured instances: `quadrature-baseline-flight-01` and `-02` (vanilla),
`quadrature-baseline-celeritas-01` and `-02`, `quadrature-baseline-complementary-01` and
`-02` (shader pack), and `quadrature-baseline-lifecycle-01`
(stationary), each with a record in Riven's telemetry ledger; tour frames in
`quadrature-celeritas-01`.
