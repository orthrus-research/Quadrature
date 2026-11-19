"""Read a client log's stage times, one row per captured view.

A client run with ``-Dquadrature.frameLog=true`` writes a stage report every five seconds: the
median and 95th percentile of each stage's CPU and GPU time over the last 256 frames, then the
terrain summary. A tour logs ``Riven view captured`` once the player has held still at a view for
the settle time, so the last report before each capture is that view, settled. The median of a
tour's reports mixes settled views with the loading between them; this tool keeps them apart.

    python tools/stage_views.py views LOG [--tour TOUR] [--stage NAME]
    python tools/stage_views.py summary LOG [LOG ...] [--tour TOUR --select PATTERN] [--stage NAME] [--json OUT]

``views`` prints a row per captured view. ``summary`` prints a row per log: the median and the
worst over its views, over its settled views (no meshes waiting at the report), and the median
over all of its reports. Exit 0, or 2 on a usage error or a log with no captured view.
"""

from __future__ import annotations

import argparse
import json
import re
import statistics
import sys
from dataclasses import dataclass, field
from pathlib import Path

REPORT = re.compile(r"Stage times, median/95th in ms \(CPU \| GPU\):")
STAGE = re.compile(
    r"^\s+(?P<name>[a-z][a-z ]*?)\s+(?P<cpu>[\d.]+)/\s*(?P<cpu95>[\d.]+)\s*\|\s*"
    r"(?P<gpu>[\d.]+)/\s*(?P<gpu95>[\d.]+)\s*$"
)
CAPTURE = re.compile(r"Riven view captured: riven-view-(?P<index>\d+)-")
DISTANCES = re.compile(r"Inspection view distances: client horizontal=(?P<h>\d+) vertical=(?P<v>\d+)")
TIME = re.compile(r"^\[(?P<time>\d\d:\d\d:\d\d)\]")
# Fields of the terrain summary that describe a view's load and memory. Any may be missing from an
# older log.
TERRAIN = {
    "meshes": (r"(\d+) meshes, \d+ waiting", int),
    "waiting": (r"\d+ meshes, (\d+) waiting", int),
    "heldMiB": (r"([\d.]+) MiB, \d+ M vertices held", float),
    "verticesHeldM": (r"(\d+) M vertices held", int),
    "draws": (r"last frame (\d+) draws", int),
    "kVertices": (r"last frame \d+ draws, (\d+) k vertices", int),
    "cubesSeen": (r"main view (\d+) cubes seen", int),
    "searchMs": (r"([\d.]+) ms a search", float),
    "arenaPages": (r"arena (\d+) pages", int),
    "arenaCapacityMiB": (r"arena \d+ pages, ([\d.]+) MiB capacity", float),
    "arenaInUseMiB": (r"arena \d+ pages, [\d.]+ MiB capacity, ([\d.]+) MiB in use", float),
    "recordPages": (r"records in (\d+) pages", int),
    "recordCapacityMiB": (r"records in \d+ pages, ([\d.]+) MiB capacity", float),
    "recordInUseMiB": (r"records in \d+ pages, [\d.]+ MiB capacity, ([\d.]+) MiB in use", float),
    "halfSideBlocks": (r"near terrain's half side (\d+) blocks", int),
}
TERRAIN_PATTERNS = {key: (re.compile(pattern), kind) for key, (pattern, kind) in TERRAIN.items()}


@dataclass
class Report:
    """One stage report: each stage's (CPU median, CPU 95th, GPU median, GPU 95th), and the terrain."""

    line: int
    time: str
    stages: dict[str, tuple[float, float, float, float]] = field(default_factory=dict)
    terrain: dict[str, float] = field(default_factory=dict)


@dataclass
class View:
    """A captured view and the last report before it."""

    index: int
    label: str
    time: str
    horizontal: int | None
    vertical: int | None
    report: Report | None


def parse_terrain(text: str) -> dict[str, float]:
    found = {}
    for key, (pattern, kind) in TERRAIN_PATTERNS.items():
        match = pattern.search(text)
        if match:
            found[key] = kind(match.group(1))
    return found


def read(path: Path, labels: list[str] | None = None) -> tuple[list[Report], list[View]]:
    """Every stage report in a client log, and every captured view with the report before it."""
    reports: list[Report] = []
    views: list[View] = []
    current: Report | None = None
    distances: tuple[int, int] | None = None
    with path.open(encoding="utf-8", errors="replace") as log:
        for number, line in enumerate(log, 1):
            if current is not None:
                if line.startswith((" ", "\t")):
                    stage = STAGE.match(line)
                    if stage:
                        current.stages[stage["name"]] = (
                            float(stage["cpu"]), float(stage["cpu95"]),
                            float(stage["gpu"]), float(stage["gpu95"]),
                        )
                    elif line.lstrip().startswith("terrain:"):
                        current.terrain = parse_terrain(line)
                    continue
                reports.append(current)
                current = None
            if REPORT.search(line):
                stamp = TIME.match(line)
                current = Report(number, stamp["time"] if stamp else "")
                continue
            measured = DISTANCES.search(line)
            if measured:
                distances = (int(measured["h"]), int(measured["v"]))
                continue
            capture = CAPTURE.search(line)
            if capture:
                index = int(capture["index"])
                stamp = TIME.match(line)
                label = labels[index] if labels and index < len(labels) else f"view-{index:02d}"
                views.append(View(
                    index, label, stamp["time"] if stamp else "",
                    distances[0] if distances else None, distances[1] if distances else None,
                    reports[-1] if reports else None,
                ))
                distances = None
    if current is not None:
        reports.append(current)
    return reports, views


def tour_labels(path: Path) -> list[str]:
    tour = json.loads(path.read_text(encoding="utf-8"))
    views = tour["views"] if isinstance(tour, dict) else tour
    return [view["label"] for view in views]


def stage_of(report: Report | None, stage: str) -> tuple[float, float, float, float] | None:
    return report.stages.get(stage) if report else None


def spread(values: list[float]) -> dict:
    if not values:
        return {"count": 0}
    return {
        "count": len(values),
        "median": statistics.median(values),
        "mean": statistics.fmean(values),
        "most": max(values),
        "least": min(values),
    }


def settled(views: list[View], stage: str) -> set[int]:
    """The views whose report has the stage and no meshes waiting: terrain had finished loading."""
    return {view.index for view in views
            if stage_of(view.report, stage) and view.report.terrain.get("waiting", 0) == 0}


def chosen(views: list[View], select: str | None) -> list[View]:
    """The views whose label matches a pattern; all of them without one."""
    return views if not select else [view for view in views if re.search(select, view.label)]


def summarize(path: Path, stage: str, common: set[int] | None = None, labels: list[str] | None = None,
              select: str | None = None) -> dict:
    """A log's views: GPU and CPU medians over all views, over settled ones and over the views given
    as common (settled in every log compared), the tour's median of reports, and the terrain at the
    last view. With a pattern, only the views whose label matches count."""
    reports, views = read(path, labels)
    views = chosen(views, select)
    measured = [(view, stage_of(view.report, stage)) for view in views]
    measured = [(view, times) for view, times in measured if times]
    calm = settled(views, stage)
    shared = calm if common is None else common
    every = [stage_of(report, stage) for report in reports]
    every = [times for times in every if times]
    worst = max(measured, key=lambda pair: pair[1][2], default=None)
    last = views[-1].report.terrain if views and views[-1].report else {}

    def terrain(key: str, among: set[int]) -> dict:
        return spread([view.report.terrain[key] for view, _ in measured
                       if view.index in among and key in view.report.terrain])

    return {
        "log": str(path),
        "stage": stage,
        "distances": sorted({(view.horizontal, view.vertical) for view in views if view.horizontal}),
        "views": len(views),
        "settledViews": sorted(calm),
        "commonViews": sorted(shared),
        "gpu": spread([times[2] for _, times in measured]),
        "cpu": spread([times[0] for _, times in measured]),
        "settledGpu": spread([times[2] for view, times in measured if view.index in calm]),
        "commonGpu": spread([times[2] for view, times in measured if view.index in shared]),
        "commonGpu95": spread([times[3] for view, times in measured if view.index in shared]),
        "commonCpu": spread([times[0] for view, times in measured if view.index in shared]),
        "commonCubesSeen": terrain("cubesSeen", shared),
        "commonKVertices": terrain("kVertices", shared),
        "worstView": {"index": worst[0].index, "gpu": worst[1][2]} if worst else None,
        "waitingAtCapture": {str(view.index): view.report.terrain.get("waiting", 0)
                             for view, _ in measured if view.report.terrain.get("waiting", 0)},
        "reports": len(every),
        "reportsGpu": spread([times[2] for times in every]),
        "lastTerrain": last,
    }


def format_view(view: View, stage: str) -> str:
    times = stage_of(view.report, stage)
    terrain = view.report.terrain if view.report else {}
    if not times:
        return f"{view.index:3d} {view.label:28s} {view.time}  no report"
    return (f"{view.index:3d} {view.label:28s} {view.time}  {view.report.time}  "
            f"{times[2]:6.3f} {times[3]:6.3f}  {times[0]:6.3f}  "
            f"{terrain.get('waiting', '-')!s:>6} {terrain.get('cubesSeen', '-')!s:>6} "
            f"{terrain.get('kVertices', '-')!s:>6} {terrain.get('draws', '-')!s:>5}")


def format_summary(row: dict) -> str:
    def median(key: str, width: int = 6, digits: int = 3) -> str:
        part = row[key]
        return f"{part['median']:{width}.{digits}f}" if part.get("count") else " " * (width - 1) + "-"

    def most(key: str) -> str:
        part = row[key]
        return f"{part['most']:6.3f}" if part.get("count") else "     -"

    last = row["lastTerrain"]
    memory = (f"{last.get('arenaInUseMiB', 0):6.1f}/{last.get('arenaCapacityMiB', 0):6.1f} "
              f"{last.get('recordInUseMiB', 0):6.1f}/{last.get('recordCapacityMiB', 0):6.1f}")
    distances = ",".join(f"{h}x{v}" for h, v in row["distances"]) or "-"
    return (f"{distances:>7} {row['views']:3d} {len(row['settledViews']):3d} {len(row['commonViews']):3d}  "
            f"{median('commonGpu')} {most('commonGpu')} {median('commonGpu95')} {median('commonCpu')}  "
            f"{median('settledGpu')} {median('gpu')} {median('reportsGpu')}  "
            f"{median('commonCubesSeen', 6, 0)} {median('commonKVertices', 6, 0)}  {memory}  "
            f"{last.get('halfSideBlocks', '-')!s:>4}  {Path(row['log']).name}")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)
    one = commands.add_parser("views", help="a row per captured view of one log")
    one.add_argument("log", type=Path)
    one.add_argument("--tour", type=Path, help="the tour file, to name views by label")
    one.add_argument("--stage", default="opaque terrain")
    many = commands.add_parser("summary", help="a row per log")
    many.add_argument("logs", type=Path, nargs="+")
    many.add_argument("--stage", default="opaque terrain")
    many.add_argument("--tour", type=Path, help="the tour file, to name views by label")
    many.add_argument("--select", help="count only the views whose label matches this pattern")
    many.add_argument("--json", type=Path, help="also write the rows as JSON")
    arguments = parser.parse_args(argv)

    if arguments.command == "views":
        labels = tour_labels(arguments.tour) if arguments.tour else None
        _, views = read(arguments.log, labels)
        if not views:
            print(f"{arguments.log}: no captured view", file=sys.stderr)
            return 2
        print(f"{arguments.stage}, GPU median and 95th, CPU median (ms), from the last report before each capture")
        print("  # view                         captured  report    GPU    95th     CPU  waiting cubes  kvert draws")
        for view in views:
            print(format_view(view, arguments.stage))
        return 0

    labels = tour_labels(arguments.tour) if arguments.tour else None
    read_views = {}
    for log in arguments.logs:
        views = chosen(read(log, labels)[1], arguments.select)
        if not views:
            print(f"{log}: no captured view", file=sys.stderr)
            return 2
        read_views[log] = views
    common = set.intersection(*(settled(views, arguments.stage) for views in read_views.values()))
    rows = [summarize(log, arguments.stage, common, labels, arguments.select) for log in arguments.logs]
    print(f"{arguments.stage}, ms. Over the views settled in every log (common): the median GPU, the worst, "
          "the median 95th and the median CPU; the median GPU over this log's settled views, over all its views "
          "and over all its reports; cubes seen and k vertices at the median common view; arena and records "
          "MiB in use/capacity and near terrain's half side in blocks at the last view.")
    print(" radius  all set com     GPU  worst   95th    CPU  settled  views reports  cubes  kvert   arena         records   half")
    for row in rows:
        print(format_summary(row))
    if arguments.json:
        arguments.json.write_text(json.dumps(rows, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
