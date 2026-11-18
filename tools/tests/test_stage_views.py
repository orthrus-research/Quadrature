import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import stage_views  # noqa: E402


def report(time: str, gpu: float, waiting: int, cubes: int = 1500) -> str:
    return (
        f"[{time}] [Client thread/INFO]: Stage times, median/95th in ms (CPU | GPU):\n"
        f"  setup                   0.014/ 0.021 |  0.005/ 0.010\n"
        f"  opaque terrain          0.400/ 0.600 |  {gpu:.3f}/ {gpu + 0.5:.3f}\n"
        f"  sum of medians          0.414        |  {gpu + 0.005:.3f}\n"
        f"  frames 1000, out of order 0, unknown stages 0, outside a frame 0, GPU results dropped 0\n"
        f"  terrain: 4090 meshes, {waiting} waiting, 65131 built (1.219 ms each), 193.1 MiB, 21 M vertices held; "
        f"last frame 9 draws, 317 k vertices; main view {cubes} cubes seen, 0.000 ms last frame, 0.256 ms a search "
        f"over 10 searches; arena 2 pages, 128.0 MiB capacity, 53.8 MiB in use, 6.6 MiB wasted; "
        f"records in 3 pages, 192.0 MiB capacity, 164.8 MiB in use, 978 commands; "
        f"near terrain's half side 178 blocks by the cubes held\n"
    )


def capture(time: str, index: int, horizontal: int = 12) -> str:
    return (
        f"[{time}] [Client thread/INFO]: Inspection view distances: client horizontal={horizontal} vertical=12\n"
        f"[{time}] [Client thread/INFO]: Riven view stats: fps=120 avgFps=120\n"
        f"[{time}] [Client thread/INFO]: Initial terrain inspection capture: Saved screenshot as "
        f"riven-view-{index:02d}-1.png\n"
        f"[{time}] [Client thread/INFO]: Riven view captured: riven-view-{index:02d}-1795016802045.png\n"
    )


class StageViewsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def write(self, name: str, text: str) -> Path:
        path = self.root / name
        path.write_text(text, encoding="utf-8")
        return path

    def test_each_capture_takes_the_last_report_before_it(self) -> None:
        log = self.write("a.log", report("10:00:00", 9.0, 0) + report("10:00:05", 2.0, 0) + capture("10:00:07", 0)
                         + report("10:00:10", 8.0, 0) + capture("10:00:12", 1))
        reports, views = stage_views.read(log, ["first", "second"])
        self.assertEqual(3, len(reports))
        self.assertEqual(["first", "second"], [view.label for view in views])
        self.assertAlmostEqual(2.0, views[0].report.stages["opaque terrain"][2])
        self.assertAlmostEqual(8.0, views[1].report.stages["opaque terrain"][2])
        self.assertEqual((12, 12), (views[0].horizontal, views[0].vertical))

    def test_terrain_fields_are_read(self) -> None:
        log = self.write("a.log", report("10:00:00", 1.0, 7, cubes=1234) + capture("10:00:01", 0))
        terrain = stage_views.read(log)[1][0].report.terrain
        self.assertEqual(7, terrain["waiting"])
        self.assertEqual(1234, terrain["cubesSeen"])
        self.assertEqual(317, terrain["kVertices"])
        self.assertAlmostEqual(53.8, terrain["arenaInUseMiB"])
        self.assertAlmostEqual(192.0, terrain["recordCapacityMiB"])
        self.assertEqual(178, terrain["halfSideBlocks"])

    def test_a_view_with_meshes_waiting_is_not_settled(self) -> None:
        log = self.write("a.log", report("10:00:00", 1.0, 500) + capture("10:00:01", 0)
                         + report("10:00:05", 3.0, 0) + capture("10:00:06", 1))
        row = stage_views.summarize(log, "opaque terrain")
        self.assertEqual([1], row["settledViews"])
        self.assertAlmostEqual(3.0, row["settledGpu"]["median"])
        self.assertAlmostEqual(2.0, row["gpu"]["median"])
        self.assertEqual({"0": 500}, row["waitingAtCapture"])

    def test_common_views_are_those_settled_in_every_log(self) -> None:
        first = self.write("a.log", report("10:00:00", 1.0, 0) + capture("10:00:01", 0)
                           + report("10:00:05", 3.0, 0) + capture("10:00:06", 1))
        second = self.write("b.log", report("10:00:00", 2.0, 0) + capture("10:00:01", 0)
                            + report("10:00:05", 5.0, 9) + capture("10:00:06", 1))
        common = stage_views.settled(stage_views.read(first)[1], "opaque terrain") & stage_views.settled(
            stage_views.read(second)[1], "opaque terrain")
        self.assertEqual({0}, common)
        row = stage_views.summarize(second, "opaque terrain", common)
        self.assertAlmostEqual(2.0, row["commonGpu"]["median"])
        self.assertEqual(1, row["commonGpu"]["count"])

    def test_a_pattern_keeps_only_the_views_it_names(self) -> None:
        log = self.write("a.log", report("10:00:00", 1.0, 0) + capture("10:00:01", 0)
                         + report("10:00:05", 3.0, 0) + capture("10:00:06", 1))
        row = stage_views.summarize(log, "opaque terrain", None, ["place-wait-1", "place"], r"^place$")
        self.assertEqual(1, row["views"])
        self.assertAlmostEqual(3.0, row["gpu"]["median"])

    def test_summary_refuses_a_log_without_captures(self) -> None:
        log = self.write("a.log", report("10:00:00", 1.0, 0))
        self.assertEqual(2, stage_views.main(["summary", str(log)]))


if __name__ == "__main__":
    unittest.main()
