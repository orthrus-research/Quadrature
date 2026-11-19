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
| P6 Retirement | Removal of the Celeritas and Demonica integrations; documents updated | No third-party code remains in the render path |

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
| Phase 2 | Reversed depth on by default; a scripted scene with a screen open, for multiblock preview picking and other mods' own depth code |
| Phase 2 | Implementing the cube source in Cubature, and whether cube data can be shared between snapshots |
| Phase 2 | Face merging against per-face output with smooth lighting on |
| Phase 2 | The first-run lighting difference, settled with one light dump (recorded for Cubature) |
| Phase 2 | Light that differs between identically prepared instances: one tree in the forest tour, about two sky-light levels, in 5 of 24 instances, in the game's own draw too (recorded for Cubature) |
| Phase 2 | The river in forest tour view 07: between two captures it differs by nothing to 1,158 pixels, enough to fail the view alone, and not in step with what changed. Cause and scoring undecided |
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
| Depth strategy | Decided (D17): the whole frame uses reversed depth. Prototype built behind `-Dquadrature.reversedDepth=true`: depth calls are translated where they are made and the projection stack's base is a reversal. In a cubic instance all 17 forest tour views pass the near-terrain profile against the vanilla reference, worst 0.063% of pixels; with nine depth-setting mods of the surveyed pack added, the same instance with the switch on and off differs by at most 0.056%, in isolated distant pixels. Cost in four flight captures: median render work 1.51 ms off, 1.60 and 1.83 ms on. Depth pixel reads are translated; depth textures in other mods' shaders are reported only. The rewriter passes a 189-mod pack's 68,661 classes in 0.4 s. Open: the added mods' own drawing exercised (research R1, 2.2) |
| Survey of common mods | Done for one pack: `tools/mod_survey.py` over 189 client mods (research R5, 11.3) |
| State audit: the state the game and mods really leave, measured in a client | Done for two runs: the game alone and a cubic instance of the current stack, 41 state items over 120 frames each (research R1, 9.3). Fixed-function state is never restored between stages; modern state is never left changed |

Phase 1 is closed. Its condition, a cubic world matching the vanilla image within the
stated tolerance, is met for a frame Quadrature observes, and for one in which a stage of
its own runs inside the envelope. Reversed depth stays behind its switch until Phase 2
turns it on with the first terrain.

The development client reaches a world on its own with `./gradlew runClient -PdevWorld=<seconds>`,
logs stage times every five seconds, and quits.

## Phase 2 status

As of 2026-11-15.

| Step | Status |
|---|---|
| Cube source in Cubature | Built: events, versions, queries, snapshots, the build pool and the settled signal (Cubature's `docs/CUBE-SOURCE.md`), all in the pinned commit |
| Near terrain, first form | Built behind `-Dquadrature.terrain=own`: every loaded cube is meshed, once the six cubes around it are loaded, from a snapshot with the game's block models, on Cubature's build pool, nearest and in view first; the three opaque layers are drawn from those meshes in place of the game's draw. Translucent terrain, layers other mods add and block entities are still the game's, which also still builds its own meshes |
| Frame against the game's terrain | All 17 forest tour views pass the near-terrain profile against a game-drawn run of the same instance; the worst differs in 0.0624% of pixels, most of them on a river the game draws, and 1,228 pixels differ over the tour outside that river, single pixels with no direction. The game's rule for when a cube is meshed and the matrix it gives each render chunk are reproduced; without the matrix 14 views passed. Scoring is within one instance because light around some terrain differs between instances ([VERIFICATION.md](VERIFICATION.md)) |
| First numbers | 1.15 ms to mesh a cube, face links included; 584 MiB for 4,090 meshes in the game's 28-byte vertex format; 963 draws in a settled frame on average and 1,370 at most; opaque terrain 0.74 ms on the CPU and 2.27 ms on the GPU with the selection comparison running, against 0.65 and 2.36 ms for the game's own draw in the same instance; 0.25 ms for a search, reused on two frames in three |
| Why the game omits terrain Quadrature draws | Answered. Every cube Quadrature drew and the game did not was one the game's occlusion search leaves out, 93.8% of them because none of the cube's six neighbors was reached; they changed no pixels and cost draws ([VERIFICATION.md](VERIFICATION.md)) |
| A view, and what it sees | Built. Terrain is drawn for a view: the player's, filled from the game each frame. What a view sees is found once a frame by a search from the eye's cube through the faces of cubes that see each other, on the client thread; a second view, along the sun, is found each frame and not drawn. Quadrature now draws a median of 383 cubes a frame where the game draws 365 and it drew 1,145, for the same pixels ([ARCHITECTURE.md](ARCHITECTURE.md) 4.7 and 6.4) |
| Terrain memory and submission | Built. Meshes lie in an arena of 64 MiB pages written through the driver and never mapped, and each layer is drawn with one indirect multi-draw per page from commands written on the client thread: 35 draws a settled frame where one call per cube and layer made 938, for the same pixels, with opaque terrain at 0.58 ms on the CPU and 1.43 ms on the GPU against the game's 0.56 and 1.74 ms over the tour. Mapped pages were built first and cost three to four times the GPU time, since the driver keeps them in system memory. Free blocks are split and joined, so 640 MiB is taken for 592 MiB in use where reuse by exact size took 896 MiB. Placing is capped at 1 ms a frame, which one write a tour still exceeds, by up to 3.5 ms ([ARCHITECTURE.md](ARCHITECTURE.md) 6.3, [VERIFICATION.md](VERIFICATION.md)) |
| The light's view drawn | Built behind `-Dquadrature.shadow=true`. The view along the sun or moon is drawn for depth alone into a 4,096-texel, 64 MiB layer of an array texture, through a fixed 544-block box snapped to whole texels, with cutout alpha tested, and its matrices are published each frame. Nothing samples it yet: with it on, 13 of 17 tour views are identical byte for byte and the rest differ on the river. It is kept between frames and drawn again only when a mesh changes, the player moves to another texel of it or the light turns 0.1 degree: over the tour in a third of frames, 0.58 ms of GPU time a frame on average. Entities are not drawn into it ([ARCHITECTURE.md](ARCHITECTURE.md) 4.7, [VERIFICATION.md](VERIFICATION.md)) |
| The scene for lighting | Built behind `-Dquadrature.scene=true`: a 16-bit state number for every cell of every meshed cube, what each state does to light, three answers for a cell, a generation per cube and a report of changed cubes; 0.01 ms more to mesh a cube and 47 MiB for 5,617 cubes with cells of their own. With `-Dquadrature.scene.gpu=true`, a wrapped lookup of 25 cubes a side and occupancy bricks in two buffers a shader reads, 8.7 MiB, copied 64 bricks a frame; with `-Dquadrature.scene.states=true`, each cell's state as a byte into its cube's list of states and a table of what each state does to light, 69 MiB more ([ARCHITECTURE.md](ARCHITECTURE.md) 5.1) |
| Compact face stream, culling on the GPU, commands and their count written on the GPU | Not started |

## Capability and tier work

Decisions D20 to D23 (2026-11-18). Every supported card has one capability set, so what is
left is measurement, in this order; each runs through a queued GPU turn.

| # | Work | Unblocks | Status |
|---|---|---|---|
| 1 | A capability dump at startup in the game's context: mesh, subgroup, shading-rate and sparse-page limits, the interop identifiers and the full extension list | Twelve rows of the gap matrix; the memory axis | Done on the RTX 3060, 2026-11-18 ([research R1](research/R1-platform-frame-hosted.md), 1.8); to repeat on a Turing card |
| 2 | The light's depth redrawn spread over frames and scrolled, against its spikes | A precondition of every tier | Built and measured 2026-11-18: drawn by tiles, a new box over about 8 frames into a scratch. On the forest tour with both layers, against the same tour redrawn whole: GPU 0.17 ms a frame over all frames (from 2.01), 0.49 ms mean on frames that draw (from 5.63), worst 6.6 ms after world entry (from 16.0); 1% of drawing frames over 2 ms. Checked against boxes drawn whole: 122 of 129 exact, none past a thousandth of the span. Since: state put back from a mirror of the game's cache, no reads, and the targets prepared at world attach, which took the world-entry frame from 42 ms of CPU to 8. Open: a 19 ms GPU frame early after world entry, CPU spikes that swell every part together, both layers beginning a box in one frame |
| 3 | Opaque terrain against near radius and configuration, explaining 1.43 against 3.3 ms | The near radius of every tier | Done 2026-11-18 ([research R2](research/R2-terrain.md), 5.7; [verification](VERIFICATION.md), opaque terrain against the near radius). The two figures were two statistics and two configurations. At settled views, drawn forward: 0.96 to 2.32 ms from radius 8 to 20; with the targets, 2.15 to 3.74 ms. The radius is limited by meshing on the client thread, 70 to 88 s to fill a place at 20, not by drawing. Not measured: the targets' pass alone, and vertical radii below 12 |
| 4 | Rays a second through the occupancy bricks by compute | Indirect light, far shadows | Done 2026-11-19 ([research R4](research/R4-lighting-shaders.md), 7.8 and 7.8a; [architecture](ARCHITECTURE.md) 5.2; [verification](VERIFICATION.md), rays through the scene). A compute walk passing cubes of one state whole and cells one at a time, the same to the bit as `BrickTrace` in `core`: 3,981,312 rays compared, none differing. The bricks' coarser bits are not read, by the user's decision after a patent search (7.8a). On the RTX 3060, two million rays at 16, 64 and 256 blocks: probes 1.1, 4.9 and 12.2 ms, toward a low sun 1.4, 3.6 and 5.4 ms. Whole updates: MEDIUM 0.22 ms, HIGH 1.45 ms, MAX's two cascades about 8.5 ms; far shadows 2.6 ms a million rays. Radii 16 and 20 need only the larger lookup the scene already makes (15.0 and 23.1 MiB with the bricks); each cell's state does not fit at 20, so MAX keeps states within 12. A buffer the CPU reads back slows incoherent rays three to twelve times. Not measured: an open cave, a built-up area, ocean |
| 5 | The tier benchmark, calibrated and repeated across a driver update | Tier selection | Built and calibrated on the RTX 3060 2026-11-19 ([architecture](ARCHITECTURE.md), 12.1.1; [verification](VERIFICATION.md), the tier benchmark; [research R1](research/R1-platform-frame-hosted.md), 1.10 and 1.11), behind `-Dquadrature.tierBench`. A settling load runs until its own times agree, since the card's clock follows its load (a run in a low state read twice as slow). Score spread 0.5% at one place and on two instances, a standard deviation of 6.4% between places; band 15%. The card scores 0.96: HIGH at 1920 by 1080 and 2560 by 1440, MEDIUM at 3840 by 2160 on the boundary with LOW, open until the scaler is measured at that output. Parity with it on: 17 of 17. A driver change, simulated on the stored result, runs it again with the band held. The final code ran 64 runs on a fresh instance at radii 20 and 12, each place caught up with no cube unloaded, every choice the test's. Open: a real driver update (the user's step); the groups not yet counted; whether LOW and MEDIUM keep 67% and 77% (D23); the first-launch path off by default |
| 6 | Own temporal anti-aliasing and spatial scaler at 67% and 77% | The lower tiers' render scale | Built and measured 2026-11-19 ([ARCHITECTURE](ARCHITECTURE.md), 4.8; [research R4](research/R4-lighting-shaders.md), 7b; [verification](VERIFICATION.md), own temporal anti-aliasing and scaler), behind switches, off by default. At 2560 by 1440 the composite takes 1.00 ms of GPU at 67% and 1.07 ms at 77%, and pays for itself once the world's resolution-bound stages take 1.8 to 2.6 ms at 67% and 2.6 to 3.9 ms at 77%; the forest's terrain took 2.0 ms, so it did not. Against native: 28.3 dB and SSIM 0.81 at 67%, 28.9 dB and 0.84 at 77%. The scaler is Keys' separable cubic; a kernel steered along edges scored no better for more and is declined, and variance clipping is not used (D23). Open: the temporal pass's per-frame weights on the CPU, narrower barriers and taps read from shared memory, three costs not yet cut; motion vectors for hosted draws (D21) |
| 7 | The GL to Vulkan handoff and DLSS's cost, before the bridge is built | D21 | Done 2026-11-19 ([research R1](research/R1-platform-frame-hosted.md), 1.9; [verification](VERIFICATION.md), the handoff from OpenGL to Vulkan). Built in measurement form in the separately licensed `quadrature-nvidia` module, with a compute stand-in where DLSS would evaluate. The round trip costs a fixed 0.36 to 0.37 ms of GPU time at P0 (0.47 to 0.50 at P3) at 1080p and 1440p, and the client 0.06 ms a frame; the images take 32 and 57 MiB. DLSS's published cost on the RTX 3060 in Performance mode is 1.45 to 2.81 ms at 1080p and 2.39 to 5.05 ms at 1440p, so with the handoff about 1.82 to 3.18 and 2.75 to 5.41 ms. A mismatched device is refused, a resize remakes the images in 7 to 8 ms, a world change keeps them, and with the round trip on the picture passes 17 of 17 views. Not measured: DLSS itself, which needs its SDK and its license accepted by the user |
| 8 | The lowest tier's budgets on an RTX 3050 8 GB and a Turing 8 GB card | D22's floor | Waiting on hardware |

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
