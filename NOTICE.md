# Quadrature licensing

Quadrature
Copyright 2026 Orthrus Research and Quadrature contributors

Original Quadrature code, tools, configuration and documentation are licensed under the
[Apache License, Version 2.0](LICENSE) (SPDX `Apache-2.0`), except material with a separate
file, directory or upstream license. The Apache License does not relicense the components
below. Their own conditions continue to apply to them.

## Material offered under NRR 1.0

Quadrature was offered under NRR 1.0, a public-domain dedication with an unconditional
fallback license, up to and including commit 65af717 (2026-11-17). That grant is
irrevocable: material as it stood in those commits remains available on NRR's terms, and
the NRR 1.0 text is in the `LICENSE` file of those commits. Quadrature as offered from the
next commit on is licensed under the Apache License 2.0.

## Gradle wrapper

`gradlew`, `gradlew.bat` and `gradle/wrapper/gradle-wrapper.jar` are the Gradle wrapper,
distributed by the Gradle project under the Apache License 2.0. They are build tooling and
are not part of the mod JAR.

## quadrature-nvidia

The `quadrature-nvidia` module is a separately licensed part of Quadrature (decision D19 in
`docs/DECISIONS.md`). Its terms are set by Orthrus Research and are still pending; the Apache
License does not cover it. `quadrature-nvidia/NOTICE.md` states its status. Quadrature runs
without it.

## What Quadrature does not contain

Quadrature is an original implementation (decision D2 in `docs/DECISIONS.md`). It contains
no code from other renderers, shader loaders or level-of-detail mods, and no NVIDIA SDK
material. `docs/PROVENANCE.md` records where each technique it uses came from.
