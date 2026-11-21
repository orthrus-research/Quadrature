# Plan

Seven phases. Each closes when something the projects depend on today has been replaced and
the replacement is proven. Requirements for each area are in
[REQUIREMENTS.md](REQUIREMENTS.md); decisions are in [DECISIONS.md](DECISIONS.md).

## Phases

| Phase | Builds | Closes when |
|---|---|---|
| P0 Foundations | Research answers, architecture, repository, platform gate, capture and comparison harness, baselines | A frame can be captured and compared automatically, and every requirement section has its research answered |
| P1 Frame | The pass graph hosting the game's own drawing, vanilla terrain still inside it | A cubic world matches the vanilla image within the stated tolerance |
| P2 Terrain | Cube source, meshing, visibility, submission | Quadrature's terrain replaces vanilla's at or above the vanilla renderer's measured baseline ([BASELINE.md](BASELINE.md)); Cubature's chunk-render Mixins are deleted |
| P3 Mod interface and post-processing | Layers, emissive, bloom, stage hooks, state containment | GTCEu: Reloaded's bloom runs on it and its renderer Mixins are deleted |
| P4 Levels of detail | Distant source, detail levels, streaming, transitions | Distant Horizons and Cubature's integration with it are removed, at parity or better |
| P5 Shaders | Shading stages, shader format, first shader, the suite | Quadrature's shaders light near terrain, distant terrain, entities and the hand, and can be switched in game |
| P6 Retirement | Removal of the remaining third-party render integrations; documents updated | No third-party code remains in the render path |

The pass graph built in P1 is designed for P5 from the start, so shading stages are added
to an existing graph and the frame is not rewritten. Levels of detail come before shaders so
the shading stages are written once with distant terrain in them.

## Dependencies between phases

- P1 needs P0's platform gate and comparison harness.
- P2 needs the cube source from Cubature and P1's graph.
- P3 needs P2's layers. P4 needs P2's visibility and submission.
- P5 needs P3's state containment and P4's distant terrain.
- P3 and P4 do not depend on each other and may overlap.

## P0 work orders

Research comes first; its answers decide the architecture.

| ID | Work | Output |
|---|---|---|
| P0-R1 | Research pass over sections 1, 2 and 9 of the requirements (platform, pass graph, hosted rendering) | Findings with sources, per question |
| P0-R2 | Research pass over sections 4 and 5 (meshing, visibility and submission), including the mesh shader measurement on a Turing card | Findings with sources; measured comparison |
| P0-R3 | Research pass over section 6 (levels of detail) | Findings with sources |
| P0-R4 | Research pass over sections 7 and 8 (lighting, shader system) | Findings with sources |
| P0-R5 | Research pass over sections 3, 10, 11 (scene sources, mod interface, other mods) | Findings with sources, including the list of mods that inject into chunk rendering |
| P0-A1 | Architecture document: taxonomy, pass graph, capability set, module layout | `docs/ARCHITECTURE.md` |
| P0-A2 | Cube source contract, written with Cubature | The contract in Cubature's repository; what Quadrature relies on here |
| P0-B1 | Repository scaffold: Gradle modules, Java 25, CleanroomGradle, platform manifest | A build that produces an empty mod |
| P0-B2 | Platform gate: context check, foreign renderer detection, stop message | A client that starts on the supported platform and stops with a message elsewhere |
| P0-V1 | Capture and comparison harness: fixed world, camera path, tolerance | A repeatable capture and a pass or fail against a reference |
| P0-V2 | Baselines of the current stack on the canonical world: frame time, memory, images | Recorded baseline that P2 and P4 must meet |
| P0-D1 | Budgets: frame time, heap, GPU memory, disk | Targets written into the architecture document |
| P0-L1 | License choice and provenance template | `LICENSE`, provenance record format |

### Phase 0 status

As of 2026-11-13. Phase 0 is closed. What it could not settle without running code is
carried into the phase that produces that code.

| ID | Status | Where |
|---|---|---|
| P0-R1 to R5 | First pass and reading pass done; findings marked verified, known or needing measurement | [research/](research/README.md) |
| P0-A1 | Done | [ARCHITECTURE.md](ARCHITECTURE.md) |
| P0-A2 | Done as a draft: the contract is owned by Cubature and drafted in its repository (D16), with proposed answers to its open points; not implemented | [CUBE-SOURCE.md](CUBE-SOURCE.md) |
| P0-B1 | Done: `./gradlew build` on Temurin 25 produces the mod JAR with Java 25 class files | |
| P0-B2 | Done: ten unit tests; verified in the development client by log lines and live objects, not by eye | |
| P0-V1 | Done: frames compared against profiles; a settled near-terrain instance reproduces 13 of 17 frames exactly | [VERIFICATION.md](VERIFICATION.md) |
| P0-V2 | Done on the available machine: vanilla, Celeritas and Celeritas with a shader pack, two flight captures each | [BASELINE.md](BASELINE.md) |
| P0-D1 | Provisional budgets set, to be revised by each phase's measurements | [ARCHITECTURE.md](ARCHITECTURE.md), section 12 |
| P0-L1 | Done: the license (decision D15; Apache-2.0 since 2026-11-18), provenance rules and record format | [PROVENANCE.md](PROVENANCE.md) |

Not verified in Phase 0: how the gate's two screens look; the vendor and mesh shader
refusals in a client; a real second renderer; the canonical 32 km world; an 8 GB card.

### Carried forward

| Into | Item |
|---|---|
| Phase 1 | The gate also refuses Kirino's graphics state (research R1, 2.5) |
| Phase 1 | Stage boundaries by injection into the game's world pass |
| Phase 4 | The distant terrain composite and its transition band, by prototype (research R1, 2.2) |
| Phase 2 | The state audit repeated on more views and with a larger pack installed |
| Phase 2 | Hosted drawing that lies exactly on Quadrature's terrain: its depth and the game's fixed-function depth are not bit-identical (research R1, 9a.7) |
| Phase 2 | Implementing the cube source in Cubature, and whether cube data can be shared between snapshots |
| Phase 2 | Face merging against per-face output with smooth lighting on |
| Phase 2 | The first-run lighting difference, settled with one light dump (recorded for Cubature) |
| Phase 2 | Light that differs between identically prepared instances: one tree in the forest tour, about two sky-light levels, in 5 of 24 instances, in the game's own draw too (recorded for Cubature) |
| Phase 2 | A cube the game still draws in the second a teleport lands, which the cube source does not report as loaded (recorded for Cubature) |
| Phase 2 | Drawing the second view: its own projection, fog, alpha test and textures, and a depth target |
| Phase 5 | Entities and block entities in a light's view, which reaches into hosted drawing |
| Phase 5 | Scene data for light beyond what is seen: opacity per block, which the mesher finds and does not keep, material and emission, a generation per cube, and changed regions |
| Phase 2 | One multiplication by a composed projection and cube matrix in the terrain shader in place of two, against the 1,251 single pixels that still differ from the game's terrain: untested |
| Phase 4 | Distant terrain representation, by memory per square kilometer |
| Phase 4 | Mesh shaders as a second technique, measured on distant terrain |
| Phase 4 | The unexplained 1.67% difference on a settled horizon |
| Phase 5 | Costs of shadows and indirect light at long range |
| Phase 5 | The material model and its mapping from LabPBR; the shader format |
| Any | What render work spans under Celeritas, if that comparison is used again |

## Phase 1 status

As of 2026-11-13.

| Step | Status |
|---|---|
| Pass graph in `core`: declared stages and resources, ordering, dropping unused stages | Done; 8 unit tests |
| Stage timeline and statistics in `core` | Done; 7 unit tests |
| Stage boundaries from the game's world pass, without redirecting anything | Done. In the development client: 14 hosted stages, 2,379 frames, no stage out of order, unknown or outside a frame |
| CPU and GPU time per stage | Done. No GPU result was dropped in that run |
| The gate refuses Kirino's graphics state | Done. Verified in the development client: loading halts with the message |
| A cubic world, with Cubature | Done. Installed from its JAR in a Riven instance: the Mixin configuration registered through the manifest, and 43,452 frames ran with no stage out of order, unknown or outside a frame and no GPU result dropped |
| The frame compared against the vanilla reference | Done. All 17 forest tour views pass the near-terrain profile against the vanilla reference; 6 are pixel-identical and the worst changes 0.039% of pixels ([VERIFICATION.md](VERIFICATION.md)) |
| State envelope around hosted stages | Done. A development stage of Quadrature's own draws with a program, a vertex array object, a buffer and its own framebuffer between two hosted stages. The state audit shows it leaving none of 41 items changed, and in a cubic instance the frame is unchanged by it (research R1, 9a.7) |
| Depth strategy | Decided (D17): the whole frame uses reversed depth. Built, and on by default since 2026-11-21 (`-Dquadrature.reversedDepth=false` turns it off): depth calls are translated where they are made and the projection stack's base is a reversal. In a cubic instance all 17 forest tour views pass the near-terrain profile against the vanilla reference, worst 0.063% of pixels; with nine depth-setting mods of the surveyed pack added, the same instance with the switch on and off differs by at most 0.056%, in isolated distant pixels. Cost in four flight captures: median render work 1.51 ms off, 1.60 and 1.83 ms on. Depth pixel reads are translated; depth textures in other mods' shaders are reported only. The rewriter passes a 189-mod pack's 68,661 classes in 0.4 s. Open: the added mods' own drawing exercised (research R1, 2.2) |
| Survey of common mods | Done for one pack: `tools/mod_survey.py` over 189 client mods (research R5, 11.3) |
| State audit: the state the game and mods really leave, measured in a client | Done for two runs: the game alone and a cubic instance of the current stack, 41 state items over 120 frames each (research R1, 9.3). Fixed-function state is never restored between stages; modern state is never left changed |

Phase 1 is closed. Its condition, a cubic world matching the vanilla image within the
stated tolerance, is met for a frame Quadrature observes, and for one in which a stage of
its own runs inside the envelope. Phase 2 turned reversed depth on by default with own terrain (2026-11-21).

The development client reaches a world on its own with `./gradlew runClient -PdevWorld=<seconds>`,
logs stage times every five seconds, and quits.

## Phase 2 status

As of 2026-11-21. Own terrain, reversed depth and face records are the defaults: with no switches,
16 of 17 forest views pass against the game's own drawing (view 07's river aside) and the baseline flight
holds the game's frame times ([VERIFICATION.md](VERIFICATION.md), own terrain by default). What remains
for the phase to close: Cubature's chunk-render Mixins deleted, which is Cubature's change; ModularUI's
schema renderer driven as GregTech's was (GregTech's picks the same blocks in reversed depth); the river in
view 07 settled.

| Step | Status |
|---|---|
| Cube source in Cubature | Built: events, versions, queries, snapshots, the build pool and the settled signal (Cubature's `docs/CUBE-SOURCE.md`), all in the pinned commit |
| Near terrain, first form | Built, and the default since 2026-11-21 with reversed depth and face records (`-Dquadrature.terrain=game` leaves terrain to the game): every loaded cube is meshed, once the six cubes around it are loaded, from a snapshot with the game's block models, on Cubature's build pool, nearest and in view first; the three opaque layers are drawn from those meshes in place of the game's draw. Translucent terrain, layers other mods add and block entities are still the game's, which also still builds its own meshes |
| Frame against the game's terrain | All 17 forest tour views pass the near-terrain profile against a game-drawn run of the same instance; the worst differs in 0.0624% of pixels, most of them on a river the game draws, and 1,228 pixels differ over the tour outside that river, single pixels with no direction. The game's rule for when a cube is meshed and the matrix it gives each render chunk are reproduced; without the matrix 14 views passed. Scoring is within one instance because light around some terrain differs between instances ([VERIFICATION.md](VERIFICATION.md)) |
| First numbers | 1.15 ms to mesh a cube, face links included; 584 MiB for 4,090 meshes in the game's 28-byte vertex format; 963 draws in a settled frame on average and 1,370 at most; opaque terrain 0.74 ms on the CPU and 2.27 ms on the GPU with the selection comparison running, against 0.65 and 2.36 ms for the game's own draw in the same instance; 0.25 ms for a search, reused on two frames in three |
| Why the game omits terrain Quadrature draws | Answered. Every cube Quadrature drew and the game did not was one the game's occlusion search leaves out, 93.8% of them because none of the cube's six neighbors was reached; they changed no pixels and cost draws ([VERIFICATION.md](VERIFICATION.md)) |
| A view, and what it sees | Built. Terrain is drawn for a view: the player's, filled from the game each frame. What a view sees is found once a frame by a search from the eye's cube through the faces of cubes that see each other, on the client thread; a second view, along the sun, is found each frame and not drawn. Quadrature now draws a median of 383 cubes a frame where the game draws 365 and it drew 1,145, for the same pixels ([ARCHITECTURE.md](ARCHITECTURE.md) 4.7 and 6.4) |
| Terrain memory and submission | Built. Meshes lie in an arena of 64 MiB pages written through the driver and never mapped, and each layer is drawn with one indirect multi-draw per page from commands written on the client thread: 35 draws a settled frame where one call per cube and layer made 938, for the same pixels, with opaque terrain at 0.58 ms on the CPU and 1.43 ms on the GPU against the game's 0.56 and 1.74 ms over the tour. Mapped pages were built first and cost three to four times the GPU time, since the driver keeps them in system memory. Free blocks are split and joined, so 640 MiB is taken for 592 MiB in use where reuse by exact size took 896 MiB. Placing is capped at 1 ms a frame, which one write a tour still exceeds, by up to 3.5 ms ([ARCHITECTURE.md](ARCHITECTURE.md) 6.3, [VERIFICATION.md](VERIFICATION.md)) |
| The light's view drawn | Built behind `-Dquadrature.shadow=true`. The view along the sun or moon is drawn for depth alone into a 4,096-texel, 64 MiB layer of an array texture, through a fixed 544-block box snapped to whole texels, with cutout alpha tested, and its matrices are published each frame. Nothing samples it yet: with it on, 13 of 17 tour views are identical byte for byte and the rest differ on the river. It is kept between frames and drawn again only when a mesh changes, the player moves to another texel of it or the light turns 0.1 degree: over the tour in a third of frames, 0.58 ms of GPU time a frame on average. Entities are not drawn into it ([ARCHITECTURE.md](ARCHITECTURE.md) 4.7, [VERIFICATION.md](VERIFICATION.md)) |
| The scene for lighting | Built behind `-Dquadrature.scene=true`: a 16-bit state number for every cell of every meshed cube, what each state does to light, three answers for a cell, a generation per cube and a report of changed cubes; 0.01 ms more to mesh a cube and 47 MiB for 5,617 cubes with cells of their own. With `-Dquadrature.scene.gpu=true`, a wrapped lookup of 25 cubes a side and occupancy bricks in two buffers a shader reads, 8.7 MiB, copied 64 bricks a frame; with `-Dquadrature.scene.states=true`, each cell's state as a byte into its cube's list of states and a table of what each state does to light, 69 MiB more ([ARCHITECTURE.md](ARCHITECTURE.md) 5.1) |
| Compact face stream, culling on the GPU, commands and their count written on the GPU | Face records built (`quadrature.faces`). Culling measured first, 2026-11-20: a depth pyramid and a test of each cube against it, leaving nothing out, find 34% of the player's drawn vertices behind other terrain over the forest tour and 45% over the cave tour, and 28% and 56% of the light's wide box hidden from the light; the test costs 0.03 ms a frame and the pyramid as first built 0.55 to 0.68 ms at low clocks ([VERIFICATION.md](VERIFICATION.md), what a depth pyramid would leave out). Built in first form the same day behind `quadrature.terrain.cull=gpu`: two phases, every picture unchanged (17 of 17, 406 exact pyramid checks), 32% and 44% of the player's vertices left out; on the RTX 3060 at 2560 by 1440, radius 16, it costs about 0.3 ms more than it saves, measured frame by frame (turns of fixed length locked onto the tours' timing), so it is off by default ([VERIFICATION.md](VERIFICATION.md), culling on the GPU). Open: whether cards slower at geometry gain, and the light's view |

## Capability and tier work

Decisions D20 to D23 (2026-11-18). Every supported card has one capability set, so what is
left is measurement, in this order; each runs through a queued GPU turn.

| # | Work | Unblocks | Status |
|---|---|---|---|
| 1 | A capability dump at startup in the game's context: mesh, subgroup, shading-rate and sparse-page limits, the interop identifiers and the full extension list | Twelve rows of the gap matrix; the memory axis | Done on the RTX 3060, 2026-11-18 ([research R1](research/R1-platform-frame-hosted.md), 1.8); to repeat on a Turing card |
| 2 | The light's depth redrawn spread over frames and scrolled, against its spikes | A precondition of every tier | Built and measured 2026-11-18: drawn by tiles, a new box over about 8 frames into a scratch. On the forest tour with both layers, against the same tour redrawn whole: GPU 0.17 ms a frame over all frames (from 2.01), 0.49 ms mean on frames that draw (from 5.63), worst 6.6 ms after world entry (from 16.0); 1% of drawing frames over 2 ms. Checked against boxes drawn whole: 122 of 129 exact, none past a thousandth of the span. Since: state put back from a mirror of the game's cache, no reads, and the targets prepared at world attach, which took the world-entry frame from 42 ms of CPU to 8. Since: the spread raised to 32 frames, which took the frames over 2 ms from 565 to 10 a forest tour; the CPU spikes were the collector's pauses, gone under ZGC. and the first frame's 15 ms, a copy of a box with nothing in it, gone since such a box clears its layer instead (0.55 ms) ([verification](VERIFICATION.md), the light's view) |
| 3 | Opaque terrain against near radius and configuration, explaining 1.43 against 3.3 ms | The near radius of every tier | Done 2026-11-18 ([research R2](research/R2-terrain.md), 5.7; [verification](VERIFICATION.md), opaque terrain against the near radius). The two figures were two statistics and two configurations. At settled views, drawn forward: 0.96 to 2.32 ms from radius 8 to 20; with the targets, 2.15 to 3.74 ms. The radius is limited by meshing on the client thread, 70 to 88 s to fill a place at 20, not by drawing. Since measured: the targets' pass alone, 0.91 ms at radius 12, 0.52 of the forward draw; and the meshes by height, 80% of the vertices one or two cubes from the player's and half the meshes seven or more away ([verification](VERIFICATION.md), opaque terrain against the near radius) |
| 4 | Rays a second through the occupancy bricks by compute | Indirect light, far shadows | Done 2026-11-19 ([research R4](research/R4-lighting-shaders.md), 7.8 and 7.8a; [architecture](ARCHITECTURE.md) 5.2; [verification](VERIFICATION.md), rays through the scene). A compute walk passing cubes of one state whole and cells one at a time, the same to the bit as `BrickTrace` in `core`: 3,981,312 rays compared, none differing. The bricks' coarser bits are not read, by the user's decision after a patent search (7.8a). On the RTX 3060, two million rays at 16, 64 and 256 blocks: probes 1.1, 4.9 and 12.2 ms, toward a low sun 1.4, 3.6 and 5.4 ms. Whole updates: MEDIUM 0.22 ms, HIGH 1.45 ms, MAX's two cascades about 8.5 ms; far shadows 2.6 ms a million rays. Radii 16 and 20 need only the larger lookup the scene already makes (15.0 and 23.1 MiB with the bricks); each cell's state does not fit at 20, so MAX keeps states within 12. A buffer the CPU reads back slows incoherent rays three to twelve times. Open water and rugged rock with open caves measured since: neither dearer than the dwell tour's places for any class, nor a town of 36 stone buildings built by a tour ([verification](VERIFICATION.md), rays through the scene) |
| 5 | The tier benchmark, calibrated and repeated across a driver update | Tier selection | Built and calibrated on the RTX 3060 2026-11-19 ([architecture](ARCHITECTURE.md), 12.1.1; [verification](VERIFICATION.md), the tier benchmark; [research R1](research/R1-platform-frame-hosted.md), 1.10 and 1.11), behind `-Dquadrature.tierBench`. A settling load runs until its own times agree, since the card's clock follows its load (a run in a low state read twice as slow). Score spread 0.5% at one place and on two instances, a standard deviation of 6.4% between places; band 15%. The card scores 0.96: HIGH at 1920 by 1080 and 2560 by 1440, MEDIUM at 3840 by 2160 with a fit of 1.11 since the scaler was measured at that output ([verification](VERIFICATION.md), the scaler's costs), the slowest places below one. Parity with it on: 17 of 17. A driver change, simulated on the stored result, runs it again with the band held. The final code ran 64 runs on a fresh instance at radii 20 and 12, each place caught up with no cube unloaded, every choice the test's. Since 2026-11-20 LOW and MEDIUM render below the output only where the benchmark predicts that costs less than drawing natively (D23, benchmark version 4). The stored tiers set the built groups at startup (render scale and temporal pass, distant reach, the light's near layer), verified on the first-launch path ([architecture](ARCHITECTURE.md), 12.1.1). Distant terrain is counted since the same day (benchmark version 6, its faces at each reach measured at two places): this card then takes HIGH at 1280 by 720, MEDIUM at 1920 by 1080 and 2560 by 1440, and LOW at 3840 by 2160 ([verification](VERIFICATION.md), distant terrain's cost). Open: a real driver update (the user's step); the groups not yet counted (fog and clouds, ambient occlusion, the shading the shadow filter and indirect light add); the first-launch path off by default |
| 6 | Own temporal anti-aliasing and spatial scaler at 67% and 77% | The lower tiers' render scale | Built and measured 2026-11-19 ([ARCHITECTURE](ARCHITECTURE.md), 4.8; [research R4](research/R4-lighting-shaders.md), 7b; [verification](VERIFICATION.md), own temporal anti-aliasing and scaler), behind switches, off by default. At 2560 by 1440 the composite takes 1.00 ms of GPU at 67% and 1.07 ms at 77%, and pays for itself once the world's resolution-bound stages take 1.8 to 2.6 ms at 67% and 2.6 to 3.9 ms at 77%; the forest's terrain took 2.0 ms, so it did not. Against native: 28.3 dB and SSIM 0.81 at 67%, 28.9 dB and 0.84 at 77%. The scaler is Keys' separable cubic; a kernel steered along edges scored no better for more and is declined, and variance clipping is not used (D23). Of the three costs once to be cut, the weights from the CPU, a narrower barrier and taps read straight from the tile, none saved time measured against the programs before them in the same run, at 2560 by 1440 and 3840 by 2160 ([verification](VERIFICATION.md), the scaler's costs); the barrier is narrowed, the programs stay. Entities' own motion from two frames' transforms ([research R4](research/R4-lighting-shaders.md), 7b.5) is built behind `quadrature.taa.entityMotion` since 2026-11-20 and moves a walking golem's pixels and nothing else; in the same frame, the history it reads lies half as far from the frame as the camera's motion would have read it (0.044 against 0.083 in luma over 1.16 million pixels), and against native the player's moving body seen from behind scores 0.22 to 0.45 dB higher in every phase ([verification](VERIFICATION.md), entities' own motion). Open: motion for block entities and particles (D21) |
| 7 | The GL to Vulkan handoff and DLSS's cost, before the bridge is built | D21 | Done 2026-11-19 ([research R1](research/R1-platform-frame-hosted.md), 1.9; [verification](VERIFICATION.md), the handoff from OpenGL to Vulkan). Built in measurement form in the separately licensed `quadrature-nvidia` module, with a compute stand-in where DLSS would evaluate. The round trip costs a fixed 0.36 to 0.37 ms of GPU time at P0 (0.47 to 0.50 at P3) at 1080p and 1440p, and the client 0.06 ms a frame; the images take 32 and 57 MiB. DLSS's published cost on the RTX 3060 in Performance mode is 1.45 to 2.81 ms at 1080p and 2.39 to 5.05 ms at 1440p, so with the handoff about 1.82 to 3.18 and 2.75 to 5.41 ms. A mismatched device is refused, a resize remakes the images in 7 to 8 ms, a world change keeps them, and with the round trip on the picture passes 17 of 17 views. DLSS itself, through the shim since 2026-11-19 ([verification](VERIFICATION.md), DLSS through the handoff): NGX's own work at P0 takes 1.54 and 2.20 ms with preset K at 1080p and 1440p and 2.55 and 4.53 ms with preset M, and the round trip with it 1.89 to 4.84 ms. Since the same day DLSS also takes the scaler's picture ([verification](VERIFICATION.md), DLSS taking the scaler's picture): at 2560 by 1440 against native, 30.37 dB and SSIM 0.897 at 0.67 and 29.21 dB and 0.858 at 0.5, against the own scaler's 28.31 dB and 0.811 at 0.67, for 2.9 ms of GPU at P0 against 1.0 ms at P3 |
| 8 | The lowest tier's budgets on an RTX 3050 8 GB and a Turing 8 GB card | D22's floor | Waiting on hardware |

### Reference cards and drivers

The standard is the seven most used consumer cards on the Steam Hardware Survey that meet the supported
platform (Turing or later, at least 8 GB), reduced to the fewest cards that differ in architecture, memory
or speed class, and the driver branches NVIDIA still supports. Source: the survey for October 2026 (the
same seven in September), and NVIDIA's branch table of 2026-11-04.

| Rank | Card | Share, Oct 2026 | Chip, architecture | Memory | Stands for it |
|---|---|---|---|---|---|
| 1 | RTX 3060 | 3.78% | GA106, Ampere | 12 GB | RTX 3060 |
| 2 | RTX 5070 | 3.78% | GB205, Blackwell | 12 GB | RTX 5060 (architecture), RTX 3060 (12 GB) |
| 3 | RTX 4060 Laptop GPU | 3.60% | AD107, Ada | 8 GB | RTX 4060 (the same chip; the laptop's own difference is below) |
| 4 | RTX 4060 | 3.51% | AD107, Ada | 8 GB | RTX 4060 |
| 5 | RTX 3050 | 3.25% | GA106/GA107, Ampere | 8 GB (a 6 GB model is below the floor) | RTX 3050 8 GB |
| 6 | RTX 5060 | 3.15% | GB206, Blackwell | 8 GB | RTX 5060 |
| 7 | RTX 5060 Ti | 2.70% | GB206, Blackwell | 8 or 16 GB | RTX 5060 (the same chip) |

The reference cards are therefore four, and the floor a fifth:

- **RTX 3060 12 GB**: Ampere at 12 GB, the reference all tiers are calibrated on (ours, under Windows; a
  rented one under Linux was calibrated against it on 2026-11-20, recorded in the results database).
- **RTX 3050 8 GB**: the slowest of the seven, at the floor's memory: LOW's budgets (item 8).
- **RTX 4060 8 GB**: Ada, standing also for the RTX 4060 Laptop GPU's chip.
- **RTX 5060 8 GB**: Blackwell, standing for the RTX 5060 Ti and RTX 5070's architecture; the 5070's
  12 GB is the RTX 3060's memory class.
- **Turing 8 GB (RTX 2070 SUPER or RTX 2070)**: not among the seven (the most used Turing card at 8 GB, the
  RTX 2060 SUPER, holds 0.76%), but it is the supported platform's floor and lacks two optional extensions
  and half of one limit (VERIFICATION, rented cards): item 1's dump and item 8's floor.

Driver branches, all of which support Turing and later: **R580** (long-term support to June 2028, the
branch older installations stay on), **R595** (production to March 2027, the Linux distributions' current
driver; 595.99.02 is NVIDIA's latest Linux release), **R620** (long-term support to September 2029; the
current Windows Game Ready driver, 621.14 of 2026-11-04). New feature branches (610, 615) are short-lived
and are not targets; the reference RTX 3060 moves from 610.88 to R620 at item 5's driver update. Each branch
is measured on at least two cards and each card on at least one branch:

| Card | R580 | R595 | R620 |
|---|---|---|---|
| RTX 3060 | rented (Linux) | rented (Linux) | ours (Windows), after item 5's update |
| RTX 3050 8 GB | rented (the one listing) | | |
| RTX 4060 8 GB | | rented | |
| RTX 5060 8 GB | | | rented (or an RTX 5060 Ti 8 GB) |
| Turing 8 GB | rented | | |

Rented cards run Linux; players run Windows. Each rented result is read through the RTX 3060's pair of the
same plan on both systems, whose long passes differed (4K terrain 3x, the light's view 16% dearer on the
Windows desktop, cause not established). Rented hosts must give at least 8 effective cores, or the tour does
not settle. Where a card is scarce (on 2026-11-20 one RTX 3050, five RTX 4060 and six RTX 5060 listings),
the stand-in is the same chip class: the RTX 4060 Ti 8 GB for Ada and the RTX 5060 Ti 8 GB for Blackwell;
the RTX 3050 has none.

Not covered by renting: laptops. Two of the top eight are laptop chips (RTX 4060 and 5060 Laptop GPU). They
run power-limited, which the tier benchmark measures as speed, and on hybrid graphics, where the game's Java
process can be given the integrated GPU, which the gate refuses: the refusal's message and a run on a real
laptop are open.

The market snapshots, taken every 30 minutes into the results database, list these cards and their stand-ins
(RTX 3050, 3060, 4060, 4060 Ti, 5060, 5060 Ti, 5070, 2060 SUPER, 2070 SUPER, 2070), so that price,
availability and driver are known for each before a rental is asked for.

## Cross-mod diagnostics

Asked 2026-11-19, for Riven worlds: which mod costs the most, what the engine still does that
Quadrature, Cubature or Riven could take over for speed or freedom, and where the Cleanroom
integration can improve. One tool, `tools/diagnostics.py` ([DIAGNOSTICS.md](DIAGNOSTICS.md)), is
built whole before any run; the runs and the analysis follow.

| # | Work | Status |
|---|---|---|
| 1 | Run driver: a plan of tours and flights on a fresh instance, both games recorded with `DebugNonSafepoints`, recorder chunks small enough that a killed client keeps its data, GC logs, the GPU sampled, logs and recordings collected | Built |
| 2 | Attribution: each thread's CPU, from `jdk.ThreadCPULoad`, split by owner (a mod, the engine, Cleanroom); the engine's time on a mod's behalf and the engine methods it calls; each owner's hot methods; allocation, monitor waits, GC | Built; checked against one recording: the server thread's CPU within 20% of Cubature's tick clock, the process's threads within 0.2 cores of its CPU |
| 3 | Mixin index: each mod's Mixin targets and overwrites, read from its jar, so a game method a mod overwrote is charged to that mod | Built (`tools/mod_index.py`) |
| 4 | Static survey: each mod's event subscriptions, the engine systems it patches, the threads and executors it starts | Built; the pack's ten jars (nine mods) read, 240 Mixins among them |
| 5 | Windows: startup, each tour view, each flight phase | Built |
| 6 | Compilation by owner: compiles, their time, deoptimizations and failures | Built |
| 7 | Preflight: artifacts and their hashes, switch combinations, tour checks, instance names, free disk | Built (`check`) |
| 8 | Ablation: a mod removed from both sides of a fresh instance, its cost measured by difference | Built; refuses a removal that leaves a mod without one it requires |
| 9 | Report: the costliest owner per critical resource, the engine work each mod leans on, the engine's CPU by system beside the mods patching it, Cleanroom's own costs, runs compared, a Markdown readout | Built; run on the base configuration's cave tour and route flight |
| 9a | Clocks against samples: the plan's named methods timed by JFR's method timing, each set beside the time its samples imply, and a verdict per thread. Execution samples see a thread only while it runs Java code: on the base run they put autosave discovery at up to 71% of the server thread in a minute, three times its own budget | Built and calibrated 2026-11-19: the meshing pool's samples agree with its clock (0.89 to 0.99); the server thread's are off three to seven times either way, so its measure is the clocks and Cubature's phase times ([DIAGNOSTICS.md](DIAGNOSTICS.md)) |
| 10 | Runs: base, full stack, then ablations of the mods attribution cannot settle. Plans are kept with the evidence; the generating cave tour is `tools/tours/caves-8675309.json`; the flight a 2,400-block square loop at 16 blocks a second with a 15 s stop, flown by the motion helper's route (0.2.0) | Done 2026-11-19 on four fresh instances: base, full stack without DLSS (its handoff switch was missing, which `check` now refuses), base with clocks, and full stack with DLSS and clocks; every route complete with no tick held. No ablation was needed |
| 11 | Code analysis of what the runs find, with requisitions for Riven and Cubature | Done 2026-11-19: requisitions raised for Riven (the distant preview's work per column, ore placement, allocation and recompiles), Cubature (construction by runs, surface tracking by columns, the entity pass's cube lookups) and GTCEu Reloaded (its render-layer hook). Findings below |
| 12 | Engine work to take over: the engine's CPU by system on the server tick and the frame, set against what each of our mods already patches, ranked by what taking it over would save and what it would free | Designed 2026-11-19 (below). 12a done 2026-11-19 ([verification](VERIFICATION.md), the game's chunk rebuild without the opaque layers): the game's rebuilds take 60% less time, every picture unchanged; with it, the walk is the default mesher for model blocks. 12c: the light matrix from a table of the cube, and quads lit straight into the buffer, both exact; together the walk takes 0.833 of the dispatcher's time on the same blocks (the light matrix from a table of the cube) |
| 13 | Cleanroom integration: Forge's, Cleanroom's, Mixin's and LWJGL's own CPU, startup by owner, event dispatch and compilation churn, each as a change to propose to Cleanroom or to work around in our mods | First steps taken 2026-11-20; none is Cleanroom's to change. The near rebuilds on the frame thread closed with 12a. The cancellable injects in the per-block layer test no longer run in the game's rebuild for the layers Quadrature draws, and in Quadrature's meshing the answers are kept per state ([verification](VERIFICATION.md), layers kept per state). JFR's method timing skips a class whose rewriting needs a game class because it reads class files as resources by their runtime names, which the game's jar does not hold (its classes are named as they load); the caller is clocked instead. The client's compilation grows with Riven's and Quadrature's own code, not with any flag the lab passes. Clocked on the generating cave tour, `DimensionManager.getIDs` takes 0.14 ms a second (its samples overstated it 18 times), and Forge's event dispatch 42 ms a second only inclusive of its handlers, two thirds of whose samples are Cubature's own world tick, which runs from a world-tick handler and which Cubature's phase lines already count |

Findings, on the base configuration unless named. Figures on the server tick and the frame are clocks or the
features' own counters; pool figures are samples, which agree with the clock on the meshing pool.

- Riven owns the most CPU and allocation. Its `generateCube` took 426 ms of each second of the generating cave tour.
  Ore placement is about a third of the generation pool's samples: each rock block hashes the 27 node cells around
  it for every ore body within reach. With distant terrain the preview pool takes 1.1 to 1.9 cores, 306 ms of
  sampling for a 66-by-66 tile. Half of that is continental kernel evaluations, of which a coastal column makes five
  (its sample and four probes for the coast normal) and the climate a sixth.
- 34 to 38% of each side's sampled allocation is the two-element array that `StrictMath.sin` and `cos` allocate on
  every call (OpenJDK 26.0.2's Java port of fdlibm). Another 15% of the client's is the lambdas Riven's cell memo
  allocates on every lookup, hits included. With ZGC the collector itself takes little CPU.
- Cubature on the server tick, by clock: the delivery tick, installs included, took 3.2 ms a tick on the cave tour.
  After cuts A, C1 and A2 the largest install phases left are construction (72 microseconds a cube) and surface
  tracking (57). Both loop per block through the game's storage and Cubature's height map.
- The game still meshes everything. Its chunk-meshing threads took 188.6 CPU s over the cave tour, against 134.9 s
  for Quadrature's. It also rebuilds render chunks within about 28 blocks of the player on the frame thread, since
  Forge's `alwaysSetupTerrainOffThread` is off. Those rebuilds were 49% of the frame thread's samples over the base
  cave tour, up to 85% in a minute after an arrival, and 5% over the full stack's.
- Inside Quadrature's meshing the game's and Forge's code take about 80%: Forge's smooth lighting and quad pipeline
  about 35%, the game's face test 21%, model quads 10%, and GTCEu Reloaded's render-layer hook 10%. On the pinned
  build that hook also asks Forge's mod list for CTM on every call; the fix is on a branch of GTCEu Reloaded, not
  yet on master.
- Full stack with DLSS against base, route flight: the GPU render bracket's median went from 2.4 to 7.7 ms and its
  99th percentile from 4.8 to 10.1 ms. The frame's 99th percentile went from 13.4 to 16.7 ms. Distant terrain took
  2.3 ms of GPU a frame and DLSS 0.80 ms at the median.
- For item 13:
  - the near-rebuild switch above;
  - Mixin's cancellable injects in the game's per-block layer test (Mixin is 12% of the game's meshing threads);
  - JFR's method timing cannot instrument classes that resolve game classes through the launch class loader;
  - the client's compilation doubles with the full stack, and Riven's methods are half of its long compilations.

### Engine take-over (item 12)

Under own terrain the game's opaque-layer meshes are never drawn, but the game still builds them. It also builds the
rest the same way: the translucent layer, the block entity lists and its visibility graph. Quadrature takes this
work over in steps. Before the next step starts, each is verified against a game-drawn run of the same instance
(17 of 17 views under the near profile) and measured on the base plan's cave tour.

| Step | Change | What must hold | Measure |
|---|---|---|---|
| 12a | Under own terrain the game's chunk rebuild answers false to its per-block layer test for the three opaque layers, through a wrap of `Block.canRenderInLayer` in `RenderChunk.rebuildChunk` that chains with other mods' wraps. The game then renders only translucent blocks; its block entities and visibility graph are found as before | Every picture unchanged. Only the draw Quadrature replaces reads the opaque layers' buffers; the game's only other reader of a compiled chunk's layers is the debug screen's count of non-empty render chunks. The pack's survey checks for mods that read them | The game's meshing threads' CPU (188.6 s), the frame thread's rebuild share, GTCEu Reloaded's share |
| 12b | Forge's `alwaysSetupTerrainOffThread` turned on while own terrain is, unless 12a leaves the frame thread's rebuilds negligible | No rebuild on the frame thread. The game's translucent terrain near the player may follow a change a frame later | The frame's 99th percentile over the cave tour's arrivals |
| 12c | Quadrature lights baked-model quads itself. Each vertex takes its light and shade from a padded copy of the snapshot's light and occlusion, built once a cube, in place of Forge's per-block light matrix and its vertex lighter. Blocks with renderers of their own (fluids, CodeChickenLib's) keep those | Pictures within the near profile against the game's lighting; Quadrature's own formulation, recorded in PROVENANCE | Meshing time a cube (1.40 ms), builds a second |
| 12d | The face test by table, for blocks that keep the game's own test: per state, which faces its shape fills and whether it is an opaque cube, read in place of `Block.shouldSideBeRendered` | The same faces, checked per cube against the game's test as `quadrature.faces.check` does | As 12c |
| 12e | Translucent terrain in Quadrature, meshed with the other layers and ordered back to front per cube as the eye moves. The game's chunk rebuild then stops altogether | Translucent views within the near profile. First, a policy for mods that add to the game's chunk meshes (the risk below) | The game's meshing threads idle |
| 12f | Block entities and entities come from Quadrature's view: the game's entity pass reads the visible cubes that hold them, not the game's render chunks | The same entities and block entities drawn | The frame thread's entity pass. Cubature's lookup per render chunk (6.5 to 9.4% of the frame thread's samples) is gone |

12a and 12b need no new technique. Steps 12c to 12f each get a PROVENANCE record in the commit that adds them.

12a is done: the game's chunk rebuild answers false to the per-block layer test for the three opaque layers
wherever own terrain draws that render chunk's world (`MixinRenderChunkLayers`, `GameChunkRebuilds`,
`-Dquadrature.terrain.gameOpaque=build` for the game's full rebuild). Quadrature's own meshing drew every block
through the game's dispatcher unless face records were on; the walk that face records use, the same to the bit
and with no position made for a face test, is now the default for model blocks (`-Dquadrature.terrain.mesh=dispatcher`
for the old path). That covers most of what 12d set out to save, the positions the game's face test makes; a table
of each state's faces would save only two calls a face, so 12d waits for a profile that shows them.

12f's measure, Cubature's cube lookup for every render chunk in the entity pass, is met in Cubature instead (its branch for underground costs, 46a9d9e5): each column counts its cubes holding entities, and of 179 million requests over a forest tour with golems in view 99.8% were answered without a lookup, none wrongly. What remained of 12f, the entity pass taking Quadrature's visible cubes in place of the game's render chunks, is not needed: under the policy for 12e the game's rebuild still finds the block entity lists, the lookup it cost is met, and under own terrain the game's terrain setup and its whole entity pass take 0.016 and 0.067 ms of the client thread a frame at the median of a forest tour, so the most it could save is under a tenth of a millisecond. It is taken up again if a frame's time is ever traced to them.

12b is not needed while 12a holds: the game's rebuilds on the client thread took 0.14 s over the forest tour and
0.76 s over the generating cave tour once they left the opaque layers out, about a tenth of a percent of the
client thread, where they had been 49% of its samples over the base cave tour. It is taken up again if a frame's
time is ever traced to them.

**Policy for 12e (decided 2026-11-20).** Stopping the game's chunk rebuild altogether would take away
what mods add through it: layers of their own (GregTech CEu's bloom layer, CTM's extra layers a quad), changes to
the layer a block draws in (Fluidlogged API), and events inside the rebuild (RenderChunk rebuildChunk Hooks, which
BetterFoliage and others use); in the surveyed pack five mods patch `RenderChunk` and three
`BlockRendererDispatcher` (research R5, 11.3). The rebuild therefore stays as the point where they join, and 12a's
rule extends to every layer Quadrature draws: a render chunk with nothing else in it costs the game only its pass
over the blocks for the visibility graph and the block entity lists. A layer, a hook or an event a mod adds keeps
working as it does today, drawn by the game. Quadrature meshes another mod's layer only where that mod is examined
and the result passes its own views.

12e is done under that policy: under own terrain Quadrature meshes the translucent layer
through the game's dispatcher, sorts each cube's quads by the game's rule as the eye moves, and draws the cubes
farthest first at the game's translucent pass, and the game's rebuild leaves the layer out (ARCHITECTURE 6.5). On the
water tour it draws what the game draws at all 7 views against own terrain with the game's translucent layer, and
the game's rebuilds took 26.2 s against 36.8 s with only the opaque layers left out (VERIFICATION, translucent
terrain), and the forest tour passes all 17 views against the game's draw with it. `-Dquadrature.terrain.translucent=game`
leaves the layer to the game.

## Reading rules

The rules for what may be read and used, and a record for each technique, are in
[PROVENANCE.md](PROVENANCE.md).

## Risks

| Risk | Where it bites | Response |
|---|---|---|
| Performance below the vanilla baseline | P2 cannot close | Baseline recorded (P0-V2); measure from the first terrain frame |
| Mods that inject into vanilla chunk rendering | P2 and P3 | Survey in P0-R5; policy before P2 starts |
| Hosted fixed-function draws resist relighting | P5 | Research in P0-R1 before the pass graph is fixed |
| Distant terrain storage and bandwidth | P4 | Budgets in P0-D1; representation chosen against them |
| Scope growth | Every phase | A phase closes on its stated condition only |
