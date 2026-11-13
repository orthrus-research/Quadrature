# Plan

Seven phases. Each closes when something the projects depend on today has been replaced and
the replacement is proven. Requirements for each area are in
[REQUIREMENTS.md](REQUIREMENTS.md); decisions are in [DECISIONS.md](DECISIONS.md).

## Phases

| Phase | Builds | Closes when |
|---|---|---|
| P0 Foundations | Research answers, architecture, repository, platform gate, capture and comparison harness, baselines | A frame can be captured and compared automatically, and every requirement section has its research answered |
| P1 Frame | The pass graph hosting the game's own drawing, vanilla terrain still inside it | A cubic world matches the vanilla image within the stated tolerance |
| P2 Terrain | Cube source, meshing, visibility, submission | Quadrature's terrain replaces vanilla's at or above the baseline; Cubature's chunk-render Mixins are deleted |
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
| P0-A2 | Cube source contract, written with Cubature | Interface specification in both repositories |
| P0-B1 | Repository scaffold: Gradle modules, Java 25, CleanroomGradle, platform manifest | A build that produces an empty mod |
| P0-B2 | Platform gate: context check, foreign renderer detection, stop message | A client that starts on the supported platform and stops with a message elsewhere |
| P0-V1 | Capture and comparison harness: fixed world, camera path, tolerance | A repeatable capture and a pass or fail against a reference |
| P0-V2 | Baselines of the current stack on the canonical world: frame time, memory, images | Recorded baseline that P2 and P4 must meet |
| P0-D1 | Budgets: frame time, heap, GPU memory, disk | Targets written into the architecture document |
| P0-L1 | License choice and provenance template | `LICENSE`, provenance record format |

### Work order status

- P0-B1 is done (2026-11-13): `./gradlew build` on Temurin 25 produces the mod JAR with
  Java 25 class files. The JAR has not been loaded in a client yet; that is part of P0-B2.
- A reading pass on 2026-11-13 confirmed hosted rendering against the OpenGL 4.6
  compatibility and GLSL 4.60 specifications, and read Cleanroom's LWJGL shim, the LabPBR
  material standard, and sources on clustered shading and virtual shadow maps.
- Next: P0-B2 (platform gate), which also loads the JAR in a client for the first time.

### Research status

A first pass over R1 to R5 was made on 2026-11-13; findings are in
[research/](research/README.md). None of the five is closed. Each file ends with its
follow-ups. The ones that block the architecture document:

| Blocks | Follow-up |
|---|---|
| Pass graph (A1) | Reversed depth with hosted draws, by prototype (R1, 2.2) |
| Terrain technique (A1) | Mesh shaders against compute culling with indirect multi-draw, measured on an 8 GB Turing card or under an enforced 8 GB budget (R2, 5.6) |
| Distant terrain (P4) | Representation prototypes with memory per square kilometer (R3, 6.1) |
| Lighting (P5) | Measured costs for shadows and indirect light at long range (R4, 7.2 and 7.3) |
| Materials (P5) | How the material model relates to LabPBR (R4, 7.6) |

Order: R1 to R5 can run in parallel. A1 follows R1, R2 and R4. A2 follows R5. B1 and V2
depend on nothing and can start at once. B2 and V1 follow B1.

## Reading rules

Quadrature is an original implementation (decision D2).

- Allowed sources: the OpenGL and GLSL specifications, vendor documentation and
  presentations, published papers and talks, public interface documentation of the game,
  Forge and Cleanroom, and measurements.
- Other renderers' source code is not a source for implementation. Their public
  documentation of behavior, requirements and known conflicts may be read.
- Each technique adopted gets a provenance record: where the idea came from and under what
  terms.

## Risks

| Risk | Where it bites | Response |
|---|---|---|
| Performance below the baseline | P2 cannot close | Baseline first (P0-V2); measure from the first terrain frame |
| Mods that inject into vanilla chunk rendering | P2 and P3 | Survey in P0-R5; policy before P2 starts |
| Hosted fixed-function draws resist relighting | P5 | Research in P0-R1 before the pass graph is fixed |
| Distant terrain storage and bandwidth | P4 | Budgets in P0-D1; representation chosen against them |
| Scope growth | Every phase | A phase closes on its stated condition only |
