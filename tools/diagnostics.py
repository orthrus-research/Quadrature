"""Cross-mod diagnostics: run a fixed plan on a fresh Riven world, then charge each process's time,
allocation, compilation and waits to the mod whose code it was in.

    python tools/diagnostics.py check PLAN               # preflight, no game: exit 1 on any problem
    python tools/diagnostics.py run PLAN [--dry-run]     # only as the command of a queued GPU turn
    python tools/diagnostics.py attribute RUN_DIR [--cores N]
    python tools/diagnostics.py report RUN_DIR [RUN_DIR ...] [--markdown OUT.md] [--json OUT.json]

``check`` reads a plan (below) and refuses what would waste a turn: a missing artifact, a Quadrature
switch without the switches it needs, DLSS without its shim, runtime or module, distant terrain
without a Riven that previews, a flight without a motion helper that flies routes or outside the
helper's bounds or its run's limit, a tour with two consecutive views at one position, an instance
name already taken, a removal that leaves a mod without one it requires, too little disk, no
``jfr`` tool.

``run`` checks, prepares the plan's instance with Riven's ``tools/runtime.py``, removes the plan's
``remove`` mods from both sides (an ablation: the instance's mod hashes are rewritten to match),
indexes the instance's mods (``mod-index.json``) and plays each run in order: a tour, or a flight,
which the motion helper (0.2.0 or later) flies along the plan's route at a player's pace, the
player really moving, so the world loads ahead of it and unloads behind it as for a player; the
helper waits wherever the next cube has not arrived, and closes the client when the route ends.
Every run records both games under JFR with ``DebugNonSafepoints``, and clocks the plan's
``timed`` methods by JFR's method timing, since execution samples see a thread only while it runs
Java code and so misstate threads that mix tight loops with VM-heavy work (the server thread
among them); a tour's client recording is dumped with ``jcmd``
once every view is captured, before the launcher kills the client, and 1 MiB recorder chunks are
the fallback. It writes GC logs, samples the GPU with ``nvidia-smi``, and moves logs, recordings
and the helper's frame records into ``RUN_DIR/<label>/``, with ``run.json`` beside them.

``attribute`` reads each recording with the JDK's ``jfr`` tool (``JAVA_HOME``) into windows:
``startup`` (to the first teleport), ``run`` (from it), each tour view (from the capture before to
its own) and each flight phase (stationary, move, dwell, hold, from the helper's frames). A sample's
owner is the mod whose frame is nearest the top of the stack; the JDK and libraries are
transparent, so their time goes to whoever called them; a game method a mod overwrote, and code
Mixin merged into a game class, are charged to that mod (by the index, or by the mod name the
handler carries). Time the engine (the game, Forge, Cleanroom, LWJGL, Netty, Mixin) spent below
a mod's frame is kept separately with the engine method and the mod method that called it: what a
mod leaves the engine to do. Each thread's CPU, from ``jdk.ThreadCPULoad``, is split by its
samples, so shares become CPU seconds without trusting sample counts across threads. Compilations
(``jdk.Compilation``, those of 100 ms or more under the profile settings) and deoptimizations are
charged to the owner of their method. Cubature's server tick phases, Quadrature's stage times,
the GPU sampler and the flight's frame and GPU times are read beside them.

``report`` prints, per run: the costliest owner of each critical thread group, each group's CPU by
owner, the engine calls each mod's samples went to, the engine's and Cleanroom's own costs,
allocation, compilation, GC, the server's tick phases, the stage and frame times and the GPU's
clocks; and, for labels shared between run directories, the differences.

Plan:

    {"riven": RIVEN_ROOT, "artifact": FROZEN_RIVEN_JAR, "cubicArtifact": CUBATURE_JAR,
     "clientMods": [JAR, ...], "instance": NAME, "seed": 8675309, "out": RUN_DIR,
     "options": "-D... common to every run", "jvm": "zgc-fixed", "window": "1920x1080",
     "remove": ["GTCEu Reloaded"], "owners": {"PACKAGE.PREFIX.": "Owner name"},
     "runs": [
       {"label": "1-caves", "tour": TOUR_JSON, "options": "-D...", "limitSeconds": 1500},
       {"label": "2-flight", "flight": {"start": [x, y, z, yaw, pitch],
        "route": [[x, y, z], [x, y, z, DWELL_SECONDS], ...], "speed": 16, "acceleration": 16,
        "cornerRadius": 24, "turnRate": 120, "laps": 1, "settleSeconds": 60, "stationarySeconds": 5,
        "holdSeconds": 10}, "options": "-D..."}]}

Exit 0, 1 on a failed check, run or analysis, 2 on a usage error or a run outside a queued turn.
"""

from __future__ import annotations

import argparse
import datetime
import functools
import hashlib
import json
import math
import os
import re
import shutil
import subprocess
import sys
import time
import zipfile
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

# Owners by class-name prefix, most specific first. None marks code that is transparent: the JDK and
# general libraries, whose time is charged to the owner that called them.
OWNERS: list[tuple[str, str | None]] = [
    ("cc.orthrus.quadrature.", "Quadrature"),
    ("cc.orthrus.riven.", "Riven"),
    ("io.github.opencubicchunks.", "Cubature"),
    ("cubicchunks.regionlib.", "Cubature"),
    ("dev.orthlib.", "OrthLib"),
    ("gregtech.", "GTCEu Reloaded"),
    ("codechicken.", "CodeChickenLib"),
    ("com.cleanroommc.modularui.", "ModularUI"),
    ("com.ezylang.evalex.", "ModularUI"),
    ("com.cleanroommc.fugue.", "Fugue"),
    ("com.cleanroommc.scalar.", "Scalar Legacy"),
    ("scala.", "Scalar Legacy"),
    ("com.seibel.distanthorizons.", "Distant Horizons"),
    ("DistantHorizons.", "Distant Horizons"),
    ("net.minecraftforge.", "Forge (Cleanroom)"),
    ("cpw.mods.", "Forge (Cleanroom)"),
    ("com.cleanroommc.", "Cleanroom"),
    ("top.outlands.", "Cleanroom"),
    ("zone.rong.", "Cleanroom"),
    ("net.minecraft.", "Minecraft"),
    ("com.mojang.", "Minecraft"),
    ("paulscode.", "Minecraft"),
    ("org.spongepowered.", "Mixin"),
    ("com.llamalad7.mixinextras.", "Mixin"),
    ("org.lwjgl.", "LWJGL"),
    ("io.netty.", "Netty"),
    ("java.", None),
    ("javax.", None),
    ("jdk.", None),
    ("sun.", None),
    ("com.sun.", None),
    ("it.unimi.", None),
    ("com.google.", None),
    ("org.apache.", None),
    ("org.joml.", None),
    ("org.objectweb.", None),
    ("kotlin.", None),
]

# Owners that are the engine rather than a mod: time they spend below a mod's frame is that mod's
# demand on the engine.
ENGINE = {"Minecraft", "Forge (Cleanroom)", "Cleanroom", "LWJGL", "Netty", "Mixin"}
NOT_A_MOD = ENGINE | {"JDK", "Other", "Mixin handler (unnamed mod)", "Idle (frame limiter)", "Present (driver)",
                      "GPU wait (driver)"}

HANDLER_PREFIX = re.compile(
    r"^(handler|redirect|modify|localvar|constant|wrapOperation|wrapWithCondition|modifyExpressionValue|"
    r"modifyReturnValue|modifyArg|modifyArgs|modifyVariable|modifyConstant|wrapMethod|inject)\$[0-9a-z]+\$")
HANDLER_TOKENS = [
    ("cubicchunks", "Cubature"), ("cubature", "Cubature"), ("quadrature", "Quadrature"), ("riven", "Riven"),
    ("orthlib", "OrthLib"), ("gregtech", "GTCEu Reloaded"), ("gtceu", "GTCEu Reloaded"),
    ("modularui", "ModularUI"), ("fugue", "Fugue"), ("codechicken", "CodeChickenLib"),
    ("distanthorizons", "Distant Horizons"), ("cleanroom", "Cleanroom"),
]

# Mod frames that only wrap the engine's own work to time or scope it (Cubature's tick clock, its
# autosave scope, the lab's wrappers): transparent, or every tick would count as work for that mod.
WRAPPERS = re.compile(r"cubicchunks\$measureTick|cubicchunks\$periodicSave|AutosaveScope\.run|TickDiagnostics\.|"
                      r"\.Lab[A-Za-z]*\.(wrap|around|measure)|"
                      # Quadrature's out-of-memory notice wraps the whole client loop.
                      r"quadrature\$noteOutOfMemory|"
                      # CodeChickenLib's dispatcher replaces the game's and passes every block render
                      # through; its own renderers are other classes and stay its own.
                      r"codechicken\.lib\.render\.block\.CCBlockRendererDispatcher\.")

# Frames near the top of a stack that mean the thread is waiting, not working: charged to these
# owners whatever called them.
WAITS = [
    (re.compile(r"Display\.sync\(|Sync\.sync\("), "Idle (frame limiter)"),
    (re.compile(r"glfwSwapBuffers|Display\.update\(|SwapBuffers|nglfwSwapBuffers"), "Present (driver)"),
    (re.compile(r"glFinish\(|glClientWaitSync\(|glGetQueryObject|glGetBufferSubData\(|glReadPixels\("), "GPU wait (driver)"),
]
WAIT_DEPTH = 8

FRAME = re.compile(r"^(?P<cls>[\w$.]+)\.(?P<method>[\w$<>]+)\(")
THREAD = re.compile(r'"(?P<name>[^"]*)"')
TIME = re.compile(r"(\d+):(\d+):(\d+(?:\.\d+)?)")
QUANTITY = re.compile(r"(?P<value>[\d.]+)\s*(?P<unit>[a-zA-Z%]*)")
UNIT_BYTES = {"bytes": 1, "byte": 1, "kB": 1e3, "MB": 1e6, "GB": 1e9}
UNIT_MS = {"ns": 1e-6, "us": 1e-3, "ms": 1.0, "s": 1e3}


class Attribution:
    """Owns frames: by package, by the Mixin index where a mod overwrote a game method or merged a
    handler into it, and by the mod name a merged handler carries. Answers are cached by frame."""

    def __init__(self, index=None, owners: dict[str, str] | None = None, timed: list[str] | None = None):
        self.index = index
        # Methods the plan clocks (Class::method), as samples name them (Class.method, simple class).
        self.timed = {f"{t.split('::')[0].rsplit('.', 1)[-1]}.{t.split('::')[1]}" for t in (timed or [])}
        # Owners a plan adds by package prefix (local tools that are not named here), checked first.
        self.extra = sorted((owners or {}).items(), key=lambda kv: -len(kv[0]))
        self.cache: dict[str, str | None] = {}

    def owner(self, frame: str) -> str | None:
        frame = frame.strip()
        key = frame.split(" line:", 1)[0]
        if key in self.cache:
            return self.cache[key]
        owner = self._owner(key)
        self.cache[key] = owner
        return owner

    def _owner(self, frame: str) -> str | None:
        match = FRAME.match(frame)
        if not match:
            return None
        cls, method = match.group("cls"), match.group("method")
        if WRAPPERS.search(f"{cls}.{method}"):
            return None
        prefix = HANDLER_PREFIX.match(method)
        if prefix:
            handler = method[prefix.end():]
            lowered = handler.lower()
            for token, mod in HANDLER_TOKENS:
                if token in lowered:
                    return mod
            if self.index is not None:
                mod = self.index.handlers.get((cls, handler))
                if mod:
                    return mod
            return "Mixin handler (unnamed mod)"
        if self.index is not None and (cls, method) in self.index.overwrites:
            return self.index.overwrites[(cls, method)]
        for start, owner in self.extra:
            if cls.startswith(start):
                return owner
        for start, owner in OWNERS:
            if cls.startswith(start):
                return owner
        return "Other"


DEFAULT = Attribution()


def owner_of(frame: str) -> str | None:
    """The owner of one stack frame line by package and handler name alone (no Mixin index)."""
    return DEFAULT.owner(frame)


def thread_group(name: str) -> str:
    """A thread's group: its name with numbers replaced, so a pool's threads count together."""
    return re.sub(r"\d+", "#", name).strip()


def method_of(frame: str) -> str:
    match = FRAME.match(frame.strip())
    if not match:
        return frame.strip()
    return f"{match.group('cls').rsplit('.', 1)[-1]}.{match.group('method')}"


@dataclass
class Charge:
    """Where one stack's time goes: its owner and the owner's frame nearest the top (its method);
    for an engine owner, the mod it served and that mod's frame; every mod on the stack."""
    owner: str
    behalf: str | None
    mods: frozenset[str]
    method: str = ""
    behalf_method: str = ""
    owner_class: str = ""


def class_of(frame: str) -> str:
    match = FRAME.match(frame.strip())
    return match.group("cls") if match else ""


def charge(stack: list[str], attribution: Attribution = DEFAULT) -> Charge:
    """Charge a stack (top frame first): the nearest owned frame owns it; if that is the engine, the
    nearest mod below it is who the engine served."""
    for frame in stack[:WAIT_DEPTH]:
        for pattern, waiting in WAITS:
            if pattern.search(frame):
                return Charge(waiting, None, frozenset(), method_of(frame), "", class_of(frame))
    owned = [(i, o) for i, o in ((i, attribution.owner(f)) for i, f in enumerate(stack)) if o is not None]
    if not owned:
        return Charge("JDK", None, frozenset(), method_of(stack[0]) if stack else "")
    first, owner = owned[0]
    behalf, behalf_method = None, ""
    if owner in ENGINE:
        for i, o in owned:
            if o not in NOT_A_MOD:
                behalf, behalf_method = o, method_of(stack[i])
                break
    mods = frozenset(o for _, o in owned if o not in NOT_A_MOD)
    return Charge(owner, behalf, mods, method_of(stack[first]), behalf_method, class_of(stack[first]))


# The engine's systems by class, for the time the game and Forge spend: what a mod could take over.
@functools.lru_cache(maxsize=None)
def engine_system(cls: str) -> str:
    from mod_index import system_of

    return system_of(cls.replace(".", "/"))


@dataclass
class Window:
    """Seconds of the day (local, as recordings and logs stamp them) an analysis keeps."""
    start: float = 0.0
    end: float = 1e9
    name: str = "all"

    def holds(self, t: float | None) -> bool:
        return t is not None and self.start <= t < self.end


def counters():
    return defaultdict(Counter)


@dataclass
class Tables:
    """One recording's tables over one window, keyed by thread group then owner."""
    samples: dict = field(default_factory=counters)
    native: dict = field(default_factory=counters)
    behalf: dict = field(default_factory=counters)
    hot: dict = field(default_factory=counters)
    leaned: dict = field(default_factory=counters)
    systems: dict = field(default_factory=counters)
    onstack: dict = field(default_factory=counters)
    allocation: dict = field(default_factory=counters)
    allocation_sites: dict = field(default_factory=counters)
    monitor_ms: dict = field(default_factory=counters)
    park_ms: dict = field(default_factory=counters)
    cpu: dict = field(default_factory=lambda: defaultdict(list))
    compile_ms: Counter = field(default_factory=Counter)
    compiles: Counter = field(default_factory=Counter)
    compile_methods: Counter = field(default_factory=Counter)
    deopts: Counter = field(default_factory=Counter)
    deopt_methods: Counter = field(default_factory=Counter)
    deopt_reasons: Counter = field(default_factory=Counter)
    compile_failures: Counter = field(default_factory=Counter)
    # JFR method timing: cumulative (time, method, invocations, average ms) points, kept in every
    # window's table since a window's share is a difference of them; and samples with a timed method
    # on their stack, by thread group, for the sampled estimate set against the clock.
    timing: list = field(default_factory=list)
    timed_onstack: dict = field(default_factory=counters)
    gc_pause_ms: list = field(default_factory=list)
    gc_ms: list = field(default_factory=list)
    machine: list = field(default_factory=list)
    first: float | None = None
    last: float | None = None


def seconds_of_day(text: str) -> float | None:
    match = TIME.search(text)
    if not match:
        return None
    return int(match.group(1)) * 3600 + int(match.group(2)) * 60 + float(match.group(3))


def quantity(text: str, units: dict) -> float:
    match = QUANTITY.search(text or "")
    if not match:
        return 0.0
    return float(match.group("value")) * units.get(match.group("unit"), 1.0)


EVENTS = ("jdk.ExecutionSample,jdk.NativeMethodSample,jdk.ObjectAllocationSample,jdk.JavaMonitorEnter,"
          "jdk.ThreadPark,jdk.GarbageCollection,jdk.ThreadCPULoad,jdk.CPULoad,jdk.Compilation,"
          "jdk.Deoptimization,jdk.CompilationFailure,jdk.MethodTiming")


def read_events(lines, windows: list[Window], attribution: Attribution = DEFAULT) -> dict[str, Tables]:
    """Fill one table per window from ``jfr print`` text: an event per block, its fields beneath."""
    tables = {w.name: Tables() for w in windows}
    kind = None
    fields: dict[str, str] = {}
    stack: list[str] = []
    in_stack = False
    for raw in lines:
        line = raw.rstrip("\n")
        stripped = line.strip()
        if not line.startswith(" ") and stripped.endswith("{"):
            kind, fields, stack, in_stack = stripped[:-1].strip(), {}, [], False
        elif stripped.startswith("stackTrace = ["):
            in_stack = True
        elif in_stack:
            if stripped == "]":
                in_stack = False
            elif stripped != "...":
                stack.append(stripped)
        elif line.startswith("}") and kind:
            t = seconds_of_day(fields.get("startTime", ""))
            chosen = [tables[w.name] for w in windows if w.holds(t)]
            for table in tables.values():
                if t is not None:
                    table.first = t if table.first is None else min(table.first, t)
                    table.last = t if table.last is None else max(table.last, t)
            if kind == "jdk.MethodTiming":
                # Cumulative since the recording began: every window keeps every point.
                point = (t, method_of(fields.get("method", "")), int(quantity(fields.get("invocations", "0"), {})),
                         quantity(fields.get("average", "0"), UNIT_MS))
                for table in tables.values():
                    table.timing.append(point)
            elif chosen:
                add_event(chosen, kind, fields, stack, t, attribution)
            kind = None
        elif "=" in stripped and kind:
            key, _, value = stripped.partition("=")
            fields[key.strip()] = value.strip()
    return tables


def add_event(tables: list[Tables], kind: str, fields: dict, stack: list[str], t: float | None,
              attribution: Attribution) -> None:
    thread_field = fields.get("sampledThread") or fields.get("eventThread") or ""
    named = THREAD.search(thread_field)
    thread = named.group("name") if named else "?"
    group = thread_group(thread)
    if kind in ("jdk.ExecutionSample", "jdk.NativeMethodSample"):
        c = charge(stack, attribution)
        timed = {m for m in (method_of(f) for f in stack) if m in attribution.timed} if attribution.timed else ()
        for table in tables:
            (table.samples if kind == "jdk.ExecutionSample" else table.native)[group][c.owner] += 1
            table.hot[group][f"{c.owner}: {c.method}"] += 1
            if kind == "jdk.ExecutionSample":
                for m in timed:
                    table.timed_onstack[group][m] += 1
            if c.owner in ("Minecraft", "Forge (Cleanroom)"):
                table.systems[group][engine_system(c.owner_class)] += 1
            if c.behalf:
                table.behalf[group][f"{c.owner} for {c.behalf}"] += 1
                table.leaned[group][f"{c.behalf}: {c.behalf_method} -> {c.owner}: {c.method}"] += 1
            for mod in c.mods:
                table.onstack[group][mod] += 1
            table.onstack[group]["(samples)"] += 1
    elif kind == "jdk.ObjectAllocationSample":
        c = charge(stack, attribution)
        weight = quantity(fields.get("weight", "0"), UNIT_BYTES)
        for table in tables:
            table.allocation[group][c.owner] += weight
            table.allocation_sites[group][f"{c.owner}: {c.method} ({fields.get('objectClass', '?').split(' ')[0]})"] += weight
    elif kind == "jdk.JavaMonitorEnter":
        owner = charge(stack, attribution).owner
        for table in tables:
            table.monitor_ms[group][owner] += quantity(fields.get("duration", "0"), UNIT_MS)
    elif kind == "jdk.ThreadPark":
        owner = charge(stack, attribution).owner
        for table in tables:
            table.park_ms[group][owner] += quantity(fields.get("duration", "0"), UNIT_MS)
    elif kind == "jdk.GarbageCollection":
        for table in tables:
            table.gc_pause_ms.append(quantity(fields.get("sumOfPauses", "0"), UNIT_MS))
            table.gc_ms.append(quantity(fields.get("duration", "0"), UNIT_MS))
    elif kind == "jdk.ThreadCPULoad":
        load = quantity(fields.get("user", "0"), {"%": 1.0}) + quantity(fields.get("system", "0"), {"%": 1.0})
        for table in tables:
            table.cpu[(group, thread)].append((t, load))
    elif kind == "jdk.CPULoad":
        for table in tables:
            table.machine.append((t, quantity(fields.get("machineTotal", "0"), {"%": 1.0}),
                                  quantity(fields.get("jvmUser", "0"), {"%": 1.0})
                                  + quantity(fields.get("jvmSystem", "0"), {"%": 1.0})))
    elif kind == "jdk.Compilation":
        method = fields.get("method", "")
        owner = attribution.owner(method) or "JDK"
        ms = quantity(fields.get("duration", "0"), UNIT_MS)
        for table in tables:
            table.compile_ms[owner] += ms
            table.compiles[owner] += 1
            table.compile_methods[method_of(method)] += ms
    elif kind == "jdk.Deoptimization":
        method = fields.get("method", "")
        owner = attribution.owner(method) or "JDK"
        for table in tables:
            table.deopts[owner] += 1
            table.deopt_methods[f"{owner}: {method_of(method)}"] += 1
            table.deopt_reasons[fields.get("reason", "?").strip('"')] += 1
    elif kind == "jdk.CompilationFailure":
        for table in tables:
            table.compile_failures[fields.get("failureMessage", "?").strip('"')] += 1


def cpu_seconds(points: list[tuple[float, float]], cores: int, period: float) -> float:
    """CPU seconds from ThreadCPULoad points (percent of the machine). A point's load is the thread's
    since its previous point, so it covers the interval before it, capped at 1.5 recorder periods:
    a thread that ended and a new one of the same name are not bridged."""
    points = sorted(p for p in points if p[0] is not None)
    total = 0.0
    for (t0, _), (t1, load) in zip(points, points[1:]):
        total += load / 100.0 * cores * min(t1 - t0, 1.5 * period)
    return total


def load_period(series: list[list[tuple[float, float]]]) -> float:
    """The recorder's ThreadCPULoad period: the median gap between a thread's points."""
    gaps = []
    for points in series:
        ordered = sorted(p for p in points if p[0] is not None)
        gaps += [b[0] - a[0] for a, b in zip(ordered, ordered[1:]) if b[0] > a[0]]
    gaps.sort()
    return gaps[len(gaps) // 2] if gaps else 10.0


def top(counter: Counter, n: int, scale: float = 1.0, digits: int = 1) -> dict:
    return {k: round(v * scale, digits) for k, v in counter.most_common(n)}


def clocked(tables: Tables, window: Window, group_cpu: Counter) -> dict:
    """Each timed method over the window, by JFR's method timing (cumulative points, so the window's
    share is the difference across it), beside the time its samples imply: the samples with it on
    the stack at their thread group's CPU a sample. Their ratio is how far sampling misstates it."""
    result = {}
    by_method: dict[str, list] = defaultdict(list)
    for t, method, invocations, average in tables.timing:
        if t is not None:
            by_method[method].append((t, invocations, invocations * average))
    per_sample = {g: group_cpu.get(g, 0.0) / sum(c.values()) for g, c in tables.samples.items() if sum(c.values())}
    for method, points in sorted(by_method.items()):
        points.sort()
        before = [p for p in points if p[0] <= window.start]
        inside = [p for p in points if p[0] <= window.end]
        start = before[-1] if before else (0, 0, 0.0)
        end = inside[-1] if inside else start
        ms = end[2] - start[2]
        sampled = 1000 * sum(c.get(method, 0) * per_sample.get(g, 0.0) for g, c in tables.timed_onstack.items())
        groups = Counter({g: c.get(method, 0) for g, c in tables.timed_onstack.items() if c.get(method, 0)})
        result[method] = {"calls": end[1] - start[1], "clockMs": round(ms, 1), "sampledMs": round(sampled, 1),
                          "sampledOverClock": round(sampled / ms, 2) if ms > 0 else None,
                          "group": groups.most_common(1)[0][0] if groups else None}
    return result


TRUSTED = (0.8, 1.25)  # samples over clock within which a thread's sample shares stand as CPU shares


def trust(clocks: dict) -> dict[str, dict]:
    """Per thread group with a clocked method: the range of samples over clock and a verdict."""
    by_group: dict[str, list[float]] = defaultdict(list)
    for v in clocks.values():
        if v.get("group") and v.get("sampledOverClock") and v["clockMs"] > 100:
            by_group[v["group"]].append(v["sampledOverClock"])
    verdicts = {}
    for group, ratios in by_group.items():
        low, high = min(ratios), max(ratios)
        worst = max(high, 1 / low)
        verdicts[group] = {"low": low, "high": high, "methods": len(ratios),
                           "verdict": "samples agree with the clocks" if TRUSTED[0] <= low and high <= TRUSTED[1]
                           else f"samples misstate by up to {worst:.1f}x: use the clocks and phase times"}
    return verdicts


def summarize(tables: Tables, cores: int, n: int = 12, window: Window | None = None) -> dict:
    """A window's tables as JSON: per thread group, CPU seconds split by sample share."""
    group_cpu: Counter = Counter()
    period = load_period(list(tables.cpu.values()))
    for (group, _thread), points in tables.cpu.items():
        group_cpu[group] += cpu_seconds(points, cores, period)
    groups = {}
    for group in sorted(set(tables.samples) | set(tables.native) | set(group_cpu)):
        samples = tables.samples.get(group, Counter())
        native = tables.native.get(group, Counter())
        count = sum(samples.values())
        cpu = group_cpu.get(group, 0.0)
        groups[group] = {
            "cpuSeconds": round(cpu, 3),
            "samples": count,
            "nativeSamples": sum(native.values()),
            "owners": {o: {"share": round(k / count, 4), "cpuSeconds": round(cpu * k / count, 3)}
                       for o, k in samples.most_common(n)} if count else {},
            "native": dict(native.most_common(n)),
            "engineFor": dict(tables.behalf.get(group, Counter()).most_common(n)),
            "hotMethods": dict(tables.hot.get(group, Counter()).most_common(3 * n)),
            "engineCalls": dict(tables.leaned.get(group, Counter()).most_common(2 * n)),
            # The game's and Forge's own samples by engine system, in CPU seconds.
            "engineSystems": {s: round(cpu * k / count, 3) for s, k in tables.systems.get(group, Counter()).most_common(n)}
            if count else {},
            "onStack": {m: round(k / max(tables.onstack[group]["(samples)"], 1), 4)
                        for m, k in tables.onstack.get(group, Counter()).most_common(n + 1) if m != "(samples)"},
            "allocationMB": top(tables.allocation.get(group, Counter()), n, 1e-6),
            "allocationSitesMB": top(tables.allocation_sites.get(group, Counter()), n, 1e-6),
            "monitorMs": top(tables.monitor_ms.get(group, Counter()), n),
            "parkMs": top(tables.park_ms.get(group, Counter()), n),
        }
    span = (tables.last - tables.first) if tables.first is not None and tables.last is not None else 0.0
    return {
        "window": [tables.first, tables.last],
        "seconds": round(span, 1),
        "gc": {"collections": len(tables.gc_ms), "pauseMsTotal": round(sum(tables.gc_pause_ms), 2),
               "pauseMsMax": round(max(tables.gc_pause_ms, default=0.0), 3),
               "concurrentMsTotal": round(sum(tables.gc_ms), 1)},
        "machineCpuMean": round(sum(m for _, m, _ in tables.machine) / max(len(tables.machine), 1), 2),
        "jvmCpuMean": round(sum(j for _, _, j in tables.machine) / max(len(tables.machine), 1), 2),
        "compilation": {"msByOwner": top(tables.compile_ms, n), "countByOwner": dict(tables.compiles.most_common(n)),
                        "slowestMethodsMs": top(tables.compile_methods, n),
                        "deoptimizationsByOwner": dict(tables.deopts.most_common(n)),
                        "deoptimizedMethods": dict(tables.deopt_methods.most_common(n)),
                        "deoptimizationReasons": dict(tables.deopt_reasons.most_common(8)),
                        "failures": dict(tables.compile_failures.most_common(5))},
        "clocked": clocked(tables, window or Window(), group_cpu),
        "threads": groups,
    }


def window_summary(tables: Tables, cores: int, window: Window | None = None) -> dict:
    """A narrow window's summary: per thread group, CPU by owner and the hottest methods only."""
    full = summarize(tables, cores, 8, window)
    return {"seconds": full["seconds"], "gc": full["gc"], "clocked": full["clocked"],
            "threads": {g: {"cpuSeconds": t["cpuSeconds"], "samples": t["samples"], "owners": t["owners"],
                            "hotMethods": dict(list(t["hotMethods"].items())[:8])}
                        for g, t in full["threads"].items() if t["samples"] or t["cpuSeconds"] > 0.5}}


def jfr_tool() -> Path:
    home = os.environ.get("JAVA_HOME")
    if not home:
        raise SystemExit("JAVA_HOME is not set (the JDK whose bin holds jfr)")
    tool = Path(home) / "bin" / ("jfr.exe" if os.name == "nt" else "jfr")
    if not tool.exists():
        raise SystemExit(f"no jfr tool at {tool}")
    return tool


def attribute_recording(recording: Path, windows: list[Window], cores: int, attribution: Attribution) -> dict:
    process = subprocess.Popen([str(jfr_tool()), "print", "--events", EVENTS, "--stack-depth", "96", str(recording)],
                               stdout=subprocess.PIPE, text=True, encoding="utf-8", errors="replace")
    tables = read_events(process.stdout, windows, attribution)
    if process.wait() != 0:
        raise RuntimeError(f"jfr print failed on {recording}")
    result = {}
    for w in windows:
        result[w.name] = (summarize(tables[w.name], cores, window=w) if w.name in ("startup", "run")
                          else window_summary(tables[w.name], cores, w))
    return result


# ---------------------------------------------------------------------------------------------------
# Logs and frame records

LOG_TIME = re.compile(r"^\[(\d+):(\d+):(\d+)\]")
TICK_COSTS = re.compile(r"Cubature tick costs: ticks=(\d+) (.*)$")
PHASE = re.compile(r"(\w+)=([\d.]+)/([\d.]+)ms")
STAGE_REPORT = "Stage times, median/95th in ms (CPU | GPU):"
STAGE_LINE = re.compile(r"^\s+(?P<name>[a-z][a-z ]*?)\s+(?P<cpu>[\d.]+)/\s*[\d.]+\s*\|\s*(?P<gpu>[\d.]+)/\s*[\d.]+\s*$")


def log_lines(path: Path):
    """(seconds of day, line) for each stamped line of a game log."""
    if not path.exists():
        return
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        match = LOG_TIME.match(line)
        if match:
            yield int(match.group(1)) * 3600 + int(match.group(2)) * 60 + int(match.group(3)), line
        else:
            yield None, line


def first_teleport(server_log: Path) -> float | None:
    return next((t for t, line in log_lines(server_log) if t is not None and "Teleported " in line), None)


def view_captures(client_log: Path) -> list[float]:
    return [t for t, line in log_lines(client_log) if t is not None and "Riven view captured" in line]


def local_seconds(utc: str) -> float | None:
    """Seconds of the local day for an ISO UTC stamp, as the recordings and logs count them."""
    try:
        moment = datetime.datetime.fromisoformat(utc.replace("Z", "+00:00")).astimezone()
    except ValueError:
        return None
    return moment.hour * 3600 + moment.minute * 60 + moment.second + moment.microsecond / 1e6


def flight_records(label_dir: Path) -> tuple[list[dict], list[dict]]:
    frames, gpu = [], []
    for path in label_dir.glob("motion/*/render-frames.ndjson"):
        frames += [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    for path in label_dir.glob("motion/*/gpu-frames.ndjson"):
        gpu += [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    return frames, gpu


def windows_for(label_dir: Path) -> list[Window]:
    """startup, run, then each tour view or each flight phase."""
    teleport = first_teleport(label_dir / "server-latest.log")
    start = teleport if teleport is not None else 0.0
    windows = [Window(0.0, start, "startup"), Window(start, 1e9, "run")]
    previous = start
    for n, captured in enumerate(view_captures(label_dir / "client-latest.log")):
        windows.append(Window(previous, captured, f"view-{n:02d}"))
        previous = captured
    frames, _ = flight_records(label_dir)
    spans: dict[str, list[float]] = {}
    for frame in frames:
        t = local_seconds(frame.get("utc", ""))
        if t is not None:
            span = spans.setdefault(frame.get("phase", "?"), [t, t])
            span[0], span[1] = min(span[0], t), max(span[1], t)
    for phase, (a, b) in spans.items():
        windows.append(Window(a, b, f"flight-{phase}"))
    return windows


def percentile(values: list[float], p: float) -> float | None:
    if not values:
        return None
    values = sorted(values)
    return round(values[min(len(values) - 1, int(p * len(values)))], 3)


def flight_times(label_dir: Path) -> dict:
    """By the helper's phase: frames, those held for the world, the frame interval and the CPU render
    time over its eligible frames; its GPU frame times (render-tick bracket) over eligible samples;
    and the receipt's outcome."""
    frames, gpu = flight_records(label_dir)
    if not frames:
        return {}
    by_phase: dict[str, list[dict]] = defaultdict(list)
    for frame in frames:
        by_phase[frame.get("phase", "?")].append(frame)
    result: dict = {"phases": {}}
    for phase, rows in by_phase.items():
        eligible = [r for r in rows if r.get("throughputEligible")]
        intervals = [r["intervalMilliseconds"] for r in eligible if r.get("intervalMilliseconds") is not None]
        cpu = [r["cpuRenderHookScopeMilliseconds"] for r in eligible if r.get("cpuRenderHookScopeMilliseconds") is not None]
        result["phases"][phase] = {
            "frames": len(rows), "eligible": len(eligible), "heldForWorld": sum(1 for r in rows if r.get("heldForWorld")),
            "frameMsP50": percentile(intervals, 0.5), "frameMsP95": percentile(intervals, 0.95),
            "frameMsP99": percentile(intervals, 0.99),
            "cpuRenderMsP50": percentile(cpu, 0.5), "cpuRenderMsP95": percentile(cpu, 0.95)}
    gpu_ms = [r["gpuNanoseconds"] / 1e6 for r in gpu if r.get("throughputEligible") and r.get("gpuNanoseconds")]
    if gpu_ms:
        result["gpu"] = {"samples": len(gpu_ms), "msP50": percentile(gpu_ms, 0.5), "msP95": percentile(gpu_ms, 0.95),
                         "msP99": percentile(gpu_ms, 0.99)}
    for receipt_path in label_dir.glob("motion/*/receipt.json"):
        receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
        result["receipt"] = {k: receipt.get(k) for k in ("status", "failure", "elapsedSeconds", "heldForWorldTicks",
                                                          "heldForWorldTicksByPhase", "lastPathTick", "motion")}
    return result


def tick_costs(server_log: Path, window: Window) -> dict:
    """Mean milliseconds a tick of each server phase over the minute lines inside the window, the
    first minute after the window opens left out (it can hold the spawn area's preparation)."""
    rows = []
    for t, line in log_lines(server_log):
        match = TICK_COSTS.search(line)
        if match and window.holds(t):
            installed = re.search(r"installed=(\d+)", line)
            rows.append((int(match.group(1)), {p: float(m) for p, m, _ in PHASE.findall(match.group(2))},
                         int(installed.group(1)) if installed else 0))
    rows = rows[1:]
    ticks = sum(r[0] for r in rows)
    if not ticks:
        return {}
    phases = sorted({p for _, means, _ in rows for p in means})
    installed = sum(r[2] for r in rows)
    return {"ticks": ticks, "installed": installed,
            "msPerTick": {p: round(sum(m.get(p, 0.0) * k for k, m, _ in rows) / ticks, 3) for p in phases},
            "usPerCube": {p: round(1000 * sum(m.get(p, 0.0) * k for k, m, _ in rows) / max(installed, 1), 1)
                          for p in phases}}


def stage_times(client_log: Path, window: Window) -> dict:
    """Median over the window's stage reports of each stage's median CPU and GPU milliseconds."""
    reports: list[dict[str, tuple[float, float]]] = []
    current = None
    for t, line in log_lines(client_log):
        if STAGE_REPORT in line:
            current = {} if window.holds(t) else None
            if current is not None:
                reports.append(current)
            continue
        if current is not None:
            stage = STAGE_LINE.match(line)
            if stage:
                current[stage.group("name").strip()] = (float(stage.group("cpu")), float(stage.group("gpu")))
            elif not line.startswith(" "):
                current = None
    names = sorted({k for r in reports for k in r})
    return {"reports": len(reports),
            "stages": {k: {"cpu": percentile([r[k][0] for r in reports if k in r], 0.5),
                           "gpu": percentile([r[k][1] for r in reports if k in r], 0.5)} for k in names}}


FEATURES = {
    # Quadrature's own report lines: what each feature costs where the stage times cannot say (distant
    # terrain draws under the entities stage, by its own timer).
    "distantGpuMs": re.compile(r"drawn: \d+ tiles, \d+ faces, \d+ draws; GPU ([\d.]+) ms mean"),
    "distantClientMs": re.compile(r"; client thread ([\d.]+) ms mean"),
    "previewTiles": re.compile(r"^\s*built (\d+) \(sample [\d.]+ ms"),
    "previewSampleMsPerTile": re.compile(r"^\s*built \d+ \(sample ([\d.]+) ms"),
    "previewMeshMsPerTile": re.compile(r"mesh ([\d.]+) ms a tile\)"),
    "terrainBuilds": re.compile(r"^\s*terrain: \d+ meshes, \d+ waiting, (\d+) built"),
    "terrainMsPerBuild": re.compile(r"^\s*terrain: \d+ meshes, \d+ waiting, \d+ built \(([\d.]+) ms each\)"),
    "handoffPictures": re.compile(r"(\d+) pictures made by the handoff"),
}
COUNTED = {
    "permitCuts": "Client work permits",
    "slowPlacements": "Terrain: slow placement",
}


def feature_times(client_log: Path, window: Window) -> dict:
    """The last value inside the window of each of Quadrature's feature report figures (counters are
    cumulative, times are its own means), and how often the window logged a permit cut or a slow
    placement."""
    values: dict = {}
    counts: Counter = Counter()
    last = None
    for t, line in log_lines(client_log):
        if t is not None:
            last = t
        if not window.holds(last):
            continue
        for key, pattern in FEATURES.items():
            match = pattern.search(line)
            if match:
                values[key] = float(match.group(1))
        for key, text in COUNTED.items():
            if text in line:
                counts[key] += 1
    return {**values, **counts}


def gpu_samples(csv: Path) -> dict:
    """The GPU sampler's means: utilization, graphics clock, power, memory, and the power states seen."""
    if not csv.exists():
        return {}
    rows = [line.split(",") for line in csv.read_text(encoding="utf-8", errors="replace").splitlines()[1:] if line.strip()]

    def column(i):
        values = []
        for row in rows:
            try:
                values.append(float(re.sub(r"[^\d.]", "", row[i])))
            except (ValueError, IndexError):
                pass
        return round(sum(values) / len(values), 1) if values else None

    return {"samples": len(rows), "utilizationPercent": column(1), "graphicsMHz": column(3),
            "powerW": column(4), "memoryMiB": column(5),
            "powerStates": dict(Counter(r[2].strip() for r in rows if len(r) > 2))}


# ---------------------------------------------------------------------------------------------------
# check

REQUIRES = {
    # a switch: the switches it needs, as (property, value or None for any)
    "quadrature.gbuffer": [("quadrature.terrain", "own"), ("quadrature.reversedDepth", "true"), ("quadrature.faces", "true")],
    "quadrature.faces": [("quadrature.terrain", "own")],
    "quadrature.terrain.fade": [("quadrature.terrain", "own")],
    "quadrature.shadow": [("quadrature.terrain", "own")],
    "quadrature.shadow.near": [("quadrature.shadow", "true")],
    "quadrature.scene.gpu": [("quadrature.scene", "true")],
    "quadrature.scene.states": [("quadrature.scene.gpu", "true")],
    "quadrature.distant.joinFade": [("quadrature.distant", None), ("quadrature.terrain.fade", "true")],
    "quadrature.taa": [("quadrature.scale", None)],
    "quadrature.handoff.evaluate": [("quadrature.handoff", None), ("quadrature.handoff.shim", None),
                                    ("quadrature.handoff.dlssPath", None)],
}


def properties(options: str) -> dict[str, str]:
    return dict(re.findall(r"-D([\w.]+)=(\S+)", options))


def tour_views(tour: Path) -> list[dict]:
    data = json.loads(tour.read_text(encoding="utf-8"))
    return data["views"] if isinstance(data, dict) else data


def mods_by_name(paths: list[Path]) -> dict[str, list[Path]]:
    """Jars by the mod they hold (by the owner of most of their classes)."""
    from mod_index import jar_mod_name

    named: dict[str, list[Path]] = defaultdict(list)
    for path in paths:
        try:
            with zipfile.ZipFile(path) as jar:
                named[jar_mod_name(jar.namelist(), owner_of)].append(path)
        except zipfile.BadZipFile:
            named["(unreadable)"].append(path)
    return named


def helper_routes(client_mods: list[Path]) -> bool:
    """Whether a client mod is a motion helper that flies routes."""
    for mod in client_mods:
        try:
            with zipfile.ZipFile(mod) as jar:
                names = jar.namelist()
                if all(any(n.endswith(part) for n in names) for part in MOTION_HELPER):
                    return True
        except (FileNotFoundError, zipfile.BadZipFile):
            continue
    return False


def check(plan: dict, plan_path: Path | None = None) -> list[str]:
    """Every problem that would waste a turn, or nothing."""
    problems = []
    riven = Path(plan.get("riven", ""))
    for key in ("riven", "artifact", "cubicArtifact", "instance", "out", "runs"):
        if key not in plan:
            problems.append(f"plan has no {key}")
    if problems:
        return problems
    artifacts = [Path(plan["artifact"]), Path(plan["cubicArtifact"]), *map(Path, plan.get("clientMods", []))]
    for artifact in artifacts:
        if not artifact.is_file():
            problems.append(f"missing artifact {artifact}")
    if not (riven / "tools" / "runtime.py").is_file():
        problems.append(f"{riven} is not a Riven checkout (no tools/runtime.py)")
    instance = riven / ".local" / "cubic-standalone" / plan["instance"]
    if instance.exists():
        problems.append(f"instance {plan['instance']} exists; a diagnostics run starts on a fresh world")
    if Path(plan["out"]).exists() and any(Path(plan["out"]).iterdir()):
        problems.append(f"{plan['out']} is not empty; a run directory is never reused")
    client_mods = [Path(m) for m in plan.get("clientMods", [])]
    for timed in plan.get("timed", []):
        if not TIMED.match(timed):
            problems.append(f"timed: {timed} is not Class::method (fully qualified class, runtime method name)")
    labels = set()
    for entry in plan["runs"]:
        label = entry.get("label", "?")
        if label in labels:
            problems.append(f"{label}: two runs share the label")
        labels.add(label)
        options = properties(" ".join([plan.get("options", ""), entry.get("options", "")]))
        for switch, needs in REQUIRES.items():
            if switch in options and options[switch] not in ("false", "off"):
                for need, value in needs:
                    if need not in options or (value is not None and options[need] != value):
                        problems.append(f"{label}: -D{switch} needs -D{need}" + (f"={value}" if value else ""))
        if options.get("quadrature.handoff.evaluate") == "dlss":
            shim = Path(options.get("quadrature.handoff.shim", ""))
            runtime = Path(options.get("quadrature.handoff.dlssPath", ""))
            if not shim.is_file():
                problems.append(f"{label}: no DLSS shim at {shim}")
            if not (runtime / "nvngx_dlss.dll").is_file():
                problems.append(f"{label}: no nvngx_dlss.dll in {runtime}")
            if not any("quadrature-nvidia" in m.name for m in client_mods):
                problems.append(f"{label}: DLSS needs the quadrature-nvidia module among the client mods")
        if "quadrature.distant" in options:
            try:
                with zipfile.ZipFile(plan["artifact"]) as jar:
                    if not any("Preview" in n and n.startswith("cc/orthrus/riven/") for n in jar.namelist()):
                        problems.append(f"{label}: distant terrain needs a Riven with the client terrain preview")
            except (FileNotFoundError, zipfile.BadZipFile):
                pass
        if "tour" in entry:
            tour = Path(entry["tour"])
            if not tour.is_file():
                problems.append(f"{label}: no tour {tour}")
            else:
                views = tour_views(tour)
                for a, b in zip(views, views[1:]):
                    if all(a.get(k) == b.get(k) for k in ("x", "y", "z")):
                        problems.append(f"{label}: two consecutive views at {a.get('x')}, {a.get('y')}, {a.get('z')} "
                                        "(the second waits out the capture timeout)")
        elif "flight" in entry:
            flight = entry["flight"]
            if len(flight.get("start", [])) != 5:
                problems.append(f"{label}: a flight's start is [x, y, z, yaw, pitch]")
            route = flight.get("route") or []
            if not route or any(len(p) not in (3, 4) for p in route):
                problems.append(f"{label}: a flight's route is a list of [x, y, z] or [x, y, z, dwell seconds]")
            elif not helper_routes(client_mods):
                problems.append(f"{label}: a flight needs the motion helper 0.2.0 or later (route flights) "
                                "among the client mods")
            else:
                # The helper's own bounds, refused here rather than inside the game.
                bounds = {"speed": (0.1, 100, 16), "acceleration": (0.1, 400, 16), "cornerRadius": (0, 256, 24),
                          "turnRate": (1, 720, 120), "laps": (1, 100, 1), "settleSeconds": (2, 120, 60),
                          "stationarySeconds": (0, 120, 5), "holdSeconds": (0.25, 60, 10)}
                for key, (low, high, default) in bounds.items():
                    value = float(flight.get(key, default))
                    if not low <= value <= high:
                        problems.append(f"{label}: flight {key} {value} outside {low}..{high}")
                ticks = route_length(flight) / float(flight.get("speed", 16)) * 20
                if ticks > 72_000:
                    problems.append(f"{label}: the route takes {ticks:.0f} ticks at its speed; the helper's bound is 72,000")
                needed = flight_seconds(flight) + 240
                if entry.get("limitSeconds", 1500) < needed:
                    problems.append(f"{label}: limitSeconds {entry.get('limitSeconds', 1500)} leaves no room for the "
                                    f"flight and the game's start (about {needed:.0f} s)")
        else:
            problems.append(f"{label}: a run is a tour or a flight")
    if plan.get("remove"):
        problems += removal_problems(plan, riven)
    try:
        jfr_tool()
    except SystemExit as error:
        problems.append(str(error))
    free = shutil.disk_usage(riven).free / 2 ** 30 if riven.exists() else 0
    if free < 30:
        problems.append(f"{free:.0f} GiB free beside {riven}; a run needs 30")
    return problems


def removal_problems(plan: dict, riven: Path) -> list[str]:
    """A removal must name mods the instance would hold, and leave none without a mod it requires."""
    from mod_index import index_jar

    pins = riven / "dev" / "pins"
    candidates = sorted(pins.glob("*.jar")) if pins.exists() else []
    candidates += [Path(m) for m in plan.get("clientMods", [])]
    records = [index_jar(path, owner_of) for path in candidates if path.is_file()]
    removed = set(plan["remove"])
    known = {r.mod for r in records}
    problems = [f"remove: no mod named {name} among the pins and client mods" for name in removed - known]
    removed_ids = {i.lower() for r in records if r.mod in removed for i in r.mod_ids}
    for r in records:
        if r.mod in removed:
            continue
        missing = r.requires & removed_ids
        if missing:
            problems.append(f"remove: {r.mod} requires {', '.join(sorted(missing))}")
    return problems


# ---------------------------------------------------------------------------------------------------
# run

RECORD = ("-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints "
          "-XX:FlightRecorderOptions=repository=jfr-repository,maxchunksize=1m "
          "-XX:StartFlightRecording=name=diagnostics,filename=diagnostics-%p.jfr,settings=profile "
          "-Xlog:gc*:file=gc-%p.log:time,uptime")

MOTION_HELPER = ("/MotionValidation.class", "/RoutePath.class")  # a motion helper that flies routes (0.2.0)


def route_text(flight: dict) -> str:
    """The helper's route property: x,y,z[@dwell] per waypoint, joined by semicolons."""
    parts = []
    for point in flight["route"]:
        x, y, z = point[:3]
        parts.append(f"{x},{y},{z}" + (f"@{point[3]}" if len(point) > 3 and point[3] else ""))
    return ";".join(parts)


def route_length(flight: dict) -> float:
    """The route's length in blocks along its waypoints, laps included (corners not rounded)."""
    points = [flight["start"][:3]] + [p[:3] for p in flight["route"]] * int(flight.get("laps", 1))
    return sum(math.dist(a, b) for a, b in zip(points, points[1:]))


def flight_seconds(flight: dict) -> float:
    """About how long the helper takes, if the world keeps up: settle, stationary, the route at its
    speed (each stop's ramps at its acceleration), the dwells and the hold."""
    speed, acceleration = float(flight.get("speed", 16)), float(flight.get("acceleration", 16))
    stops = 1 + sum(1 for p in flight["route"] if len(p) > 3 and p[3]) * int(flight.get("laps", 1))
    dwell = sum(float(p[3]) for p in flight["route"] if len(p) > 3) * int(flight.get("laps", 1))
    return (float(flight.get("settleSeconds", 60)) + float(flight.get("stationarySeconds", 5))
            + route_length(flight) / speed + stops * speed / acceleration + dwell + float(flight.get("holdSeconds", 10)))


def flight_options(flight: dict) -> str:
    """The motion helper's settings for a route flight: no frame capture, so its GPU timers run; the
    route at a player's pace; a wall-clock bound past the flight's expected length."""
    seconds = flight_seconds(flight)
    values = {
        "enabled": "true", "scenario": "diagnostics-route", "captureEnabled": "false", "gpuTimers": "true",
        "settleTicks": int(float(flight.get("settleSeconds", 60)) * 20),
        "stationaryTicks": int(float(flight.get("stationarySeconds", 5)) * 20),
        "holdTicks": int(float(flight.get("holdSeconds", 10)) * 20),
        "route": route_text(flight),
        "routeSpeed": flight.get("speed", 16), "routeAcceleration": flight.get("acceleration", 16),
        "routeCornerRadius": flight.get("cornerRadius", 24), "routeTurnDegreesPerSecond": flight.get("turnRate", 120),
        "routePitch": flight["start"][4], "routeLaps": flight.get("laps", 1),
        "maxSeconds": int(min(3600, flight.get("maxSeconds", seconds * 2 + 120))),
    }
    return " ".join(f"-Driven.motion.{k}={v}" for k, v in values.items())


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


TIMED = re.compile(r"^[\w$.]+::[\w$<>]+$")


def record_options(plan: dict) -> str:
    """The recorder's options, with JFR method timing of the plan's "timed" methods (Class::method,
    runtime names) emitted every 10 s, the clock set against the samples."""
    timed = plan.get("timed") or []
    if not timed:
        return RECORD
    return RECORD.replace("settings=profile", "settings=profile,method-timing=" + ";".join(timed)
                          + ",jdk.MethodTiming#period=10s")


def play_command(python: str, plan: dict, entry: dict) -> tuple[list[str], str]:
    """A run's play command and its JVM options (the recorder's included): a tour keeps the motion
    helper off; a flight is the helper's route."""
    options = " ".join(filter(None, [plan.get("options", ""), entry.get("options", ""), record_options(plan)]))
    command = [python, "tools/runtime.py", "play", "--name", plan["instance"]]
    window = entry.get("window", plan.get("window"))
    if window:
        command += ["--window", window]
    if "tour" in entry:
        command += ["--tour", entry["tour"]]
        options += " -Driven.motion.enabled=false"
    else:
        options += " " + flight_options(entry["flight"])
    return command, options


def jcmd_tool(riven: Path) -> Path | None:
    """jcmd of the game's own Java, else of JAVA_HOME's."""
    name = "jcmd.exe" if os.name == "nt" else "jcmd"
    for candidate in (riven / ".local" / "toolchains" / "java26" / "bin" / name,
                      Path(os.environ.get("JAVA_HOME", "")) / "bin" / name):
        if candidate.is_file():
            return candidate
    return None


def dump_client(riven: Path, instance: Path, target: Path) -> str:
    """Ask the running client to write its recording now, before the launcher kills it."""
    tool = jcmd_tool(riven)
    try:
        pid = json.loads((instance / "processes.json").read_text(encoding="utf-8"))["client"]
    except (OSError, ValueError, KeyError):
        return "client dump: no client pid in processes.json"
    if tool is None:
        return "client dump: no jcmd"
    dumped = subprocess.run([str(tool), str(pid), "JFR.dump", "name=diagnostics",
                             f"filename={(target / 'client-dump.jfr').resolve()}"],
                            capture_output=True, text=True, timeout=120)
    return "client dump: written" if dumped.returncode == 0 and (target / "client-dump.jfr").exists() \
        else f"client dump failed: {(dumped.stdout + dumped.stderr).strip()[:200]}"


def count_lines(path: Path, text: str, since: float) -> int:
    """Lines holding text in a log written since a time (a log from an earlier start counts nothing)."""
    try:
        if path.stat().st_mtime < since:
            return 0
        return sum(1 for line in path.read_text(encoding="utf-8", errors="replace").splitlines() if text in line)
    except OSError:
        return 0


def drive(play: subprocess.Popen, entry: dict, riven: Path, instance: Path, target: Path, limit: float) -> tuple[int, list[str]]:
    """Watch a run to its end. A tour: once the client has captured every view, its recording is
    dumped, before the launcher kills the client. A flight: the motion helper flies its route and
    closes the client itself, which writes the client's recording at exit, and the launcher then
    stops the server. Returns the play's exit (3 past the limit) and notes."""
    began, notes = time.time(), []
    client_log = instance / "client" / "logs" / "latest.log"
    deadline = began + limit
    views = len(tour_views(Path(entry["tour"]))) if "tour" in entry else None
    dumped = False
    while play.poll() is None and time.time() < deadline:
        if views is not None and not dumped and count_lines(client_log, "Riven view captured", began) >= views:
            notes.append(dump_client(riven, instance, target))
            dumped = True
        time.sleep(0.5)
    if play.poll() is None:
        return 3, notes + ["past the run's limit"]
    return play.returncode, notes


def ablate(instance: Path, remove: list[str]) -> list[str]:
    """Move the named mods' jars out of both sides and rewrite the manifest's mod hashes to match."""
    manifest_path = instance / "standalone.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    moved = []
    for side in ("client", "server"):
        mods = instance / side / "mods"
        jars = sorted(mods.glob("*.jar"))
        for mod, paths in mods_by_name(jars).items():
            if mod not in remove:
                continue
            target = instance / "removed-mods" / side
            target.mkdir(parents=True, exist_ok=True)
            for path in paths:
                shutil.move(str(path), target / path.name)
                manifest.get("modHashes", {}).get(side, {}).pop(path.name, None)
                moved.append(f"{side}/{path.name}")
    manifest["diagnosticNote"] = "mods removed for an ablation: " + ", ".join(remove)
    manifest_path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    return moved


def collect(instance: Path, target: Path) -> list[str]:
    """Move a run's logs, recordings and frame records out of the instance into target; assemble
    the client's recorder chunks when the client was killed before writing its recording."""
    notes = []
    target.mkdir(parents=True, exist_ok=True)
    for side in ("client", "server"):
        root = instance / side
        log = root / "logs" / "latest.log"
        if log.exists():
            shutil.copy2(log, target / f"{side}-latest.log")
        for crash in list(root.glob("hs_err_pid*.log")) + list((root / "crash-reports").glob("*.txt")):
            shutil.move(str(crash), target / f"{side}-{crash.name}")
            notes.append(f"{side}: {crash.name}")
        for gc in root.glob("gc-*.log*"):
            shutil.move(str(gc), target / f"{side}-{gc.name}")
        recordings = list(root.glob("diagnostics-*.jfr"))
        finished = [f for f in recordings if f.stat().st_size > 0]
        for f in recordings:
            if f.stat().st_size == 0:
                f.unlink()
        repository = root / "jfr-repository"
        dumped = target / f"{side}-dump.jfr"
        if not finished and dumped.exists():
            # Dumped live before the launcher killed the process: the whole run, to the dump.
            shutil.move(str(dumped), target / f"{side}.jfr")
            notes.append(f"{side}: recording dumped before the end")
        elif finished:
            for f in finished:
                shutil.move(str(f), target / f"{side}.jfr")
            notes.append(f"{side}: recording written at exit")
        elif repository.exists():
            chunks = sorted(repository.rglob("*.jfr"))
            readable = [c for c in chunks
                        if subprocess.run([str(jfr_tool()), "summary", str(c)], capture_output=True).returncode == 0]
            if readable:
                staging = target / f"{side}-chunks"
                staging.mkdir(exist_ok=True)
                for chunk in readable:
                    shutil.copy2(chunk, staging / chunk.name)
                assembled = subprocess.run([str(jfr_tool()), "assemble", str(staging), str(target / f"{side}.jfr")],
                                           capture_output=True, text=True)
                notes.append(f"{side}: assembled {len(readable)} of {len(chunks)} chunks"
                             + ("" if assembled.returncode == 0 else f" (assemble failed: {assembled.stderr.strip()})"))
            else:
                notes.append(f"{side}: no readable recording ({len(chunks)} chunks, none finished)")
        if repository.exists():
            shutil.rmtree(repository, ignore_errors=True)
    evidence = instance / "client" / "motion-evidence"
    if evidence.exists():
        (target / "motion").mkdir(exist_ok=True)
        for record_dir in evidence.iterdir():
            shutil.move(str(record_dir), target / "motion" / record_dir.name)
    return notes


def run(plan_path: Path, dry: bool = False) -> int:
    plan = json.loads(plan_path.read_text(encoding="utf-8"))
    if dry:
        for entry in plan["runs"]:
            command, options = play_command("python", plan, entry)
            print(f"{entry['label']}: {' '.join(command)}\n  JAVA_TOOL_OPTIONS={options}")
        return 0
    if not os.environ.get("CUBIC_LAB_TURN_LEASE"):
        print("not inside a queued turn (no CUBIC_LAB_TURN_LEASE); start it through gpu_queue.py turn")
        return 2
    problems = check(plan)
    if problems:
        print("check failed:\n  " + "\n  ".join(problems))
        return 1
    riven = Path(plan["riven"]).resolve()
    out = Path(plan["out"]).resolve()
    out.mkdir(parents=True, exist_ok=True)
    artifacts = [Path(plan["artifact"]), Path(plan["cubicArtifact"]), *map(Path, plan.get("clientMods", []))]
    record = {"plan": plan, "artifacts": {str(a): sha256(a) for a in artifacts}, "runs": []}
    instance = riven / ".local" / "cubic-standalone" / plan["instance"]
    python = sys.executable
    command = [python, "tools/runtime.py", "prepare", "--name", plan["instance"], "--seed", str(plan.get("seed", 8675309)),
               "--no-distant-horizons", "--capture-ticks", "900", "--cubic-artifact", plan["cubicArtifact"],
               "--artifact", plan["artifact"]]
    for mod in plan.get("clientMods", []):
        command += ["--client-mod", mod]
    if any("flight" in r for r in plan["runs"]):
        command.append("--spectator")
    env = {**os.environ, "CUBATURE_LAB_JVM": plan.get("jvm", "zgc-fixed")}
    env.pop("JAVA_TOOL_OPTIONS", None)
    prepared = subprocess.run(command, cwd=riven, env=env, capture_output=True, text=True)
    (out / "prepare.log").write_text(prepared.stdout + prepared.stderr, encoding="utf-8")
    if prepared.returncode != 0:
        print(f"prepare {plan['instance']} failed; see prepare.log")
        return 1
    if plan.get("remove"):
        record["removed"] = ablate(instance, plan["remove"])
    from mod_index import MixinIndex, index_dirs, survey

    records = index_dirs([instance / "client" / "mods", instance / "server" / "mods"], owner_of)
    (out / "mod-index.json").write_text(json.dumps({"survey": survey(records), "index": MixinIndex.of(records).to_json()},
                                                   indent=2), encoding="utf-8")
    code = 0
    for entry in plan["runs"]:
        label = entry["label"]
        target = out / label
        command, options = play_command(python, plan, entry)
        if "flight" in entry:
            manifest_path = instance / "standalone.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["inspectionStart"] = entry["flight"]["start"]
            manifest_path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
            started = instance / "inspection-started.json"
            if started.exists():
                started.rename(instance / f"inspection-started-before-{label}.json")
        env = {**os.environ, "JAVA_TOOL_OPTIONS": options}
        target.mkdir(parents=True)
        sampler = None
        if shutil.which("nvidia-smi"):
            sampler = subprocess.Popen(
                ["nvidia-smi", "--query-gpu=timestamp,utilization.gpu,pstate,clocks.gr,power.draw,memory.used",
                 "--format=csv", "-lms", "1000", "-f", str(target / "gpu.csv")],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        began = time.time()
        print(f"RUN {label} {time.strftime('%H:%M:%S')}", flush=True)
        with (out / f"play-{label}.log").open("w", encoding="utf-8") as log:
            play = subprocess.Popen(command, cwd=riven, env=env, stdout=log, stderr=subprocess.STDOUT)
            result, notes = drive(play, entry, riven, instance, target, entry.get("limitSeconds", 1500))
            if result == 3:
                with (instance / "commands.txt").open("a", encoding="utf-8") as stream:
                    stream.write("stop\n")
                try:
                    play.wait(timeout=120)
                except subprocess.TimeoutExpired:
                    play.kill()
        if sampler:
            sampler.terminate()
        notes += collect(instance, target)
        print(f"RUN {label} EXIT={result} {time.strftime('%H:%M:%S')} " + "; ".join(notes), flush=True)
        record["runs"].append({"label": label, "exit": result, "seconds": round(time.time() - began, 1),
                               "options": options, "notes": notes})
        (out / "run.json").write_text(json.dumps(record, indent=2), encoding="utf-8")
        if result != 0:
            code = 1
            break
    return code


# ---------------------------------------------------------------------------------------------------
# attribute

def load_attribution(run_dir: Path, mods: list[Path]) -> Attribution:
    """The Mixin index (from the mods folders given, else the run's mod-index.json) and the plan's own
    owners by package prefix (its "owners", kept in run.json)."""
    from mod_index import MixinIndex, index_dirs

    record = run_dir / "run.json"
    plan = json.loads(record.read_text(encoding="utf-8"))["plan"] if record.exists() else {}
    owners, timed = plan.get("owners", {}), plan.get("timed", [])
    if mods:
        return Attribution(MixinIndex.of(index_dirs(mods, owner_of)), owners, timed)
    stored = run_dir / "mod-index.json"
    if stored.exists():
        return Attribution(MixinIndex.from_json(json.loads(stored.read_text(encoding="utf-8"))["index"]), owners, timed)
    return Attribution(None, owners, timed)


def attribute(run_dir: Path, cores: int, mods: list[Path]) -> int:
    attribution = load_attribution(run_dir, mods)
    result = {}
    for label_dir in sorted(p for p in run_dir.iterdir() if p.is_dir()):
        windows = windows_for(label_dir)
        run_window = next(w for w in windows if w.name == "run")
        entry = {"windows": {w.name: [w.start, w.end] for w in windows}}
        for side in ("server", "client"):
            recording = label_dir / f"{side}.jfr"
            if recording.exists():
                entry[side] = attribute_recording(recording, windows, cores, attribution)
        server_log = label_dir / "server-latest.log"
        if server_log.exists():
            entry["tickCosts"] = tick_costs(server_log, run_window)
        client_log = label_dir / "client-latest.log"
        if client_log.exists():
            entry["stageTimes"] = stage_times(client_log, run_window)
            entry["features"] = feature_times(client_log, run_window)
        entry["gpu"] = gpu_samples(label_dir / "gpu.csv")
        entry["flight"] = flight_times(label_dir)
        result[label_dir.name] = entry
        print(f"{label_dir.name}: attributed ({len(windows)} windows)", flush=True)
    (run_dir / "attribution.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    return 0


# ---------------------------------------------------------------------------------------------------
# report

# The thread groups a player waits on, by side: the JVM names the client's game thread main.
CRITICAL = {
    "server": {"Server thread": "the server tick",
               "Cubature Generation dimension # ##": "world generation",
               "Cubature Build dimension # ##": "column preparation"},
    "client": {"main": "the frame",
               "Cubature client build #": "Quadrature's meshing",
               "Chunk Batcher #": "the game's own chunk meshing"},
}
FRAME_GROUP = {"server": "Server thread", "client": "main"}
CLEANROOM = {"Forge (Cleanroom)", "Cleanroom", "Mixin", "LWJGL"}


def costliest(threads: dict) -> list[tuple[str, str, float, float]]:
    """For each thread group: (group, owner, share, CPU seconds) of its costliest owner that is not
    the JDK, heaviest groups first."""
    rows = []
    for group, t in sorted(threads.items(), key=lambda kv: -kv[1]["cpuSeconds"]):
        for owner, v in t["owners"].items():
            if owner not in ("JDK", "Other"):
                rows.append((group, owner, v["share"], v["cpuSeconds"]))
                break
    return rows


def ranking(entry: dict) -> list[dict]:
    """Each mod's cost on the critical thread groups: its own CPU plus the engine's done on its
    behalf (engine samples below its frames, at the group's CPU a sample), with its allocation
    and its compilation and deoptimizations, costliest first by the server tick and the frame."""
    mods: dict[str, Counter] = defaultdict(Counter)
    for side in ("server", "client"):
        part = entry.get(side, {}).get("run")
        if not part:
            continue
        for group, t in part["threads"].items():
            per_sample = t["cpuSeconds"] / t["samples"] if t["samples"] else 0.0
            column = ("workers" if group != FRAME_GROUP[side] else "tick" if side == "server" else "frame")
            for owner, v in t["owners"].items():
                if owner not in NOT_A_MOD:
                    mods[owner][column] += v["cpuSeconds"]
            for key, count in t["engineFor"].items():
                _engine, _, mod = key.partition(" for ")
                mods[mod][column + "Engine"] += count * per_sample
            for owner, mb in t["allocationMB"].items():
                if owner not in NOT_A_MOD:
                    mods[owner]["allocationMB"] += mb
        for owner, ms in part["compilation"]["msByOwner"].items():
            if owner not in NOT_A_MOD:
                mods[owner]["compileMs"] += ms
        for owner, n in part["compilation"]["deoptimizationsByOwner"].items():
            if owner not in NOT_A_MOD:
                mods[owner]["deoptimizations"] += n
    rows = [{"mod": m, **{k: round(v, 2) for k, v in c.items()}} for m, c in mods.items()]
    rows.sort(key=lambda r: -(r.get("tick", 0) + r.get("tickEngine", 0) + r.get("frame", 0) + r.get("frameEngine", 0)))
    return rows


def ranking_lines(entry: dict) -> list[str]:
    rows = ranking(entry)
    if not rows:
        return []
    out = ["| Mod | Server tick CPU s (+ engine for it) | Frame CPU s (+ engine) | Pools CPU s (+ engine) | "
           "Allocation MB | Compiles over 100 ms, ms | Deoptimizations |", "|---|---|---|---|---|---|---|"]
    for r in rows:
        out.append(f"| {r['mod']} | {r.get('tick', 0):.1f} (+{r.get('tickEngine', 0):.1f}) | "
                   f"{r.get('frame', 0):.1f} (+{r.get('frameEngine', 0):.1f}) | "
                   f"{r.get('workers', 0):.1f} (+{r.get('workersEngine', 0):.1f}) | {r.get('allocationMB', 0):.0f} | "
                   f"{r.get('compileMs', 0):.0f} | {r.get('deoptimizations', 0):.0f} |")
    return out + [""]


def survey_lines(run_dir: Path) -> list[str]:
    stored = run_dir / "mod-index.json"
    if not stored.exists():
        return []
    mods = json.loads(stored.read_text(encoding="utf-8"))["survey"]
    out = [f"## Mods of {run_dir.name}", "", "| Mod | Classes | Mixins, by engine system | Overwrites | "
           "Event handlers, commonest | Thread starts |", "|---|---|---|---|---|---|"]
    for mod, m in mods.items():
        systems = ", ".join(f"{s} {n}" for s, n in list(m["systems"].items())[:5])
        events = ", ".join(f"{e.rsplit('.', 1)[-1]} {n}" for e, n in list(m["events"].items())[:4])
        out.append(f"| {mod} | {m['classes']} | {m['mixins']}: {systems} | {len(m['overwrites'])} | "
                   f"{sum(m['events'].values())}: {events} | {sum(m['threads'].values())} |")
    return out + [""]


def systems_lines(entry: dict, survey: dict | None) -> list[str]:
    """The engine's own CPU on the server tick and the frame by system, with the mods that already
    patch each system (from the mod survey): where taking over engine work would pay."""
    out = []
    patched: dict[str, list[str]] = defaultdict(list)
    for mod, m in (survey or {}).items():
        for system, n in m.get("systems", {}).items():
            patched[system].append(f"{mod} {n}")
    for side, group in FRAME_GROUP.items():
        t = entry.get(side, {}).get("run", {}).get("threads", {}).get(group)
        if not t or not t.get("engineSystems"):
            continue
        out += [f"Engine CPU on {group} by system (the game's and Forge's own samples):", "",
                "| System | CPU s | Mods patching it (Mixins) |", "|---|---|---|"]
        for system, cpu in t["engineSystems"].items():
            out.append(f"| {system} | {cpu:.2f} | {', '.join(patched.get(system, [])) or 'none'} |")
        out.append("")
    return out


def lines_for(name: str, entry: dict, survey: dict | None = None) -> list[str]:
    out = [f"## {name}", "", "Mods by their cost on the critical threads:", ""] + ranking_lines(entry)
    out += systems_lines(entry, survey)
    for side in ("server", "client"):
        part = entry.get(side, {}).get("run")
        if not part:
            out.append(f"{side}: no recording")
            continue
        threads = part["threads"]
        out.append(f"### {side} ({part['seconds']} s from the first teleport)")
        out.append("")
        out.append("| Thread group | CPU s | Costliest owner | Owners |")
        out.append("|---|---|---|---|")
        for group, owner, share, cpu in costliest(threads)[:10]:
            owners = ", ".join(f"{o} {100 * v['share']:.0f}%" for o, v in list(threads[group]["owners"].items())[:5])
            out.append(f"| {group} | {threads[group]['cpuSeconds']:.1f} | {owner} ({100 * share:.0f}%) | {owners} |")
        out.append("")
        for group, role in CRITICAL[side].items():
            t = threads.get(group)
            if not t:
                continue
            calls = list(t["engineCalls"].items())[:6]
            if calls:
                out.append(f"Engine work done for mods on {group} ({role}), in samples:")
                out += [f"- {k}: {v}" for k, v in calls]
                out.append("")
            mods = [(k, v) for k, v in t["hotMethods"].items() if k.split(":")[0] not in ("JDK", "Other")][:8]
            if mods:
                out.append(f"Hottest methods on {group}:")
                out += [f"- {k}: {v}" for k, v in mods]
                out.append("")
        engine = Counter()
        for t in threads.values():
            for owner, v in t["owners"].items():
                if owner in CLEANROOM:
                    engine[owner] += v["cpuSeconds"]
        if engine:
            out.append("Cleanroom, Forge, Mixin and LWJGL, CPU s: " + ", ".join(f"{o} {s:.1f}" for o, s in engine.most_common()))
        alloc = Counter()
        for t in threads.values():
            alloc.update(t["allocationMB"])
        out.append("Allocation by owner, MB: " + ", ".join(f"{o} {mb:.0f}" for o, mb in alloc.most_common(6)))
        comp = part["compilation"]
        out.append("Compilation over 100 ms by owner, ms: " + ", ".join(f"{o} {v}" for o, v in comp["msByOwner"].items())
                   + "; deoptimizations: " + ", ".join(f"{o} {v}" for o, v in comp["deoptimizationsByOwner"].items()))
        out.append(f"GC: {part['gc']['pauseMsTotal']} ms of pauses in {part['gc']['collections']} collections, "
                   f"longest {part['gc']['pauseMsMax']} ms")
        timed = part.get("clocked") or {}
        if timed:
            out += ["", f"Clocked methods on the {side} (JFR method timing) against their samples:", "",
                    "| Method | Calls | Clock ms | ms a second | Samples imply, ms | Samples over clock |",
                    "|---|---|---|---|---|---|"]
            for method, v in sorted(timed.items(), key=lambda kv: -kv[1]["clockMs"]):
                rate = v["clockMs"] / part["seconds"] if part.get("seconds") else 0
                out.append(f"| {method} | {v['calls']} | {v['clockMs']} | {rate:.2f} | {v['sampledMs']} | "
                           f"{v['sampledOverClock']} |")
            out.append("")
            for group, t in sorted(trust(timed).items()):
                out.append(f"Sample trust on {group}: samples over clock {t['low']} to {t['high']} over "
                           f"{t['methods']} methods; {t['verdict']}.")
            out.append("")
        startup = entry.get(side, {}).get("startup")
        if startup:
            boot = Counter()
            for t in startup["threads"].values():
                for owner, v in t["owners"].items():
                    boot[owner] += v["cpuSeconds"]
            out.append("Startup CPU s by owner: " + ", ".join(f"{o} {s:.1f}" for o, s in boot.most_common(8)))
        out.append("")
    ticks = entry.get("tickCosts")
    if ticks:
        heavy = sorted(ticks["msPerTick"].items(), key=lambda kv: -kv[1])[:8]
        out.append("Server tick phases, ms a tick: " + ", ".join(f"{p} {v}" for p, v in heavy)
                   + f" ({ticks['installed']} cubes installed)")
    stages = entry.get("stageTimes", {}).get("stages")
    if stages:
        heavy = sorted(stages.items(), key=lambda kv: -(kv[1]["gpu"] or 0))[:6]
        out.append("Stage medians, ms CPU/GPU: " + ", ".join(f"{k} {v['cpu']}/{v['gpu']}" for k, v in heavy))
    flight = entry.get("flight")
    if flight:
        receipt = flight.get("receipt", {})
        motion = receipt.get("motion") or {}
        out.append(f"Flight: {receipt.get('status')} ({receipt.get('failure') or 'no failure'}), "
                   f"{motion.get('lengthBlocks')} blocks at {motion.get('speedBlocksPerSecond')} b/s in "
                   f"{receipt.get('elapsedSeconds')} s; held for the world {receipt.get('heldForWorldTicks')} ticks "
                   f"{receipt.get('heldForWorldTicksByPhase')}")
        for phase, v in flight.get("phases", {}).items():
            out.append(f"- {phase}: {v['frames']} frames ({v['heldForWorld']} held), frame p50/p95/p99 "
                       f"{v['frameMsP50']}/{v['frameMsP95']}/{v['frameMsP99']} ms, CPU render p50 {v['cpuRenderMsP50']} ms")
        if flight.get("gpu"):
            g = flight["gpu"]
            out.append(f"- GPU render bracket: p50/p95/p99 {g['msP50']}/{g['msP95']}/{g['msP99']} ms over {g['samples']} samples")
    features = entry.get("features") or {}
    if features:
        parts = []
        if "terrainBuilds" in features:
            parts.append(f"near terrain {features['terrainBuilds']:.0f} builds at {features.get('terrainMsPerBuild')} ms each")
        if "distantGpuMs" in features:
            parts.append(f"distant terrain GPU {features['distantGpuMs']} ms and client thread "
                         f"{features.get('distantClientMs')} ms a frame (its own timer; counted under the entities stage)")
        if "previewTiles" in features:
            parts.append(f"distant previews {features['previewTiles']:.0f} tiles, {features.get('previewSampleMsPerTile')} ms "
                         f"sampling and {features.get('previewMeshMsPerTile')} ms meshing a tile")
        if "handoffPictures" in features:
            parts.append(f"{features['handoffPictures']:.0f} pictures made by the handoff")
        parts.append(f"{features.get('permitCuts', 0)} client permit cuts, {features.get('slowPlacements', 0)} slow placements")
        out.append("Features: " + "; ".join(parts))
    gpu = entry.get("gpu")
    if gpu:
        out.append(f"GPU: {gpu.get('utilizationPercent')}% busy at {gpu.get('graphicsMHz')} MHz, {gpu.get('powerW')} W, "
                   f"{gpu.get('memoryMiB')} MiB, states {gpu.get('powerStates')}")
    views = [(w, entry["server"][w]) for w in entry.get("server", {}) if w.startswith(("view-", "flight-"))]
    if views:
        out.append("")
        out.append("| Window | s | Server thread: costliest owners |")
        out.append("|---|---|---|")
        for w, part in views:
            st = part["threads"].get("Server thread", {})
            owners = ", ".join(f"{o} {100 * v['share']:.0f}%" for o, v in list(st.get("owners", {}).items())[:4])
            out.append(f"| {w} | {part['seconds']} | {owners} |")
    out.append("")
    return out


def compare(readout: dict) -> list[str]:
    """For each label found in more than one run directory: thread CPU and stage GPU side by side."""
    by_label: dict[str, list[tuple[str, dict]]] = defaultdict(list)
    for name, entry in readout.items():
        run_dir, label = name.split("/", 1)
        by_label[label].append((run_dir, entry))
    out = []
    for label, entries in by_label.items():
        if len(entries) < 2:
            continue
        out += [f"## {label} compared", "", "| Thread group | " + " | ".join(r for r, _ in entries) + " |",
                "|---|" + "---|" * len(entries)]
        groups = Counter()
        for _, e in entries:
            for side in ("server", "client"):
                for g, t in e.get(side, {}).get("run", {}).get("threads", {}).items():
                    groups[f"{side}: {g}"] += t["cpuSeconds"]
        for g, _ in groups.most_common(10):
            side, group = g.split(": ", 1)
            cells = [f"{e.get(side, {}).get('run', {}).get('threads', {}).get(group, {}).get('cpuSeconds', 0):.1f}"
                     for _, e in entries]
            out.append(f"| {g} | " + " | ".join(cells) + " |")
        out.append("")
    return out


def report(run_dirs: list[Path], markdown: Path | None, json_out: Path | None) -> int:
    readout, surveys = {}, {}
    for run_dir in run_dirs:
        data = json.loads((run_dir / "attribution.json").read_text(encoding="utf-8"))
        stored = run_dir / "mod-index.json"
        survey = json.loads(stored.read_text(encoding="utf-8"))["survey"] if stored.exists() else None
        for label, entry in data.items():
            readout[f"{run_dir.name}/{label}"] = entry
            surveys[f"{run_dir.name}/{label}"] = survey
    text = ["# Cross-mod diagnostics", ""]
    for name, entry in readout.items():
        text += lines_for(name, entry, surveys[name])
    text += compare(readout)
    for run_dir in run_dirs:
        text += survey_lines(run_dir)
    print("\n".join(text))
    if markdown:
        markdown.write_text("\n".join(text) + "\n", encoding="utf-8")
    if json_out:
        json_out.write_text(json.dumps(readout, indent=2), encoding="utf-8")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    c = sub.add_parser("check")
    c.add_argument("plan", type=Path)
    p = sub.add_parser("run")
    p.add_argument("plan", type=Path)
    p.add_argument("--dry-run", action="store_true", help="print each run's command and options, run nothing")
    a = sub.add_parser("attribute")
    a.add_argument("run_dir", type=Path)
    a.add_argument("--cores", type=int, default=os.cpu_count() or 1,
                   help="logical processors of the machine that ran it (ThreadCPULoad is a share of all of them)")
    a.add_argument("--mods", type=Path, action="append", default=[],
                   help="mods folders to index instead of the run's mod-index.json")
    r = sub.add_parser("report")
    r.add_argument("run_dirs", type=Path, nargs="+")
    r.add_argument("--markdown", type=Path)
    r.add_argument("--json", type=Path)
    args = parser.parse_args(argv)
    if args.command == "check":
        problems = check(json.loads(args.plan.read_text(encoding="utf-8")), args.plan)
        print("ok" if not problems else "\n".join(problems))
        return 1 if problems else 0
    if args.command == "run":
        return run(args.plan, args.dry_run)
    if args.command == "attribute":
        return attribute(args.run_dir, args.cores, args.mods)
    return report(args.run_dirs, args.markdown, args.json)


if __name__ == "__main__":
    sys.exit(main())
