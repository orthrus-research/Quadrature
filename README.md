# Quadrature

**Status:** Phase 1 is closed and Phase 2 is in progress, with work on distant terrain and
shading begun. By default the mod checks the platform, stops with a message where it is not
supported, and times each stage of the game's own world rendering on the CPU and the GPU.
Behind switches, all off by default, it draws near terrain meshed from Cubature's cube source
(`-Dquadrature.terrain=own`), distant terrain sampled from the client terrain preview
(`-Dquadrature.distant=own`) and a shading stage handed over in a folder
(`-Dquadrature.stage=true`). [PLAN.md](docs/PLAN.md) gives each phase's status.

Quadrature is a rendering pipeline for Minecraft 1.12.2 cubic worlds on Cleanroom with
Cubature. It draws the whole frame: near terrain meshed directly from cubes, distant terrain
as levels of detail, entities and block entities hosted from the game's own renderers, and
lighting and post-processing through its own shaders.

It is a new implementation. It contains no code from other renderers, shader loaders or
level-of-detail mods.

## Requirements to run

| | |
|---|---|
| World | Cubic worlds (Cubature) |
| Loader | Cleanroom, Java 25 |
| Graphics API | OpenGL 4.6, compatibility profile |
| GPU | NVIDIA, Turing generation (RTX 20 series) or later, with at least 8 GB of memory |
| Driver | NVIDIA R580 branch or later; a warning below the current production branch |
| Other renderers | None installed; the game stops with a message if one is found |

These requirements are narrow on purpose. The design keeps each of them behind a named seam
so support can widen later without rewriting what sits above it; see
[the design frame](docs/REQUIREMENTS.md#design-frame).

## Building

Run Gradle with the JDK named in [platform.json](platform.json) (Temurin 25):

```sh
./gradlew build
```

`./gradlew runClient` starts a development client. Adding `-PdevWorld=<seconds>` enters a
flat development world unattended, logs each stage's CPU and GPU time every five seconds,
and quits after that many seconds. Adding `-PstateAudit` also reads OpenGL state at every
stage boundary and reports what each stage leaves changed. Adding `-PreversedDepth` switches the
whole frame to reversed depth, a prototype that is off by default; `-PdepthSelfTest` with it checks
the projection translations against the driver. `-Pprobe=offscreen` or `-Pprobe=visible` runs a
development stage of Quadrature's own inside the world pass.

Own terrain needs a cubic world, so it runs in an instance with Cubature, not in the development
client: start the game with `-Dquadrature.terrain=own`. The build takes Cubature's API from one
JAR, pinned in `platform.json` by commit and hash and kept in `pins/`, which is not in the
repository; the build stops if the file is missing or is not the one pinned. `./gradlew
refreshCubature` copies the JAR built in the sibling checkout (`../Cubature`, which must be
clean) into `pins/` and prints the commit and hash to record. Instances are prepared with the
same file. Adding `-PtestMemoryFloorMiB=<n>`
raises the GPU memory floor for that run, to see the stop screen on a supported card; the
property can only raise the floor.

`platform.json` is the one place platform versions are pinned: Java, Cleanroom,
CleanroomGradle, Cubature and the graphics floor. The root module is the Minecraft host; `core` has
no Minecraft dependency.

## Documents

- [docs/DECISIONS.md](docs/DECISIONS.md): standing decisions and their reasons
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): how the pipeline is put together, and which
  choices are decided, default or still candidates
- [docs/PLAN.md](docs/PLAN.md): phases, what closes each one, and the first work orders
- [docs/REQUIREMENTS.md](docs/REQUIREMENTS.md): what each part of the pipeline must do, and
  the questions a research pass has to answer before implementation

- [docs/VERIFICATION.md](docs/VERIFICATION.md): how frames are captured and compared, and
  the measured repeatability
- [docs/BASELINE.md](docs/BASELINE.md): what the current stack measures, as the bar to meet
- [docs/research/](docs/research/README.md): research findings per work order, with the
  basis of each and what is still open

- [docs/CUBE-SOURCE.md](docs/CUBE-SOURCE.md): what Quadrature relies on from Cubature's cube
  source contract
- [docs/PROVENANCE.md](docs/PROVENANCE.md): where each technique came from, and the rules
  for what may be used

## License

Original Quadrature material is licensed under the [Apache License 2.0](LICENSE). Material
offered up to 2026-11-17 was offered under NRR 1.0, a public-domain dedication, and keeps
that grant. [NOTICE.md](NOTICE.md) lists what is covered separately.

## Related projects

- `../Cubature`: cubic world storage, delivery and lighting; supplies cubes to Quadrature
- `../Riven`: terrain generation; supplies distant terrain without building cubes
- `../OrthLib`: shared library; carries the interface other mods use to reach Quadrature
