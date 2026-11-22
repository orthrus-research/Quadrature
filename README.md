<p align="center"><img src=".github/assets/banner.svg" alt="Quadrature by Orthrus Research" width="100%"></p>

<p align="center">
<a href="#status"><img alt="Status: in development" src="https://img.shields.io/badge/status-in%20development-000000?style=flat-square&labelColor=000000"></a>
<a href="LICENSE"><img alt="License: Apache-2.0" src="https://img.shields.io/badge/license-Apache--2.0-000000?style=flat-square&labelColor=000000"></a>
<a href="https://discord.gg/qCVqhXtSzR"><img alt="Discord" src="https://img.shields.io/badge/discord-join-000000?style=flat-square&labelColor=000000"></a>
</p>

Quadrature is a rendering pipeline for Minecraft 1.12.2 cubic worlds on
[Cleanroom](https://github.com/CleanroomMC/Cleanroom) with
[Cubature](https://github.com/orthrus-research/Cubature). It draws the whole frame.

## Status

In development: Phase 1 is closed and Phase 2 is in progress, with work on distant terrain and
shading begun. By default the mod checks the platform, stops with a message where it is not
supported, draws near terrain meshed from Cubature's cube source in the whole frame's reversed
depth, and times each stage of the frame on the CPU and the GPU; `-Dquadrature.terrain=game`
leaves terrain to the game. Behind switches, off by default, it draws distant terrain sampled
from the client terrain preview (`-Dquadrature.distant=own`) and a shading stage handed over in
a folder (`-Dquadrature.stage=true`). [PLAN.md](docs/PLAN.md) gives each phase's status.

## What Quadrature draws

- **Near terrain:** meshed directly from cubes.
- **Distant terrain:** as levels of detail.
- **Entities and block entities:** hosted from the game's own renderers.
- **Lighting and post-processing:** through its own shaders.

It is a new implementation. It contains no code from other renderers, shader loaders or
level-of-detail mods.

## For players and pack makers

| | |
|---|---|
| World | Cubic worlds (Cubature) |
| Loader | Cleanroom, Java 26 |
| Graphics API | OpenGL 4.6, compatibility profile |
| GPU | NVIDIA, Turing generation (RTX 20 series) or later, with at least 8 GB of memory |
| Driver | NVIDIA R580 branch or later; a warning below the current production branch |
| Other renderers | None installed; the game stops with a message if one is found |

These requirements are narrow on purpose. The design keeps each of them behind a named seam
so support can widen later without rewriting what sits above it; see
[the design frame](docs/REQUIREMENTS.md#design-frame).

## Building from source

Run Gradle with the JDK named in [platform.json](platform.json) (Temurin 26.0.2.1+1):

```sh
./gradlew build
```

Workbench runs the checks from a clean clone, `wb run ci.run project=quadrature`: the build
with every module's tests, the offline Mixin check, shader validation and the tool tests
([actions.toml](actions.toml); [VERIFICATION.md](docs/VERIFICATION.md#checks) says what each
checks). `./gradlew validateShaders` compiles every GLSL stage the mod holds with glslang
(`glslangValidator` on the path) without a game.

`./gradlew runClient` starts a development client. Adding `-PdevWorld=<seconds>` enters a
flat development world unattended, logs each stage's CPU and GPU time every five seconds,
and quits after that many seconds. Adding `-PstateAudit` also reads OpenGL state at every
stage boundary and reports what each stage leaves changed. The whole frame uses reversed depth
unless `-Dquadrature.reversedDepth=false` (`-PconventionalDepth` in the development client, which
leaves terrain to the game, having no Cubature); `-PdepthSelfTest` checks the projection
translations against the driver. `-Pprobe=offscreen` or `-Pprobe=visible` runs a
development stage of Quadrature's own inside the world pass.

Own terrain needs a cubic world, so it runs in an instance with Cubature, not in the development
client; without Cubature the game stops at loading with a message, unless started with
`-Dquadrature.terrain=game`. The build takes Cubature's API from one
JAR, pinned in `platform.json` by commit and hash and kept in `pins/` (or the folder `-PpinsDir`
names), which is not in the repository; the build stops if the file is missing or is not the
one pinned. `./gradlew refreshCubature` builds that JAR at the pinned commit
(`-PcubatureCommit=<commit>` to move the pin) in a worktree of the sibling checkout
(`../Cubature`, or `-PcubatureCheckout`), with the commit's own compile-only inputs, so what
the checkout has out plays no part; it prints the commit and hash to record. `./gradlew
refreshOrthLib` does the same for OrthLib's JAR, which the bridge to OrthLib's render interface
compiles against. Neither builds the pinned commit again while the pins folder holds its bytes,
or while Workbench's pin cache (`WB_PIN_CACHE`) has them. Instances are prepared with the same
file. Adding `-PtestMemoryFloorMiB=<n>`
raises the GPU memory floor for that run, to see the stop screen on a supported card; the
property can only raise the floor.

`platform.json` is the one place platform versions are pinned: Java, Cleanroom,
CleanroomGradle, OrthLib, Cubature and the graphics floor. The root module is the Minecraft host; `core` has
no Minecraft dependency.

## Documentation

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

## Community

Questions and discussion: [Discord](https://discord.gg/qCVqhXtSzR). Bugs and requests: this repository's issues. Security
and private matters: admin@orthrus.cc. Pull requests are welcome; see
[CONTRIBUTING.md](https://github.com/orthrus-research/.github/blob/main/CONTRIBUTING.md).

## License

Original Quadrature material is licensed under the [Apache License 2.0](LICENSE). Material
offered up to 2026-11-17 was offered under NRR 1.0, a public-domain dedication, and keeps
that grant. [NOTICE.md](NOTICE.md) lists what is covered separately.

## Related projects

- [Cubature](https://github.com/orthrus-research/Cubature): cubic world storage, delivery and
  lighting; supplies cubes to Quadrature
- [Riven](https://github.com/orthrus-research/Riven): terrain generation; supplies distant terrain
  without building cubes
- [OrthLib](https://orthrus.cc/projects/orthlib): shared library; carries the interface other mods
  use to reach Quadrature

---

<sub>Quadrature is an [Orthrus Research](https://orthrus.cc) project ·
[All projects](https://orthrus.cc/projects) · [Texts](https://orthrus.cc/texts) ·
[Discord](https://discord.gg/qCVqhXtSzR)</sub>
