import datetime
import json
import os
import struct
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import diagnostics  # noqa: E402
import mod_index  # noqa: E402

BUILD = Path(__file__).resolve().parents[2] / "build"

SAMPLE = """jdk.ExecutionSample {
  startTime = 15:05:10.100 (2026-11-19)
  sampledThread = "Server thread" (javaThreadId = 40)
  state = "STATE_RUNNABLE"
  stackTrace = [
    java.util.HashMap.get(Object) line: 556
    net.minecraft.world.chunk.storage.ExtendedBlockStorage.func_177484_a(int, int, int, IBlockState) line: 50
    io.github.opencubicchunks.cubicchunks.core.world.cube.Cube.copyBlocks(CubePrimer, Supplier) line: 290
    net.minecraft.server.MinecraftServer.run() line: 500
    ...
  ]
}

jdk.ExecutionSample {
  startTime = 15:05:10.200 (2026-11-19)
  sampledThread = "Cubature generation worker 3" (javaThreadId = 51)
  state = "STATE_RUNNABLE"
  stackTrace = [
    java.lang.Math.floorMod(int, int) line: 1
    cc.orthrus.riven.worldgen.engine.ColumnEngine.fill(int, int) line: 77
    io.github.opencubicchunks.cubicchunks.core.server.Worker.run() line: 10
  ]
}

jdk.ExecutionSample {
  startTime = 15:05:04.000 (2026-11-19)
  sampledThread = "Server thread" (javaThreadId = 40)
  state = "STATE_RUNNABLE"
  stackTrace = [
    cc.orthrus.riven.worldgen.Early.thing() line: 1
  ]
}

jdk.ObjectAllocationSample {
  startTime = 15:05:11.000 (2026-11-19)
  objectClass = byte[] (classLoader = bootstrap)
  weight = 2.5 MB
  eventThread = "Client thread" (javaThreadId = 1)
  stackTrace = [
    java.util.Arrays.copyOf(byte[], int) line: 1
    net.minecraft.client.renderer.EntityRenderer.handler$zza000$quadrature$beginPass(int, float, long, CallbackInfo) line: 23
  ]
}

jdk.ThreadCPULoad {
  startTime = 15:05:10.000 (2026-11-19)
  user = 1.00%
  system = 0.50%
  eventThread = "Server thread" (javaThreadId = 40)
}

jdk.ThreadCPULoad {
  startTime = 15:05:20.000 (2026-11-19)
  user = 1.00%
  system = 0.50%
  eventThread = "Server thread" (javaThreadId = 40)
}

jdk.GarbageCollection {
  startTime = 15:05:12.000 (2026-11-19)
  duration = 55.7 ms
  sumOfPauses = 0.100 ms
  longestPause = 0.0290 ms
  eventThread = "ZDriverMajor" (osThreadId = 80096)
}

jdk.Compilation {
  startTime = 15:05:13.000 (2026-11-19)
  duration = 936 ms
  method = cc.orthrus.riven.worldgen.Drainage.build(long, long)
  compileLevel = 4
  eventThread = "C2 CompilerThread0" (javaThreadId = 20)
}

jdk.Deoptimization {
  startTime = 15:05:14.000 (2026-11-19)
  method = cc.orthrus.riven.worldgen.GeologyMap$Column.unit(int)
  reason = "unstable_if"
  action = "reinterpret"
  eventThread = "Cubature generation worker 3" (javaThreadId = 51)
}
"""


def at(text: str) -> float:
    return diagnostics.seconds_of_day(text)


def write_jar(path: Path, entries: dict[str, bytes]) -> Path:
    with zipfile.ZipFile(path, "w") as jar:
        for name, data in entries.items():
            jar.writestr(name, data)
    return path


def class_file(name, super_name, class_annotations, methods) -> bytes:
    """A minimal class file: methods with annotations, and class annotations (invisible)."""
    pool: list[bytes] = []
    index: dict = {}

    def utf8(s):
        if ("u", s) not in index:
            b = s.encode()
            pool.append(b"\x01" + struct.pack(">H", len(b)) + b)
            index[("u", s)] = len(pool)
        return index[("u", s)]

    def cls(s):
        if ("c", s) not in index:
            u = utf8(s)
            pool.append(b"\x07" + struct.pack(">H", u))
            index[("c", s)] = len(pool)
        return index[("c", s)]

    def element(tag, value):
        if tag in "sc":
            return tag.encode() + struct.pack(">H", utf8(value))
        return b"[" + struct.pack(">H", len(value)) + b"".join(element(t, v) for t, v in value)

    def annotation(descriptor, pairs):
        out = struct.pack(">HH", utf8(descriptor), len(pairs))
        for key, tag, value in pairs:
            out += struct.pack(">H", utf8(key)) + element(tag, value)
        return out

    def attribute(kind, annotations):
        body = struct.pack(">H", len(annotations)) + b"".join(annotation(*a) for a in annotations)
        return struct.pack(">HI", utf8(kind), len(body)) + body

    this, parent = cls(name), cls(super_name)
    method_bytes = b""
    for method, descriptor, annotations, visible in methods:
        attributes = [attribute("RuntimeVisibleAnnotations" if visible else "RuntimeInvisibleAnnotations", annotations)]
        method_bytes += struct.pack(">HHHH", 1, utf8(method), utf8(descriptor), 1) + attributes[0]
    class_attributes = attribute("RuntimeInvisibleAnnotations", class_annotations)
    body = struct.pack(">HHHH", 0x21, this, parent, 0) + struct.pack(">H", 0)
    body += struct.pack(">H", len(methods)) + method_bytes + struct.pack(">H", 1) + class_attributes
    return b"\xca\xfe\xba\xbe" + struct.pack(">HH", 0, 61) + struct.pack(">H", len(pool) + 1) + b"".join(pool) + body


MIXIN_CLASS = class_file(
    "dev/example/mixin/MixinWorld", "java/lang/Object",
    [("Lorg/spongepowered/asm/mixin/Mixin;", [("value", "[", [("c", "Lnet/minecraft/world/World;")])])],
    [("onTick", "()V", [("Lorg/spongepowered/asm/mixin/injection/Inject;", [("method", "[", [("s", "tick")])])], False),
     ("getHeight", "()I", [("Lorg/spongepowered/asm/mixin/Overwrite;", [])], False)])
EVENT_CLASS = class_file(
    "dev/example/Handlers", "java/lang/Object", [],
    [("onLoad", "(Lnet/minecraftforge/event/world/WorldEvent$Load;)V",
      [("Lnet/minecraftforge/fml/common/eventhandler/SubscribeEvent;", [])], True)])
REFMAP = {"mappings": {"dev/example/mixin/MixinWorld": {"getHeight": "Lnet/minecraft/world/World;func_72800_K()I",
                                                        "tick": "Lnet/minecraft/world/World;func_72835_b()V"}}}


class OwnerTest(unittest.TestCase):
    def test_mods_engine_and_transparent_code(self):
        self.assertEqual("Cubature", diagnostics.owner_of("io.github.opencubicchunks.cubicchunks.core.X.y() line: 1"))
        self.assertEqual("Minecraft", diagnostics.owner_of("net.minecraft.world.World.func_175623_d(BlockPos) line: 1"))
        self.assertEqual("ModularUI", diagnostics.owner_of("com.cleanroommc.modularui.api.A.b() line: 1"))
        self.assertEqual("Cleanroom", diagnostics.owner_of("com.cleanroommc.common.A.b() line: 1"))
        self.assertIsNone(diagnostics.owner_of("java.util.HashMap.get(Object) line: 1"))

    def test_a_merged_mixin_handler_belongs_to_the_mod_it_names(self):
        frame = "net.minecraft.server.MinecraftServer.handler$zza000$cubicchunks$onInitialSpawnLoad(CallbackInfo) line: 3"
        self.assertEqual("Cubature", diagnostics.owner_of(frame))
        unnamed = "net.minecraft.server.MinecraftServer.handler$zza000$onTick(CallbackInfo) line: 3"
        self.assertEqual("Mixin handler (unnamed mod)", diagnostics.owner_of(unnamed))

    def test_the_engine_is_charged_to_the_mod_below_it(self):
        c = diagnostics.charge([
            "java.util.HashMap.get(Object) line: 1",
            "net.minecraft.world.chunk.storage.ExtendedBlockStorage.func_177484_a(int) line: 1",
            "io.github.opencubicchunks.cubicchunks.core.world.cube.Cube.copyBlocks() line: 1",
            "net.minecraft.server.MinecraftServer.run() line: 1",
        ])
        self.assertEqual("Minecraft", c.owner)
        self.assertEqual("Cubature", c.behalf)
        self.assertEqual("Cube.copyBlocks", c.behalf_method)
        self.assertEqual(frozenset({"Cubature"}), c.mods)

    def test_a_mod_that_only_times_the_tick_is_not_who_the_engine_served(self):
        c = diagnostics.charge([
            "net.minecraftforge.common.DimensionManager.getIDs(boolean) line: 1",
            "net.minecraft.server.MinecraftServer.func_71190_q() line: 1",
            "net.minecraft.server.MinecraftServer.wrapMethod$zcf000$cubicchunks$measureTick(Operation) line: 1",
            "net.minecraft.server.MinecraftServer.run() line: 1",
        ])
        self.assertEqual("Forge (Cleanroom)", c.owner)
        self.assertIsNone(c.behalf)

    def test_the_frame_limiter_is_a_wait_not_a_cost(self):
        c = diagnostics.charge([
            "java.lang.Thread.yield() line: 1",
            "org.lwjgl.opengl.Sync.sync(int) line: 1",
            "org.lwjgl.opengl.Display.sync(int) line: 1",
            "net.minecraft.client.Minecraft.func_71411_J() line: 1",
        ])
        self.assertEqual("Idle (frame limiter)", c.owner)
        self.assertIsNone(c.behalf)

    def test_pass_through_dispatchers_and_loop_wrappers_charge_the_caller(self):
        c = diagnostics.charge([
            "net.minecraftforge.client.model.pipeline.VertexLighterSmoothAo.calcLightmap(float, float) line: 1",
            "codechicken.lib.render.block.CCBlockRendererDispatcher.func_175018_a(IBlockState) line: 1",
            "cc.orthrus.quadrature.terrain.CubeMesher.mesh() line: 1",
        ])
        self.assertEqual(("Forge (Cleanroom)", "Quadrature"), (c.owner, c.behalf))
        c = diagnostics.charge([
            "net.minecraft.client.renderer.RenderGlobal.func_174970_a() line: 1",
            "net.minecraft.client.Minecraft.wrapOperation$zjp000$quadrature$noteOutOfMemory(Operation) line: 1",
            "net.minecraft.client.main.Main.main(String[]) line: 1",
        ])
        self.assertEqual(("Minecraft", None), (c.owner, c.behalf))

    def test_pool_threads_group_together(self):
        self.assertEqual("Cubature generation worker #", diagnostics.thread_group("Cubature generation worker 12"))

    def test_a_plan_names_owners_of_its_own_tools(self):
        attribution = diagnostics.Attribution(None, {"dev.example.motion.": "Motion helper"})
        self.assertEqual("Motion helper", attribution.owner("dev.example.motion.RoutePath.pose(int) line: 1"))
        self.assertEqual("Other", diagnostics.owner_of("dev.example.motion.RoutePath.pose(int) line: 1"))


class IndexTest(unittest.TestCase):
    def test_a_class_file_gives_its_annotations_and_members(self):
        info = mod_index.read_class(MIXIN_CLASS)
        self.assertEqual("dev/example/mixin/MixinWorld", info.name)
        self.assertEqual(["Lnet/minecraft/world/World;"], [v for v in info.annotations[0].values["value"]])
        self.assertEqual(["onTick", "getHeight"], [m.name for m in info.methods])

    def test_a_mixin_gives_its_targets_overwrites_and_handlers_by_runtime_name(self):
        mixin = mod_index.mixin_of("Example", mod_index.read_class(MIXIN_CLASS), REFMAP)
        self.assertEqual(["net/minecraft/world/World"], mixin.targets)
        self.assertEqual(["func_72800_K"], mixin.overwrites)
        self.assertEqual([("inject", "onTick", ["func_72835_b"])], mixin.handlers)

    def test_a_jar_is_indexed_and_attribution_uses_the_index(self):
        BUILD.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=BUILD) as d:
            config = {"package": "dev.example.mixin", "mixins": ["MixinWorld"], "refmap": "example.refmap.json"}
            jar = write_jar(Path(d) / "example.jar", {
                "mixins.example.json": json.dumps(config), "example.refmap.json": json.dumps(REFMAP),
                "dev/example/mixin/MixinWorld.class": MIXIN_CLASS, "dev/example/Handlers.class": EVENT_CLASS})
            record = mod_index.index_jar(jar, lambda f: "Example")
        self.assertEqual(["mixins.example.json"], record.configs)
        self.assertEqual({"net.minecraftforge.event.world.WorldEvent$Load": 1}, dict(record.events))
        index = mod_index.MixinIndex.of([record])
        attribution = diagnostics.Attribution(index)
        self.assertEqual("Example", attribution.owner("net.minecraft.world.World.func_72800_K() line: 4"))
        self.assertEqual("Example", attribution.owner("net.minecraft.world.World.handler$zzb000$onTick() line: 4"))
        self.assertEqual("Minecraft", attribution.owner("net.minecraft.world.World.func_72835_b() line: 4"))
        again = mod_index.MixinIndex.from_json(index.to_json())
        self.assertEqual(index.overwrites, again.overwrites)


class ReadTest(unittest.TestCase):
    def test_events_inside_the_window_are_charged(self):
        windows = [diagnostics.Window(at("15:05:05"), 1e9, "run")]
        tables = diagnostics.read_events(SAMPLE.splitlines(True), windows)["run"]
        self.assertEqual({"Minecraft": 1}, dict(tables.samples["Server thread"]))
        self.assertEqual({"Minecraft for Cubature": 1}, dict(tables.behalf["Server thread"]))
        self.assertEqual({"Riven": 1}, dict(tables.samples["Cubature generation worker #"]))
        self.assertEqual(2.5e6, tables.allocation["Client thread"]["Quadrature"])
        self.assertEqual([0.1], tables.gc_pause_ms)
        self.assertEqual({"Riven": 936.0}, dict(tables.compile_ms))
        self.assertEqual({"Riven": 1}, dict(tables.deopts))
        self.assertEqual({"unstable_if": 1}, dict(tables.deopt_reasons))

    def test_an_event_counts_in_every_window_holding_it(self):
        windows = [diagnostics.Window(0, at("15:05:05"), "startup"), diagnostics.Window(at("15:05:05"), 1e9, "run"),
                   diagnostics.Window(at("15:05:10"), at("15:05:11"), "view-00")]
        tables = diagnostics.read_events(SAMPLE.splitlines(True), windows)
        self.assertEqual({"Riven": 1}, dict(tables["startup"].samples["Server thread"]))
        self.assertEqual(2, sum(sum(c.values()) for c in tables["view-00"].samples.values()))

    def test_cpu_is_split_by_samples(self):
        tables = diagnostics.read_events(SAMPLE.splitlines(True), [diagnostics.Window()])["all"]
        summary = diagnostics.summarize(tables, cores=10)
        server = summary["threads"]["Server thread"]
        self.assertAlmostEqual(1.5, server["cpuSeconds"], places=3)  # 1.5% of 10 cores for 10 s
        self.assertEqual(2, server["samples"])
        self.assertAlmostEqual(0.75, server["owners"]["Minecraft"]["cpuSeconds"], places=3)

    def test_mods_are_ranked_by_their_own_and_their_engine_time_on_the_tick(self):
        entry = {"server": {"run": {
            "threads": {"Server thread": {"cpuSeconds": 10.0, "samples": 100,
                                          "owners": {"Minecraft": {"share": 0.5, "cpuSeconds": 5.0},
                                                     "Cubature": {"share": 0.3, "cpuSeconds": 3.0},
                                                     "Riven": {"share": 0.2, "cpuSeconds": 2.0}},
                                          "engineFor": {"Minecraft for Riven": 40, "Minecraft for Cubature": 10},
                                          "allocationMB": {"Riven": 50.0}}},
            "compilation": {"msByOwner": {"Riven": 900.0}, "deoptimizationsByOwner": {"Riven": 3}}}}}
        rows = diagnostics.ranking(entry)
        self.assertEqual(["Riven", "Cubature"], [r["mod"] for r in rows])
        self.assertAlmostEqual(4.0, rows[0]["tickEngine"])
        self.assertEqual(900.0, rows[0]["compileMs"])

    def test_a_timed_method_is_clocked_over_its_window_and_set_against_its_samples(self):
        timing = """jdk.MethodTiming {
  startTime = 15:05:00.000 (2026-11-19)
  method = io.example.Autosave.discover(long, long)
  invocations = 100
  average = 0.100 ms
}

jdk.MethodTiming {
  startTime = 15:05:20.000 (2026-11-19)
  method = io.example.Autosave.discover(long, long)
  invocations = 300
  average = 0.150 ms
}

jdk.ExecutionSample {
  startTime = 15:05:10.000 (2026-11-19)
  sampledThread = "Server thread" (javaThreadId = 40)
  state = "STATE_RUNNABLE"
  stackTrace = [
    io.example.Autosave.discover(long, long) line: 1
    net.minecraft.server.MinecraftServer.run() line: 1
  ]
}

jdk.ExecutionSample {
  startTime = 15:05:11.000 (2026-11-19)
  sampledThread = "Server thread" (javaThreadId = 40)
  state = "STATE_RUNNABLE"
  stackTrace = [
    net.minecraft.server.MinecraftServer.run() line: 1
  ]
}

jdk.ThreadCPULoad {
  startTime = 15:05:05.000 (2026-11-19)
  user = 0.10%
  system = 0.00%
  eventThread = "Server thread" (javaThreadId = 40)
}

jdk.ThreadCPULoad {
  startTime = 15:05:15.000 (2026-11-19)
  user = 0.10%
  system = 0.00%
  eventThread = "Server thread" (javaThreadId = 40)
}
"""
        attribution = diagnostics.Attribution(None, None, ["io.example.Autosave::discover"])
        window = diagnostics.Window(at("15:05:01"), 1e9, "run")
        tables = diagnostics.read_events(timing.splitlines(True), [window], attribution)["run"]
        summary = diagnostics.summarize(tables, cores=10, window=window)
        clock = summary["clocked"]["Autosave.discover"]
        # 300 x 0.15 - 100 x 0.10 = 35 ms over 200 calls; the thread's 0.1 s of CPU (0.1% of 10 cores
        # for 10 s) over 2 samples is 50 ms a sample, one of them in the method.
        self.assertEqual(200, clock["calls"])
        self.assertAlmostEqual(35.0, clock["clockMs"])
        self.assertAlmostEqual(50.0, clock["sampledMs"])
        self.assertAlmostEqual(1.43, clock["sampledOverClock"])

    def test_trust_is_judged_per_thread_from_its_clocks(self):
        clocks = {"A.a": {"group": "pool #", "sampledOverClock": 0.95, "clockMs": 5000},
                  "A.b": {"group": "pool #", "sampledOverClock": 1.1, "clockMs": 4000},
                  "S.a": {"group": "Server thread", "sampledOverClock": 0.3, "clockMs": 9000},
                  "S.b": {"group": "Server thread", "sampledOverClock": 2.5, "clockMs": 800},
                  "S.c": {"group": "Server thread", "sampledOverClock": 9.0, "clockMs": 50}}  # too little to judge by
        verdicts = diagnostics.trust(clocks)
        self.assertEqual("samples agree with the clocks", verdicts["pool #"]["verdict"])
        self.assertIn("misstate by up to 3.3x", verdicts["Server thread"]["verdict"])
        self.assertEqual(2, verdicts["Server thread"]["methods"])

    def test_timed_methods_join_the_recorders_options(self):
        options = diagnostics.record_options({"timed": ["a.B::c", "d.E::f"]})
        self.assertIn("method-timing=a.B::c;d.E::f,jdk.MethodTiming#period=10s", options)
        self.assertEqual(diagnostics.RECORD, diagnostics.record_options({}))

    def test_a_gap_in_a_threads_points_is_not_bridged(self):
        points = [(0.0, 5.0), (10.0, 5.0), (300.0, 5.0)]
        self.assertAlmostEqual(0.5 + 0.75, diagnostics.cpu_seconds(points, cores=1, period=10.0))


def local_utc(hms: str) -> str:
    today = datetime.date.today()
    h, m, s = (int(x) for x in hms.split(":"))
    local = datetime.datetime(today.year, today.month, today.day, h, m, s).astimezone()
    return local.astimezone(datetime.timezone.utc).isoformat().replace("+00:00", "Z")


class WindowTest(unittest.TestCase):
    def test_views_and_flight_phases_become_windows(self):
        BUILD.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=BUILD) as d:
            label = Path(d)
            (label / "server-latest.log").write_text(
                "[15:00:00] [Server thread/INFO]: Done (3s)!\n[15:00:10] [Server thread/INFO]: Teleported CubicLab to 1, 2, 3\n",
                encoding="utf-8")
            (label / "client-latest.log").write_text(
                "[15:00:40] [Client thread/INFO]: Riven view captured: a.png\n"
                "[15:01:20] [Client thread/INFO]: Riven view captured: b.png\n", encoding="utf-8")
            motion = label / "motion" / "run"
            motion.mkdir(parents=True)
            # The helper's own records: phase, eligibility, interval and held frames.
            frames = [{"utc": local_utc("15:02:00"), "phase": "settling", "throughputEligible": False,
                       "intervalMilliseconds": 9.0, "cpuRenderHookScopeMilliseconds": 2.0, "heldForWorld": False},
                      {"utc": local_utc("15:02:30"), "phase": "move", "throughputEligible": True,
                       "intervalMilliseconds": 16.0, "cpuRenderHookScopeMilliseconds": 3.0, "heldForWorld": False},
                      {"utc": local_utc("15:03:30"), "phase": "move", "throughputEligible": True,
                       "intervalMilliseconds": 18.0, "cpuRenderHookScopeMilliseconds": 4.0, "heldForWorld": True}]
            (motion / "render-frames.ndjson").write_text("\n".join(json.dumps(f) for f in frames), encoding="utf-8")
            gpu = [{"renderFrame": 1, "gpuNanoseconds": 5_500_000, "throughputEligible": True},
                   {"renderFrame": 2, "gpuNanoseconds": 90_000_000, "throughputEligible": False}]
            (motion / "gpu-frames.ndjson").write_text("\n".join(json.dumps(g) for g in gpu), encoding="utf-8")
            (motion / "receipt.json").write_text(json.dumps({"status": "complete", "heldForWorldTicks": 7,
                                                            "motion": {"kind": "route", "lengthBlocks": 980.0}}), encoding="utf-8")
            windows = {w.name: (w.start, w.end) for w in diagnostics.windows_for(label)}
            times = diagnostics.flight_times(label)
        self.assertEqual((0.0, at("15:00:10")), windows["startup"])
        self.assertEqual((at("15:00:10"), at("15:00:40")), windows["view-00"])
        self.assertEqual((at("15:00:40"), at("15:01:20")), windows["view-01"])
        self.assertEqual((at("15:02:30"), at("15:03:30")), windows["flight-move"])
        self.assertEqual(18.0, times["phases"]["move"]["frameMsP50"])
        self.assertEqual(1, times["phases"]["move"]["heldForWorld"])
        self.assertIsNone(times["phases"]["settling"]["frameMsP50"])  # no eligible frame while settling
        self.assertEqual(5.5, times["gpu"]["msP50"])  # the ineligible sample is left out
        self.assertEqual(7, times["receipt"]["heldForWorldTicks"])


class LogTest(unittest.TestCase):
    def test_tick_costs_leave_out_the_first_minute(self):
        lines = [
            "[13:54:02] [Server thread/INFO] [cubicchunks]: Cubature tick costs: ticks=783 CUBE_CONSTRUCT=9.0/1.0ms installed=100 empty=0 (mean/p95)",
            "[13:55:02] [Server thread/INFO] [cubicchunks]: Cubature tick costs: ticks=1200 CUBE_CONSTRUCT=1.0/2.0ms CUBE_FIRST_LIGHT=0.5/1.0ms installed=600 empty=1 (mean/p95)",
            "[13:56:02] [Server thread/INFO] [cubicchunks]: Cubature tick costs: ticks=1200 CUBE_CONSTRUCT=2.0/2.0ms CUBE_FIRST_LIGHT=0.5/1.0ms installed=600 empty=1 (mean/p95)",
        ]
        BUILD.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=BUILD) as d:
            log = Path(d) / "server.log"
            log.write_text("\n".join(lines), encoding="utf-8")
            costs = diagnostics.tick_costs(log, diagnostics.Window())
        self.assertEqual(2400, costs["ticks"])
        self.assertEqual(1.5, costs["msPerTick"]["CUBE_CONSTRUCT"])
        self.assertEqual(3000.0, costs["usPerCube"]["CUBE_CONSTRUCT"])

    def test_quadratures_feature_lines_are_read_from_the_run(self):
        lines = [
            "[17:00:00] [Client thread/INFO]: Stage times, median/95th in ms (CPU | GPU):",
            "  terrain: 2291 meshes, 0 waiting, 23334 built (1.619 ms each), 80229 empty",
            "  scaler: drawn 858 by 482 of 1280 by 720 (scale 0.67), temporal on, 0 pictures made by the handoff; passes",
            "[17:00:01] [Client thread/INFO]: Distant terrain: tiles wanted/stored by level L0 144/144",
            "  drawn: 392 tiles, 1893908 faces, 6 draws; GPU 2.162 ms mean, 2.183 most (600 samples); client thread 0.478 ms mean",
            "  built 1856 (sample 319.3 ms, mesh 1.32 ms a tile), stored 1856",
            "[17:00:02] [Client thread/INFO]: Client work permits 4 -> 2: cut: frame p95 at or over 25.0 ms",
            "[16:59:00] [Client thread/INFO]: Client work permits 4 -> 2: cut: before the window",
        ]
        BUILD.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=BUILD) as d:
            log = Path(d) / "client.log"
            log.write_text("\n".join(lines[-1:] + lines[:-1]), encoding="utf-8")
            features = diagnostics.feature_times(log, diagnostics.Window(at("16:59:30"), 1e9, "run"))
        self.assertEqual(23334, features["terrainBuilds"])
        self.assertEqual(1.619, features["terrainMsPerBuild"])
        self.assertEqual(2.162, features["distantGpuMs"])
        self.assertEqual(0.478, features["distantClientMs"])
        self.assertEqual(319.3, features["previewSampleMsPerTile"])
        self.assertEqual(0, features["handoffPictures"])
        self.assertEqual(1, features["permitCuts"])

    def test_a_flight_is_the_motion_helpers_route(self):
        flight = {"start": [100, 200, 300, 270, 30], "route": [[700, 200, 300], [700, 200, 700, 10]],
                  "speed": 16, "settleSeconds": 60, "holdSeconds": 10}
        _, options = diagnostics.play_command("python", {"instance": "x"}, {"label": "f", "flight": flight})
        self.assertIn("-Driven.motion.enabled=true", options)
        self.assertIn("-Driven.motion.captureEnabled=false", options)
        self.assertIn("-Driven.motion.route=700,200,300;700,200,700@10", options)
        self.assertIn("-Driven.motion.settleTicks=1200", options)
        self.assertIn("-Driven.motion.routePitch=30", options)
        self.assertIn("-XX:+DebugNonSafepoints", options)
        self.assertAlmostEqual(1000.0, diagnostics.route_length(flight))
        # settle 60 + stationary 5 + 1000/16 + two stops' ramps + dwell 10 + hold 10
        self.assertAlmostEqual(60 + 5 + 62.5 + 2 * 1.0 + 10 + 10, diagnostics.flight_seconds(flight))

    def test_a_tour_keeps_the_motion_helper_off(self):
        _, options = diagnostics.play_command("python", {"instance": "x"}, {"label": "t", "tour": "t.json"})
        self.assertIn("-Driven.motion.enabled=false", options)
        self.assertIn("name=diagnostics", options)

    def test_a_dumped_client_recording_is_kept_as_the_clients(self):
        BUILD.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=BUILD) as d:
            instance, target = Path(d) / "instance", Path(d) / "run" / "1"
            for side in ("client", "server"):
                (instance / side / "logs").mkdir(parents=True)
                (instance / side / "logs" / "latest.log").write_text("x", encoding="utf-8")
            target.mkdir(parents=True)
            (target / "client-dump.jfr").write_bytes(b"jfr")
            (instance / "server" / "diagnostics-7.jfr").write_bytes(b"jfr")
            notes = diagnostics.collect(instance, target)
            self.assertTrue((target / "client.jfr").exists())
            self.assertTrue((target / "server.jfr").exists())
        self.assertIn("client: recording dumped before the end", notes)


class PlanTest(unittest.TestCase):
    def setUp(self):
        BUILD.mkdir(exist_ok=True)
        self.dir = tempfile.TemporaryDirectory(dir=BUILD)
        root = Path(self.dir.name)
        (root / "riven" / "tools").mkdir(parents=True)
        (root / "riven" / "tools" / "runtime.py").write_text("", encoding="utf-8")
        (root / "jdk" / "bin").mkdir(parents=True)
        (root / "jdk" / "bin" / ("jfr.exe" if os.name == "nt" else "jfr")).write_text("", encoding="utf-8")
        self.saved_home = os.environ.get("JAVA_HOME")
        os.environ["JAVA_HOME"] = str(root / "jdk")
        write_jar(root / "riven.jar", {"cc/orthrus/riven/worldgen/TerrainPreview.class": b""})
        write_jar(root / "cubature.jar", {"io/github/opencubicchunks/A.class": b""})
        write_jar(root / "quadrature.jar", {"cc/orthrus/quadrature/A.class": b""})
        write_jar(root / "motion.jar", {"dev/example/motion/MotionValidation.class": b"", "dev/example/motion/RoutePath.class": b""})
        write_jar(root / "motion-0.1.3.jar", {"dev/example/motion/MotionValidation.class": b"", "dev/example/motion/MotionPath.class": b""})
        tour = [{"x": 1, "y": 2, "z": 3}, {"x": 1, "y": 2, "z": 3}]
        (root / "tour.json").write_text(json.dumps(tour), encoding="utf-8")
        self.root = root
        self.plan = {"riven": str(root / "riven"), "artifact": str(root / "riven.jar"),
                     "cubicArtifact": str(root / "cubature.jar"), "clientMods": [str(root / "quadrature.jar")],
                     "instance": "fresh", "out": str(root / "out"), "options": "-Dquadrature.terrain=own",
                     "runs": [{"label": "1", "tour": str(root / "tour.json"),
                               "options": "-Dquadrature.gbuffer=true -Dquadrature.reversedDepth=false"},
                              {"label": "2", "flight": {"start": [0, 0, 0, 0, 0], "route": [[600, 0, 0]]}}]}

    def tearDown(self):
        if self.saved_home is None:
            os.environ.pop("JAVA_HOME", None)
        else:
            os.environ["JAVA_HOME"] = self.saved_home
        self.dir.cleanup()

    def test_check_names_what_would_waste_a_turn(self):
        problems = "\n".join(diagnostics.check(self.plan))
        self.assertIn("-Dquadrature.gbuffer needs -Dquadrature.reversedDepth=true", problems)
        self.assertIn("two consecutive views", problems)
        self.assertIn("needs the motion helper 0.2.0", problems)
        self.plan["clientMods"].append(str(self.root / "motion-0.1.3.jar"))
        self.assertIn("needs the motion helper 0.2.0", "\n".join(diagnostics.check(self.plan)))
        self.plan["clientMods"].append(str(self.root / "motion.jar"))
        self.plan["runs"][1]["limitSeconds"] = 100
        self.plan["runs"][1]["flight"]["speed"] = 500
        problems = "\n".join(diagnostics.check(self.plan))
        self.assertIn("flight speed 500.0 outside 0.1..100", problems)
        self.assertIn("leaves no room for the flight", problems)

    def test_own_terrain_reversed_depth_and_faces_are_on_unless_set_off(self):
        self.plan["options"] = ""
        self.plan["runs"][0]["options"] = "-Dquadrature.gbuffer=true"
        self.assertNotIn("-Dquadrature.gbuffer needs", "\n".join(diagnostics.check(self.plan)))
        self.plan["runs"][0]["options"] = "-Dquadrature.gbuffer=true -Dquadrature.terrain=game"
        self.assertIn("-Dquadrature.gbuffer needs -Dquadrature.terrain=own", "\n".join(diagnostics.check(self.plan)))

    def test_a_sound_plan_passes(self):
        (self.root / "tour.json").write_text(json.dumps([{"x": 1, "y": 2, "z": 3}, {"x": 9, "y": 2, "z": 3}]), encoding="utf-8")
        self.plan["runs"][0]["options"] = "-Dquadrature.faces=true"
        self.plan["clientMods"].append(str(self.root / "motion.jar"))
        problems = [p for p in diagnostics.check(self.plan) if "GiB free" not in p]
        self.assertEqual([], problems)

    def test_dlss_needs_its_shim_runtime_and_module(self):
        self.plan["runs"][0]["options"] = ("-Dquadrature.handoff.evaluate=dlss -Dquadrature.handoff.shim=missing.dll "
                                           "-Dquadrature.handoff.dlssPath=nowhere")
        problems = "\n".join(diagnostics.check(self.plan))
        self.assertIn("-Dquadrature.handoff.evaluate needs -Dquadrature.handoff", problems)
        self.assertIn("no DLSS shim", problems)
        self.assertIn("no nvngx_dlss.dll", problems)
        self.assertIn("quadrature-nvidia module", problems)

    def test_an_ablation_moves_a_mods_jars_and_rewrites_the_hashes(self):
        instance = self.root / "instance"
        for side in ("client", "server"):
            mods = instance / side / "mods"
            mods.mkdir(parents=True)
            write_jar(mods / "gtceu.jar", {"gregtech/api/A.class": b""})
            write_jar(mods / "riven.jar", {"cc/orthrus/riven/A.class": b""})
        hashes = {side: {"gtceu.jar": "a", "riven.jar": "b"} for side in ("client", "server")}
        (instance / "standalone.json").write_text(json.dumps({"modHashes": hashes}), encoding="utf-8")
        moved = diagnostics.ablate(instance, ["GTCEu Reloaded"])
        manifest = json.loads((instance / "standalone.json").read_text(encoding="utf-8"))
        self.assertEqual(["client/gtceu.jar", "server/gtceu.jar"], moved)
        self.assertEqual({"riven.jar": "b"}, manifest["modHashes"]["server"])
        self.assertTrue((instance / "removed-mods" / "client" / "gtceu.jar").exists())
        self.assertIn("GTCEu Reloaded", manifest["diagnosticNote"])


if __name__ == "__main__":
    unittest.main()
