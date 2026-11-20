import io
import json
import sqlite3
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import results  # noqa: E402

PLATFORM_LINE = ('[18:51:05] [Client thread/INFO]: Platform: OpenGL 4.6 compatibility profile, NVIDIA Corporation, '
                 'NVIDIA GeForce RTX 3060/PCIe/SSE2, 12288 MiB GPU memory, driver string "4.6.0 NVIDIA 610.57.04", '
                 '399 extensions\n')


def entry(run: int, at: str = "-8705.5, 81.0, 11756.5", ms: float = 0.68, settled: bool = True) -> str:
    return json.dumps({
        "run": run, "key": "60e1afa4", "at": at, "frames": 83, "settled": settled, "spinMs": 2.02,
        "result": {
            "terrain": [{"size": [1280, 720], "ms": ms, "fragments": 1297051.0, "primitives": 2590072},
                        {"size": [3840, 2160], "ms": 1.16, "fragments": 11649807.0, "primitives": 2590072}],
            "lightMs": 2.97, "lightPrimitives": 9852310, "lightFragments": 42574868.0,
            "trace": [{"size": [1280, 720], "ms": 0.229, "rays": 65536}],
            "smallGBs": 341.0, "largeGBs": 350.0,
        },
        "availableMiB": 8664.6,
    })


class ResultsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.db = str(self.root / "results.sqlite")

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def run_tool(self, *argv: str) -> tuple[int, str]:
        out = io.StringIO()
        with redirect_stdout(out):
            try:
                code = results.main(["--db", self.db, *argv])
            except SystemExit as stop:
                code = stop.code if isinstance(stop.code, int) else 1
                out.write(str(stop.code))
        return code, out.getvalue()

    def test_platform_line_gives_card_and_driver(self) -> None:
        log = self.root / "latest.log"
        log.write_text("[18:50:08] [Client thread/INFO]: LWJGL Version: 3.4.3\n" + PLATFORM_LINE, encoding="utf-8")
        self.assertEqual(results.platform_of(log), {"card": "NVIDIA GeForce RTX 3060", "driver": "610.57.04"})

    def test_tier_rows_skip_earlier_lines_and_unsettled_entries(self) -> None:
        record = self.root / "tier-runs.jsonl"
        record.write_text("\n".join([entry(1, ms=9.9), entry(1), entry(2, settled=False)]) + "\n", encoding="utf-8")
        rows = results.tier_rows(record, skip=1)
        terrain = [row for row in rows if row[4] == "terrain_ms"]
        self.assertEqual([(row[5], row[6]) for row in terrain], [("1280x720", 0.68), ("3840x2160", 1.16)])
        self.assertIn(("light_ms", "", 2.97), [(row[4], row[5], row[6]) for row in rows])
        self.assertTrue(all(row[1] == "-8705.5, 81.0, 11756.5" and row[3] == 1 for row in rows))

    def test_run_and_finding_round_trip(self) -> None:
        record = self.root / "tier-runs.jsonl"
        record.write_text(entry(1) + "\n" + entry(2, ms=0.70) + "\n", encoding="utf-8")
        log = self.root / "latest.log"
        log.write_text(PLATFORM_LINE, encoding="utf-8")
        code, out = self.run_tool("add-run", "cal/linux", "--date", "2026-11-20", "--os", "linux",
                                  "--client-log", str(log), "--tiers", str(record))
        self.assertEqual(code, 0, out)
        code, out = self.run_tool("add-run", "cal/linux", "--tiers", str(record))
        self.assertNotEqual(code, 0)
        self.assertIn("recorded already", out)
        code, out = self.run_tool("add-finding", "same-card", "--title", "t", "--statement", "s",
                                  "--status", "hypothesis", "--runs", "cal/linux")
        self.assertEqual(code, 0, out)
        code, out = self.run_tool("add-finding", "same-card-2", "--title", "t", "--statement", "s",
                                  "--status", "established", "--runs", "cal/linux", "--supersedes", "same-card")
        self.assertEqual(code, 0, out)
        code, out = self.run_tool("query", "SELECT r.card, r.driver, f.status, f.superseded_by FROM finding f "
                                  "JOIN finding_run fr ON fr.finding_id = f.id JOIN run r ON r.id = fr.run_id "
                                  "WHERE f.id = 'same-card'")
        self.assertEqual(code, 0, out)
        self.assertIn("NVIDIA GeForce RTX 3060", out)
        self.assertIn("superseded", out)
        self.assertIn("same-card-2", out)
        code, out = self.run_tool("query", "SELECT part, avg(value) AS ms FROM measurement "
                                  "WHERE metric = 'terrain_ms' AND part = '1280x720' GROUP BY part")
        self.assertIn("0.69", out)
        code, out = self.run_tool("query", "SELECT median(value) AS m FROM measurement WHERE metric = 'terrain_ms' "
                                  "AND part = '1280x720'")
        self.assertIn("0.69", out)

    def test_query_cannot_write(self) -> None:
        self.run_tool("runs")
        code, out = self.run_tool("query", "DELETE FROM run")
        self.assertEqual(code, 2)
        self.assertIn("readonly", out.replace("-", "").replace(" ", "").lower())

    def test_finding_needs_known_runs(self) -> None:
        code, out = self.run_tool("add-finding", "x", "--title", "t", "--statement", "s", "--status", "established",
                                  "--runs", "nowhere")
        self.assertNotEqual(code, 0)
        self.assertIn("no run nowhere", out)
        connection = sqlite3.connect(self.db)
        self.assertEqual(connection.execute("SELECT count(*) FROM finding").fetchone()[0], 0)
        connection.close()


OFFER = {"id": 36416694, "machine_id": 4242, "gpu_name": "RTX 3060", "gpu_ram": 12288, "cpu_name": "Core i5-7600 ",
         "cpu_cores_effective": 2.0, "pcie_bw": 6.1, "geolocation": "Japan, JP", "dph_total": 0.080,
         "driver_version": "610.57.04", "verified": True, "inet_down_cost": 0.004}


class CostTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.db = str(self.root / "results.sqlite")

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def tool(self, *argv: str) -> str:
        out = io.StringIO()
        with redirect_stdout(out):
            code = results.main(["--db", self.db, *argv])
        self.assertEqual(code, 0, out.getvalue())
        return out.getvalue()

    def write(self, name: str, data: dict) -> str:
        path = self.root / name
        path.write_text(json.dumps(data), encoding="utf-8")
        return str(path)

    def test_rental_charges_and_run_cost(self) -> None:
        self.tool("add-offers", self.write("market.json", {"provider": "vast", "observed": "2026-11-20T18:00:00Z",
                                                           "offers": [OFFER, {**OFFER, "id": 1, "machine_id": 7}]}))
        self.tool("add-rental", self.write("rental.json", {
            "provider": "vast", "instance": "61685240", "offer": OFFER, "observed": "2026-11-20T18:45:35Z",
            "created": "2026-11-20T18:45:35Z", "held_s": 1391, "dph_billed": 0.082, "outcome": "completed",
            "work_s": 1250}))
        self.tool("add-charges", self.write("charges.json", {"provider": "vast", "results": [
            {"source": "instance-61685240", "start": 1795132800, "amount": 0.035,
             "items": [{"type": "gpu", "amount": 0.031}, {"type": "disk", "amount": 0.001},
                       {"type": "bwd", "amount": 0.003}]}]}))
        record = self.root / "tier-runs.jsonl"
        record.write_text(entry(1) + "\n" + entry(2, at="1, 2, 3") + "\n", encoding="utf-8")
        self.tool("add-run", "cal/linux/a", "--tiers", str(record), "--rental", "vast:61685240")
        self.tool("add-run", "cal/linux/b", "--rental", "vast:61685240")
        out = self.tool("query", "SELECT round(billed, 3) AS billed, round(estimated, 4) AS estimated, runs, gpu, "
                        "cpu_cores_effective AS cores, machine FROM rental_cost")
        self.assertIn("0.035", out)
        self.assertIn("0.0317", out)
        self.assertIn("4242", out)
        out = self.tool("query", "SELECT label, round(cost, 4) AS cost, places_benchmarked FROM run_cost ORDER BY label")
        self.assertEqual(out.splitlines()[1].split(), ["cal/linux/a", "0.0175", "2"])
        out = self.tool("query", "SELECT count(*) AS offers, count(DISTINCT machine_id) AS machines, "
                        "max(cpu) AS cpu FROM offer JOIN machine ON machine.id = offer.machine_id")
        self.assertEqual(out.splitlines()[1].split()[:2], ["3", "2"])  # the rental observed the offer again
        self.assertIn("Core i5-7600", out)

    def test_charges_recorded_twice_are_kept_once(self) -> None:
        charges = self.write("charges.json", {"provider": "vast", "results": [
            {"source": "instance-1", "start": 1795132800, "amount": 0.01, "items": [{"type": "gpu", "amount": 0.01}]},
            {"source": "user-credit", "start": 1795132800, "amount": 5.0}]})
        self.tool("add-charges", charges)
        self.tool("add-charges", charges)
        self.assertIn("0.01", self.tool("query", "SELECT sum(amount) AS total FROM charge"))

    def test_requests_become_timed_observations(self) -> None:
        connection = results.connect(Path(self.db))
        try:
            first = json.dumps({"offers": [{**OFFER, "dph_base": 0.078, "min_bid": 0.06},
                                           {**OFFER, "id": 2, "dph_base": 0.078, "min_bid": 0.07}]}).encode()
            later = json.dumps({"offers": [{**OFFER, "dph_base": 0.090, "min_bid": 0.06},
                                           {**OFFER, "id": 2, "dph_base": 0.078, "min_bid": 0.07}]}).encode()
            results.record_call(connection, "vast", "POST", "/bundles/", {"gpu_name": {"eq": "RTX 3060"}}, 200, 250.0,
                                first, "market", sent="2026-11-20T19:00:00.000Z")
            results.record_call(connection, "vast", "POST", "/bundles/", {"gpu_name": {"eq": "RTX 3060"}}, 200, 240.0,
                                later, "market", sent="2026-11-20T20:00:00.000Z")
            results.record_call(connection, "vast", "PUT", "/asks/1/", {"image": "x"}, 410, 90.0, b'{"error": "gone"}',
                                "rent", sent="2026-11-20T20:00:01.000Z")
        finally:
            connection.close()
        # both offers are on one machine; only the first changed, and sibling offers are not compared
        out = self.tool("query", "SELECT offer, base_before, dph_base FROM price_change")
        self.assertEqual([line.split()[:3] for line in out.splitlines()[1:]], [["36416694", "0.078", "0.09"]])
        out = self.tool("query", "SELECT count(*) AS n, sum(status >= 400) AS errors, "
                        "max(json_extract(inflate(response), '$.offers[0].dph_base')) AS base FROM api_call")
        self.assertEqual(out.splitlines()[1].split(), ["3", "1", "0.09"])
        self.tool("prune-responses", "--before", "2026-11-20T19:30")
        out = self.tool("query", "SELECT count(*) AS observations, (SELECT count(response) FROM api_call) AS bodies "
                        "FROM offer")
        self.assertEqual(out.splitlines()[1].split(), ["4", "2"])

    def test_version_one_database_gains_the_cost_columns(self) -> None:
        connection = sqlite3.connect(self.db)
        connection.executescript("CREATE TABLE run (id INTEGER PRIMARY KEY, label TEXT NOT NULL UNIQUE, "
                                 "date TEXT NOT NULL, note TEXT); INSERT INTO run VALUES (1, 'old', '2026-11-19', NULL);")
        connection.close()
        self.tool("add-machine", "local", "desktop", "--gpu", "RTX 3060", "--cpu-cores-effective", "32")
        self.tool("link-run", "old", "--machine", "local:desktop")
        self.assertEqual(self.tool("query", "SELECT label, machine_id FROM run").splitlines()[1].split(), ["old", "1"])


if __name__ == "__main__":
    unittest.main()
