"""Applies Quadrature's Mixins to the game's classes with no game running, and fails if one does not apply.

A compile cannot tell whether an injector still finds its target: the refmap only maps the names.
OrthLib's offline Mixin check (`orthlib-mixinkit`, a sibling checkout's build) reads the target
classes as bytes, applies the configuration as the game would, and reports every hook. This runs
it over the development-named classes the build compiles against and what the build just made, with
`--strict`, so an injector that hooks nothing fails too.

    python tools/mixin_check.py --java <java 26> [--mixinkit <orthlib-mixinkit jar>] [--out report.txt]

Run `./gradlew build` first. Gradle is asked for the compile class path through an init script, so
no build file changes. It checks development names only; the shipped jar's refmap is not exercised.
"""

import argparse
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONFIGURATION = "quadrature.mixins.json"
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
                }
            }
        }
    }
}
"""


def compile_classpath() -> list[str]:
    wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    with tempfile.TemporaryDirectory() as scratch:
        script = Path(scratch) / "classpath.init.gradle"
        script.write_text(INIT_SCRIPT, encoding="utf-8")
        result = subprocess.run(
            [str(wrapper), "--no-daemon", "--console=plain", "-q", "--init-script", str(script), "printMainClasspath"],
            cwd=ROOT,
            capture_output=True,
            text=True,
        )
    if result.returncode != 0:
        sys.exit("Gradle could not give the compile class path:\n" + result.stdout + result.stderr)
    lines = result.stdout.splitlines()
    try:
        return [line.strip() for line in lines[lines.index("CLASSPATH_BEGIN") + 1 : lines.index("CLASSPATH_END")]]
    except ValueError:
        sys.exit("Gradle printed no class path:\n" + result.stdout)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--java", default=os.environ.get("QUADRATURE_MIXIN_JAVA"), help="a Java 26 launcher")
    parser.add_argument("--mixinkit", help="orthlib-mixinkit jar (default: the sibling OrthLib checkout's build)")
    parser.add_argument("--out", default=str(ROOT / "build" / "mixin-report.txt"))
    args = parser.parse_args()
    if not args.java:
        sys.exit("Name a Java 26 launcher with --java or QUADRATURE_MIXIN_JAVA.")
    kit = Path(args.mixinkit) if args.mixinkit else None
    if kit is None:
        built = sorted(
            jar
            for jar in (ROOT.parent / "OrthLib" / "mixinkit" / "build" / "libs").glob("orthlib-mixinkit-*.jar")
            if "sources" not in jar.name
        )
        if not built:
            sys.exit("No orthlib-mixinkit jar beside this checkout; build OrthLib's mixinkit or pass --mixinkit.")
        kit = built[-1]
    classes = ROOT / "build" / "classes" / "java" / "main"
    resources = ROOT / "build" / "resources" / "main"
    if not (resources / CONFIGURATION).is_file() or not classes.is_dir():
        sys.exit("Nothing built: run ./gradlew build first.")
    classpath = compile_classpath()
    tool = [str(kit)] + [entry for entry in classpath if TOOL_LIBRARIES.match(Path(entry).name)]
    command = [
        args.java,
        "--enable-final-field-mutation=ALL-UNNAMED",
        "-cp",
        os.pathsep.join(tool),
        "dev.orthlib.testkit.mixin.MixinCheck",
        "apply",
        "--out",
        args.out,
        "--config",
        CONFIGURATION,
        "--strict",
        str(classes),
        str(resources),
        *classpath,
    ]
    code = subprocess.run(command).returncode
    report = Path(args.out)
    if report.is_file():
        rows = report.read_text(encoding="utf-8").splitlines()
        counts = {kind: sum(row.startswith(kind + "\t") for row in rows) for kind in ("TARGET", "HOOK", "FAIL", "MISSING")}
        print("Mixin check: " + ", ".join(f"{count} {kind.lower()}" for kind, count in counts.items()))
        for row in rows:
            if row.startswith(("FAIL\t", "MISSING\t")):
                print("  " + row[:400])
    sys.exit(code)


if __name__ == "__main__":
    main()
