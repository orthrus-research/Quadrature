"""Compare captured frames against reference frames.

Two directories of PNG files are matched by file name. For each pair the tool measures how
many pixels changed and by how much, and a comparison profile turns those measurements into
pass or fail.

    python tools/frames.py compare REFERENCE CANDIDATE --profile tools/comparison/exact.json
    python tools/frames.py measure RUN_A RUN_B [RUN_C ...]

``compare`` exits 0 when every frame passes, 1 when any fails, 2 on a usage error.
``measure`` reports the differences between repeated captures of the same views, which is how
a profile's limits are chosen: a tolerance below the noise of two identical runs can never pass.
"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import asdict, dataclass
from pathlib import Path

import numpy as np
from PIL import Image


@dataclass(frozen=True)
class Profile:
    """Limits for one comparison.

    A pixel counts as changed when any channel differs by more than ``channelThreshold``
    (0 to 255). A frame passes when the changed fraction and the mean absolute difference are
    both within their limits.
    """

    channelThreshold: int
    maxChangedFraction: float
    maxMeanAbsolute: float

    @staticmethod
    def load(path: Path) -> "Profile":
        data = json.loads(path.read_text(encoding="utf-8"))
        return Profile(
            int(data["channelThreshold"]),
            float(data["maxChangedFraction"]),
            float(data["maxMeanAbsolute"]),
        )


@dataclass(frozen=True)
class Measurement:
    """How two frames of the same size differ."""

    changedFraction: float
    meanAbsolute: float
    maxAbsolute: int


def load_rgb(path: Path) -> np.ndarray:
    with Image.open(path) as image:
        return np.asarray(image.convert("RGB"), dtype=np.int16)


def measure_pair(reference: np.ndarray, candidate: np.ndarray, channel_threshold: int) -> Measurement:
    if reference.shape != candidate.shape:
        raise ValueError(f"sizes differ: {reference.shape[1]}x{reference.shape[0]} "
                         f"and {candidate.shape[1]}x{candidate.shape[0]}")
    difference = np.abs(reference - candidate)
    per_pixel = difference.max(axis=2)
    return Measurement(
        float((per_pixel > channel_threshold).mean()),
        float(difference.mean()),
        int(difference.max()),
    )


def passes(measurement: Measurement, profile: Profile) -> bool:
    return (measurement.changedFraction <= profile.maxChangedFraction
            and measurement.meanAbsolute <= profile.maxMeanAbsolute)


def frames(directory: Path) -> dict[str, Path]:
    return {path.name: path for path in sorted(directory.glob("*.png"))}


def write_difference(reference: np.ndarray, candidate: np.ndarray, channel_threshold: int, path: Path) -> None:
    """Saves the reference dimmed to gray, with changed pixels in red scaled by their difference."""
    per_pixel = np.abs(reference - candidate).max(axis=2)
    gray = (reference.mean(axis=2) * 0.35).astype(np.uint8)
    out = np.stack([gray, gray, gray], axis=2)
    changed = per_pixel > channel_threshold
    out[changed] = np.stack([
        np.clip(96 + per_pixel[changed] * 2, 0, 255).astype(np.uint8),
        np.zeros(int(changed.sum()), dtype=np.uint8),
        np.zeros(int(changed.sum()), dtype=np.uint8),
    ], axis=1)
    Image.fromarray(out, "RGB").save(path)


def compare(reference_dir: Path, candidate_dir: Path, profile: Profile, out_dir: Path | None) -> dict:
    reference, candidate = frames(reference_dir), frames(candidate_dir)
    results = []
    for name in sorted(set(reference) | set(candidate)):
        if name not in candidate:
            results.append({"frame": name, "passed": False, "reason": "missing from candidate"})
            continue
        if name not in reference:
            results.append({"frame": name, "passed": False, "reason": "not in reference"})
            continue
        a, b = load_rgb(reference[name]), load_rgb(candidate[name])
        try:
            measurement = measure_pair(a, b, profile.channelThreshold)
        except ValueError as error:
            results.append({"frame": name, "passed": False, "reason": str(error)})
            continue
        passed = passes(measurement, profile)
        results.append({"frame": name, "passed": passed, **asdict(measurement)})
        if out_dir is not None and not passed:
            out_dir.mkdir(parents=True, exist_ok=True)
            write_difference(a, b, profile.channelThreshold, out_dir / name)
    report = {
        "reference": str(reference_dir),
        "candidate": str(candidate_dir),
        "profile": asdict(profile),
        "passed": bool(results) and all(result["passed"] for result in results),
        "frames": results,
    }
    if out_dir is not None:
        out_dir.mkdir(parents=True, exist_ok=True)
        (out_dir / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return report


def measure_runs(runs: list[Path], channel_threshold: int) -> dict:
    """Differences of every later run against the first, per frame, and the worst of each measure."""
    first = frames(runs[0])
    rows = []
    for run in runs[1:]:
        other = frames(run)
        for name in sorted(set(first) & set(other)):
            try:
                measurement = measure_pair(load_rgb(first[name]), load_rgb(other[name]), channel_threshold)
            except ValueError as error:
                rows.append({"frame": name, "run": run.name, "error": str(error)})
                continue
            rows.append({"frame": name, "run": run.name, **asdict(measurement)})
    measured = [row for row in rows if "error" not in row]
    worst = {
        "changedFraction": max((row["changedFraction"] for row in measured), default=0.0),
        "meanAbsolute": max((row["meanAbsolute"] for row in measured), default=0.0),
        "maxAbsolute": max((row["maxAbsolute"] for row in measured), default=0),
    }
    return {"baseline": str(runs[0]), "channelThreshold": channel_threshold, "pairs": len(measured),
            "identicalPairs": sum(1 for row in measured if row["maxAbsolute"] == 0),
            "worst": worst, "rows": rows}


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)

    c = commands.add_parser("compare", help="pass or fail a candidate against a reference")
    c.add_argument("reference", type=Path)
    c.add_argument("candidate", type=Path)
    c.add_argument("--profile", type=Path, required=True, help="comparison profile JSON")
    c.add_argument("--out", type=Path, help="directory for report.json and difference images of failed frames")

    m = commands.add_parser("measure", help="report differences between repeated captures")
    m.add_argument("runs", type=Path, nargs="+")
    m.add_argument("--channel-threshold", type=int, default=8)
    m.add_argument("--json", action="store_true", help="print the full report as JSON")

    args = parser.parse_args(argv)
    if args.command == "compare":
        for directory in (args.reference, args.candidate):
            if not directory.is_dir():
                print(f"Not a directory: {directory}", file=sys.stderr)
                return 2
        report = compare(args.reference, args.candidate, Profile.load(args.profile), args.out)
        for frame in report["frames"]:
            if "reason" in frame:
                print(f"FAIL  {frame['frame']}: {frame['reason']}")
            else:
                print(f"{'pass' if frame['passed'] else 'FAIL'}  {frame['frame']}: "
                      f"{frame['changedFraction']:.4%} changed, mean {frame['meanAbsolute']:.3f}, "
                      f"max {frame['maxAbsolute']}")
        if not report["frames"]:
            print("No frames to compare.")
        print("PASSED" if report["passed"] else "FAILED")
        return 0 if report["passed"] else 1

    if len(args.runs) < 2:
        print("measure needs at least two runs", file=sys.stderr)
        return 2
    report = measure_runs(args.runs, args.channel_threshold)
    if args.json:
        print(json.dumps(report, indent=2))
    else:
        worst = report["worst"]
        print(f"{report['pairs']} frame pairs against {report['baseline']}; "
              f"{report['identicalPairs']} identical")
        print(f"worst changed fraction {worst['changedFraction']:.4%}, "
              f"worst mean {worst['meanAbsolute']:.3f}, worst max {worst['maxAbsolute']}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
