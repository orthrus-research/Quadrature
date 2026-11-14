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
| P0-L1 | Done: NRR 1.0 (decision D15), provenance rules and record format | [PROVENANCE.md](PROVENANCE.md) |

Not verified in Phase 0: how the gate's two screens look; the vendor and mesh shader
refusals in a client; a real second renderer; the canonical 32 km world; an 8 GB card.

### Carried forward

| Into | Item |
|---|---|
| Phase 1 | The gate also refuses Kirino's graphics state (research R1, 2.5) |
| Phase 1 | Stage boundaries by injection into the game's world pass |
| Phase 4 | The distant terrain composite and its transition band, by prototype (research R1, 2.2) |
| Phase 2 | The state audit repeated on more views and with a larger pack installed |
| Phase 2 | Implementing the cube source in Cubature, and whether cube data can be shared between snapshots |
| Phase 2 | Face merging against per-face output with smooth lighting on |
| Phase 2 | The first-run lighting difference, settled with one light dump (recorded for Cubature) |
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
| State envelope around hosted stages | Rules decided from the specification and a survey of 189 mods ([ARCHITECTURE.md](ARCHITECTURE.md), 4.4). Not implemented: nothing of Quadrature's draws yet |
| Depth strategy | Decided (D17): the whole frame uses reversed depth. Prototype built behind `-Dquadrature.reversedDepth=true`: depth calls are translated where they are made and the projection stack's base is a reversal. In a cubic instance all 17 forest tour views pass the near-terrain profile against the vanilla reference, worst 0.063% of pixels; with nine depth-setting mods of the surveyed pack added, the same instance with the switch on and off differs by at most 0.056%, in isolated distant pixels. Cost in four flight captures: median render work 1.51 ms off, 1.60 and 1.83 ms on. Depth pixel reads are translated; depth textures in other mods' shaders are reported only. The rewriter passes a 189-mod pack's 68,661 classes in 0.4 s. Open: the added mods' own drawing exercised (research R1, 2.2) |
| Survey of common mods | Done for one pack: `tools/mod_survey.py` over 189 client mods (research R5, 11.3) |
| State audit: the state the game and mods really leave, measured in a client | Done for two runs: the game alone and a cubic instance of the current stack, 41 state items over 120 frames each (research R1, 9.3). Fixed-function state is never restored between stages; modern state is never left changed |

Phase 1's closing condition, a cubic world matching the vanilla image within the stated
tolerance, is met for a frame in which Quadrature observes and draws nothing. The state
envelope and the two prototypes remain before the phase closes.

The development client reaches a world on its own with `./gradlew runClient -PdevWorld=<seconds>`,
logs stage times every five seconds, and quits.

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
