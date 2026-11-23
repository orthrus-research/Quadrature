"""Applies Quadrature's Mixins to the game's classes with no game running, and fails if one does not apply.

A compile cannot tell whether an injector still finds its target: the refmap only maps the names.
OrthLib's offline Mixin check (`orthlib-mixinkit`, the file the set names) reads the target
classes as bytes, applies the configuration as the game would, and reports every hook. This runs
it over the development-named classes the build compiles against and what the build just made, with
`--strict`, so an injector that hooks nothing fails too.

    python tools/mixin_check.py [--java <java>] [--mixinkit <orthlib-mixinkit jar>] [--out report.txt] [--refmap]

Run `./gradlew build` first. Gradle is asked for the compile class path through an init script, so
no build file changes. By default it checks development names only. `--refmap` then checks the
shipped jar's refmap: the game's jars are renamed to production names with the mapping the build
reobfuscates with, and the shipped jar's Mixins are applied to them through its refmap, strictly,
so a member the refmap does not map fails. The launcher is `--java`, else QUADRATURE_MIXIN_JAVA,
else the JDK that JAVA_HOME names.
"""

import argparse
import os
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONFIGURATION = "quadrature.mixins.json"
GAME_CLASS = "net/minecraft/client/Minecraft.class"
TOOL_LIBRARIES = re.compile(
    r"^(cleanmix|mixinextras-common|asm|asm-tree|asm-commons|asm-util|asm-analysis|gson|guava|failureaccess)-\d"
)
INIT_SCRIPT = """
allprojects {
    if (it == rootProject) {
        afterEvaluate {
            tasks.register('printMainClasspath') {
                doLast {
                    println 'CLASSPATH_BEGIN'
                    sourceSets.main.compileClasspath.files.each { println it }
                    println 'CLASSPATH_END'
                    def set = project.extensions.findByName('set')
                    if (set != null) { println 'MIXINKIT ' + set.jar('orthlib', 'mixinkit').singleFile }
                }
            }
        }
    }
}
"""


def compile_classpath() -> tuple[list[str], str | None]:
    """The main compile class path, and the orthlib-mixinkit file the set names (None when the build names none)."""
    # Off Windows the wrapper script runs under sh: a checkout need not mark it executable.
    wrapper = [str(ROOT / "gradlew.bat")] if os.name == "nt" else ["sh", str(ROOT / "gradlew")]
    with tempfile.TemporaryDirectory() as scratch:
        script = Path(scratch) / "classpath.init.gradle"
        script.write_text(INIT_SCRIPT, encoding="utf-8")
        result = subprocess.run(
            [*wrapper, "--no-daemon", "--console=plain", "-q", "--init-script", str(script), "printMainClasspath"],
            cwd=ROOT,
            capture_output=True,
            text=True,
        )
    if result.returncode != 0:
        sys.exit("Gradle could not give the compile class path:\n" + result.stdout + result.stderr)
    lines = result.stdout.splitlines()
    kit = next((line[len("MIXINKIT "):].strip() for line in lines if line.startswith("MIXINKIT ")), None)
    try:
        return [line.strip() for line in lines[lines.index("CLASSPATH_BEGIN") + 1 : lines.index("CLASSPATH_END")]], kit
    except ValueError:
        sys.exit("Gradle printed no class path:\n" + result.stdout)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    home = os.environ.get("JAVA_HOME")
    launcher = Path(home) / "bin" / ("java.exe" if os.name == "nt" else "java") if home else None
    parser.add_argument("--java", default=os.environ.get("QUADRATURE_MIXIN_JAVA") or (str(launcher) if launcher else None),
                        help="a Java launcher (default: QUADRATURE_MIXIN_JAVA, else JAVA_HOME's)")
    parser.add_argument("--mixinkit", help="orthlib-mixinkit jar (default: the one the set names)")
    parser.add_argument("--out", default=str(ROOT / "build" / "mixin-report.txt"))
    parser.add_argument("--refmap", action="store_true",
                        help="then apply the shipped jar's Mixins through its refmap to production-named classes")
    args = parser.parse_args()
    if not args.java:
        sys.exit("Name a Java launcher with --java or QUADRATURE_MIXIN_JAVA, or set JAVA_HOME.")
    # The check reads development names, where a shadowed member always resolves. In the released
    # jar its name is not rewritten by this build, and the game stops when the Mixin is applied.
    shadows = [
        f"{source.relative_to(ROOT).as_posix()}:{number}"
        for source in sorted((ROOT / "src" / "main" / "java").rglob("*.java"))
        for number, line in enumerate(source.read_text(encoding="utf-8").splitlines(), 1)
        if line.strip().startswith("@Shadow")
    ]
    if shadows:
        sys.exit(
            "A Mixin shadows a game member, which this build does not rename for the released jar: "
            + ", ".join(shadows)
            + ". Use an accessor or the game's own public state."
        )
    classes = ROOT / "build" / "classes" / "java" / "main"
    resources = ROOT / "build" / "resources" / "main"
    if not (resources / CONFIGURATION).is_file() or not classes.is_dir():
        sys.exit("Nothing built: run ./gradlew build first.")
    classpath, named = compile_classpath()
    kit = Path(args.mixinkit) if args.mixinkit else (Path(named) if named else None)
    if kit is None:
        sys.exit("The build names no orthlib-mixinkit file; pass --mixinkit.")
    tool = [str(kit)] + [entry for entry in classpath if TOOL_LIBRARIES.match(Path(entry).name)]
    kit_command = [args.java, "--enable-final-field-mutation=ALL-UNNAMED", "-cp", os.pathsep.join(tool),
                   "dev.orthlib.testkit.mixin.MixinCheck"]
    report = Path(args.out)
    code = apply(kit_command, report, [str(classes), str(resources), *classpath], "Mixin check")
    if args.refmap and code == 0:
        code = apply_shipped(kit_command, report.with_name(report.stem + "-refmap" + report.suffix), classpath)
    sys.exit(code)


def apply(kit_command: list[str], report: Path, classpath: list[str], title: str, production: bool = False) -> int:
    """Applies the configuration over the class path, strictly, and prints the report's counts and failures."""
    code = subprocess.run([*kit_command, "apply", "--out", str(report), "--config", CONFIGURATION,
                           *(["--production"] if production else []), "--strict", *classpath]).returncode
    if report.is_file():
        rows = report.read_text(encoding="utf-8").splitlines()
        counts = {kind: sum(row.startswith(kind + "\t") for row in rows) for kind in ("TARGET", "HOOK", "FAIL", "MISSING")}
        print(f"{title}: " + ", ".join(f"{count} {kind.lower()}" for kind, count in counts.items()))
        for row in rows:
            if row.startswith(("FAIL\t", "MISSING\t")):
                print("  " + row[:400])
    return code


def apply_shipped(kit_command: list[str], report: Path, classpath: list[str]) -> int:
    """The shipped jar's Mixins applied through its refmap to the game's classes under production names: the
    game's jars renamed by the mapping the build reobfuscates with, as the game runs them."""
    mapping = ROOT / "build" / "cleanroom_gradle" / "userdev" / "mcp2srg.tsrg"
    shipped = [jar for jar in sorted((ROOT / "build" / "libs").glob("quadrature-*.jar"))
               if not jar.name.endswith(("-sources.jar", "-mcp.jar"))]
    if not mapping.is_file() or len(shipped) != 1:
        print(f"Nothing to check the refmap with: {mapping} and one shipped jar in build/libs ({shipped}).")
        return 1
    game = [entry for entry in classpath if entry.endswith(".jar") and holds(Path(entry), GAME_CLASS)]
    if not game:
        print(f"No jar on the compile class path holds {GAME_CLASS}.")
        return 1
    with tempfile.TemporaryDirectory() as scratch:
        renamed = subprocess.run([*kit_command, "rename", "--mapping", str(mapping), "--out", scratch, *game],
                                 capture_output=True, text=True)
        if renamed.returncode != 0:
            print("Renaming the game's jars failed:\n" + renamed.stdout + renamed.stderr)
            return 1
        jars = [line.strip() for line in renamed.stdout.splitlines() if line.strip().endswith(".jar")]
        rest = [entry for entry in classpath if entry not in game]
        return apply(kit_command, report, [str(shipped[0]), *jars, *rest], "Refmap check", production=True)


def holds(jar: Path, name: str) -> bool:
    try:
        with zipfile.ZipFile(jar) as archive:
            return name in archive.namelist()
    except (OSError, zipfile.BadZipFile):
        return False


if __name__ == "__main__":
    main()
