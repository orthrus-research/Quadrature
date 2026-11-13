# Quadrature

**Status:** Phase 0. The mod checks the platform and stops with a message where it is not
supported; no rendering code exists yet. No license is granted; see [NOTICE](NOTICE).

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
| Other renderers | None installed; the game stops with a message if one is found |

These requirements are narrow on purpose. The design keeps each of them behind a named seam
so support can widen later without rewriting what sits above it; see
[the design frame](docs/REQUIREMENTS.md#design-frame).

## Building

Run Gradle with the JDK named in [platform.json](platform.json) (Temurin 25):

```sh
./gradlew build
```

`./gradlew runClient` starts a development client. Adding `-PtestMemoryFloorMiB=<n>`
raises the GPU memory floor for that run, to see the stop screen on a supported card; the
property can only raise the floor.

`platform.json` is the one place platform versions are pinned: Java, Cleanroom,
CleanroomGradle and the graphics floor. The root module is the Minecraft host; `core` has
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

## Related projects

- `../Cubature`: cubic world storage, delivery and lighting; supplies cubes to Quadrature
- `../Riven`: terrain generation; supplies distant terrain without building cubes
- `../OrthLib`: shared library; carries the interface other mods use to reach Quadrature
