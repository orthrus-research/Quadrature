"""Keep formal results in one SQLite database that can be queried.

What is kept:

- a run: one tour or benchmark on one machine, with what it ran (jars, options, tour, window) and where (card,
  driver, operating system, host), and where its evidence lies;
- measurements of a run, one value a row: the tier benchmark's probes from ``quadrature-tier-runs.jsonl``, the
  stage times of each captured view from a client log, and the per-frame scores of a ``frames.py compare`` report;
- findings: a formal result in a sentence or two, with its status and the runs it rests on;
- machines and their specs (ours, and rented hosts by the provider's machine id), offers seen on the market with
  their prices, rentals with their phases' times and outcome, and the provider's billed charges, so that price,
  specs and what a rental delivered can be set beside each other (views ``rental_cost`` and ``run_cost``);
- every request made to the provider, with its time, and its response compressed; each offer a listing
  returned becomes an observation of that offer at that time (views ``offer_price`` and ``price_change``), from
  which prices can be modeled by card, specs, hour and host.

Offer records use the field names of the provider's listing (``machine_id``, ``gpu_name``, ``dph_total``,
``cpu_cores_effective``, ...); the whole offer is kept as JSON besides.

    python tools/results.py add-run LABEL [--client-log LOG --tour TOUR] [--tiers JSONL [--tiers-skip N]]
                                          [--frames REPORT --against LABEL] [run fields ...]
    python tools/results.py add-finding ID --title T --statement S --status established --runs A,B
    python tools/results.py runs [--like PATTERN] | findings [--status S] | show LABEL_OR_ID
    python tools/results.py add-machine PROVIDER KEY [spec fields ...]
    python tools/results.py add-offers SNAPSHOT.json | add-rental RECORD.json | add-charges CHARGES.json
    python tools/results.py costs | calls [--since TIME] | prune-responses --before TIME
    python tools/results.py query "SELECT ..."

The database is ``.local/results/results.sqlite`` in the repository unless ``--db`` or ``QUADRATURE_RESULTS``
names another. ``query`` opens it read-only and offers ``median(x)`` beside SQLite's own aggregates, and
``inflate(response)`` for a stored response's JSON text. Exit 0, 1 on a failed lookup, 2 on a usage error.
"""

from __future__ import annotations

import argparse
import datetime
import json
import os
import re
import sqlite3
import statistics
import sys
import time
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import stage_views  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DB = ROOT / ".local" / "results" / "results.sqlite"
SCHEMA_VERSION = 3
STATUSES = ("established", "hypothesis", "refuted", "superseded")

SCHEMA = """
CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS run (
    id INTEGER PRIMARY KEY,
    label TEXT NOT NULL UNIQUE,      -- e.g. cal-01/linux/2-traced-bench-r12-measure
    date TEXT NOT NULL,              -- YYYY-MM-DD the run was made
    instance TEXT,                   -- the Riven instance's name
    os TEXT,                         -- windows, linux
    host TEXT,                       -- local, or the rented machine (provider, instance, place)
    card TEXT,
    driver TEXT,
    client_jar TEXT,                 -- sha256 of the Quadrature jar
    cubature TEXT,                   -- commit or jar sha256
    riven TEXT,                      -- frozen label or commit
    options TEXT,                    -- JAVA_TOOL_OPTIONS
    tour TEXT,
    window TEXT,
    radius INTEGER,
    exit INTEGER,
    evidence TEXT,                   -- repository-relative folder holding the run's logs
    note TEXT
);
CREATE TABLE IF NOT EXISTS measurement (
    run_id INTEGER NOT NULL REFERENCES run(id) ON DELETE CASCADE,
    source TEXT NOT NULL,            -- tier-bench, stage, frames
    place TEXT,                      -- position "x, y, z" (tier-bench)
    view TEXT,                       -- tour view label (stage, frames)
    sample INTEGER,                  -- the benchmark's run number at a place
    metric TEXT NOT NULL,
    part TEXT NOT NULL DEFAULT '',   -- a resolution ("1920x1080"), a stage name, or empty
    value REAL NOT NULL,
    unit TEXT,
    against TEXT                     -- the reference a comparison was scored against
);
CREATE INDEX IF NOT EXISTS measurement_run ON measurement(run_id);
CREATE INDEX IF NOT EXISTS measurement_metric ON measurement(metric, part);
CREATE TABLE IF NOT EXISTS finding (
    id TEXT PRIMARY KEY,             -- a short slug, e.g. cloud-3060-matches-small-passes
    date TEXT NOT NULL,
    title TEXT NOT NULL,
    statement TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('established', 'hypothesis', 'refuted', 'superseded')),
    superseded_by TEXT REFERENCES finding(id),
    evidence TEXT
);
CREATE TABLE IF NOT EXISTS finding_run (
    finding_id TEXT NOT NULL REFERENCES finding(id) ON DELETE CASCADE,
    run_id INTEGER NOT NULL REFERENCES run(id) ON DELETE CASCADE,
    PRIMARY KEY (finding_id, run_id)
);
CREATE TABLE IF NOT EXISTS machine (
    id INTEGER PRIMARY KEY,
    provider TEXT NOT NULL,          -- local, or the provider rented from
    key TEXT NOT NULL,               -- the provider's machine id, or a name for one of ours
    host TEXT,                       -- the provider's host id: one host may own several machines
    gpu TEXT,
    gpu_ram_mib REAL,
    gpu_mem_bw REAL,                 -- GB/s, as the provider measures it
    pcie_bw REAL,                    -- GB/s, as the provider measures it
    cpu TEXT,
    cpu_cores_effective REAL,        -- cores the container gets
    cpu_ram_mib REAL,
    cpu_ghz REAL,
    disk_bw REAL,                    -- MB/s
    inet_up REAL,                    -- Mb/s
    inet_down REAL,                  -- Mb/s
    place TEXT,
    note TEXT,
    UNIQUE (provider, key)
);
CREATE TABLE IF NOT EXISTS offer (
    id INTEGER PRIMARY KEY,
    observed TEXT NOT NULL,          -- UTC time the listing was read
    provider TEXT NOT NULL,
    offer TEXT NOT NULL,             -- the provider's offer id
    machine_id INTEGER REFERENCES machine(id),
    call_id INTEGER REFERENCES api_call(id),  -- the request whose response listed it
    dph REAL,                        -- $/hour, all in, as listed (dph_base + storage_hourly)
    dph_base REAL,                   -- $/hour, the host's price for the card
    storage_hourly REAL,             -- $/hour for the listing's default disk
    min_bid REAL,                    -- $/hour, the floor for an interruptible rental
    discounted_dph REAL,
    credit_discount_max REAL,
    rented INTEGER,
    duration_s REAL,                 -- how long the listing lasts from the observation
    end_date REAL,                   -- epoch seconds the listing ends
    score REAL,
    storage_cost REAL,               -- $/GB/month
    inet_up_cost REAL,               -- $/GB
    inet_down_cost REAL,             -- $/GB
    driver TEXT,
    cuda REAL,
    reliability REAL,
    verified INTEGER,
    dlperf REAL,
    raw TEXT,                        -- the whole offer, JSON
    UNIQUE (provider, offer, observed)
);
CREATE TABLE IF NOT EXISTS api_call (
    id INTEGER PRIMARY KEY,
    time TEXT NOT NULL,              -- UTC, to the millisecond, when the request was sent
    provider TEXT NOT NULL,
    method TEXT NOT NULL,
    endpoint TEXT NOT NULL,
    request TEXT,                    -- the request body, JSON (never a credential)
    status INTEGER,
    duration_ms REAL,
    bytes INTEGER,                   -- the response body's size
    result_count INTEGER,            -- offers or instances listed
    purpose TEXT,                    -- market, rent, status, charges, ...
    response BLOB                    -- the response body, zlib; inflate(response) in queries
);
CREATE INDEX IF NOT EXISTS api_call_time ON api_call(time);
CREATE TABLE IF NOT EXISTS rental (
    id INTEGER PRIMARY KEY,
    provider TEXT NOT NULL,
    instance TEXT NOT NULL,          -- the provider's instance id
    offer TEXT,
    machine_id INTEGER REFERENCES machine(id),
    label TEXT,                      -- the bundle or plan it ran
    purpose TEXT,                    -- probe, calibration, measurement, ...
    created TEXT,                    -- UTC
    destroyed TEXT,                  -- UTC
    held_s REAL,
    dph_quoted REAL,                 -- the offer's price when rented
    dph_billed REAL,                 -- the instance's price once created
    disk_gb REAL,
    driver TEXT,
    outcome TEXT CHECK (outcome IN ('completed', 'partial', 'failed')),
    failure TEXT,
    to_running_s REAL,               -- created to running
    to_ssh_s REAL,                   -- running to SSH answering
    upload_s REAL,
    upload_bytes REAL,
    bootstrap_s REAL,
    work_s REAL,                     -- the plan's run
    fetch_s REAL,
    download_bytes REAL,
    UNIQUE (provider, instance)
);
CREATE TABLE IF NOT EXISTS charge (
    provider TEXT NOT NULL,
    instance TEXT NOT NULL,
    day TEXT NOT NULL,               -- YYYY-MM-DD, UTC
    kind TEXT NOT NULL,              -- gpu, disk, bwd (to the host), bwu (from the host), ...
    amount REAL NOT NULL,            -- $
    description TEXT,
    PRIMARY KEY (provider, instance, day, kind)
);
"""

# Columns added to tables a version-1 database already has.
ADDED_COLUMNS = {
    "run": (("machine_id", "INTEGER REFERENCES machine(id)"), ("rental_id", "INTEGER REFERENCES rental(id)")),
    "machine": (("host", "TEXT"),),
    "offer": (("call_id", "INTEGER REFERENCES api_call(id)"), ("dph_base", "REAL"), ("storage_hourly", "REAL"),
              ("min_bid", "REAL"), ("discounted_dph", "REAL"), ("credit_discount_max", "REAL"), ("rented", "INTEGER"),
              ("duration_s", "REAL"), ("end_date", "REAL"), ("score", "REAL")),
}

VIEWS = """
DROP VIEW IF EXISTS rental_cost;
CREATE VIEW rental_cost AS
SELECT r.id, r.provider, r.instance, r.label, r.purpose, r.created, r.outcome, r.failure,
       r.held_s / 60.0 AS held_min, r.dph_quoted, r.dph_billed,
       r.held_s / 3600.0 * r.dph_billed AS estimated,
       (SELECT sum(c.amount) FROM charge c WHERE c.provider = r.provider AND c.instance = r.instance) AS billed,
       (SELECT count(*) FROM run WHERE run.rental_id = r.id) AS runs,
       r.to_running_s, r.to_ssh_s, r.upload_s, r.upload_bytes, r.bootstrap_s, r.work_s, r.fetch_s,
       r.driver, m.gpu, m.cpu, m.cpu_cores_effective, m.cpu_ghz, m.pcie_bw, m.gpu_mem_bw, m.inet_up, m.inet_down,
       m.place, m.key AS machine
FROM rental r LEFT JOIN machine m ON m.id = r.machine_id;
DROP VIEW IF EXISTS run_cost;
CREATE VIEW run_cost AS
SELECT run.id, run.label, run.date, rc.provider, rc.instance, rc.gpu, rc.cpu_cores_effective, rc.place,
       coalesce(rc.billed, rc.estimated) / max(rc.runs, 1) AS cost,
       (SELECT count(DISTINCT x.place) FROM measurement x WHERE x.run_id = run.id AND x.source = 'tier-bench')
           AS places_benchmarked
FROM run JOIN rental_cost rc ON rc.id = run.rental_id;
DROP VIEW IF EXISTS offer_price;
CREATE VIEW offer_price AS
SELECT o.id, o.observed, CAST(strftime('%w', o.observed) AS INTEGER) AS weekday,
       CAST(strftime('%H', o.observed) AS INTEGER) AS hour, o.provider, o.offer, o.call_id,
       m.key AS machine, m.host, m.gpu, m.gpu_ram_mib, m.cpu_cores_effective, m.cpu_ghz, m.pcie_bw, m.inet_down,
       m.place, o.dph, o.dph_base, o.storage_hourly, o.min_bid, o.discounted_dph, o.rented, o.reliability, o.driver,
       o.verified, o.duration_s
FROM offer o LEFT JOIN machine m ON m.id = o.machine_id;
DROP VIEW IF EXISTS price_change;
CREATE VIEW price_change AS
WITH s AS (
    SELECT *, lag(dph_base) OVER w AS base_before, lag(min_bid) OVER w AS bid_before, lag(observed) OVER w AS since
    FROM offer_price WHERE machine IS NOT NULL
    WINDOW w AS (PARTITION BY provider, machine, offer ORDER BY observed))
SELECT provider, machine, offer, host, gpu, place, since, observed, base_before, dph_base, bid_before, min_bid,
       dph_base - base_before AS base_change, min_bid - bid_before AS bid_change
FROM s
WHERE (base_before IS NOT NULL AND abs(dph_base - base_before) > 1e-9)
   OR (bid_before IS NOT NULL AND abs(min_bid - bid_before) > 1e-9);
"""

PLATFORM = re.compile(r"Platform: OpenGL [^,]+, [^,]+, (?P<card>[^/,]+)[^,]*, .*?driver string \"[^\"]*?(?P<driver>[\d.]+)\"")
RUN_FIELDS = ("date", "instance", "os", "host", "card", "driver", "client_jar", "cubature", "riven", "options", "tour",
              "window", "radius", "exit", "evidence", "note")


def database_path(explicit: str | None) -> Path:
    return Path(explicit or os.environ.get("QUADRATURE_RESULTS") or DEFAULT_DB)


def connect(path: Path, read_only: bool = False) -> sqlite3.Connection:
    if read_only:
        if not path.exists():
            raise SystemExit(f"no database at {path}")
        connection = sqlite3.connect(f"{path.resolve().as_uri()}?mode=ro", uri=True)
    else:
        path.parent.mkdir(parents=True, exist_ok=True)
        connection = sqlite3.connect(path)
        connection.executescript(SCHEMA)
        for table, columns in ADDED_COLUMNS.items():
            have = {row[1] for row in connection.execute(f"PRAGMA table_info({table})")}
            for name, kind in columns:
                if name not in have:
                    connection.execute(f"ALTER TABLE {table} ADD COLUMN {name} {kind}")
        fill_from_raw(connection)
        connection.executescript(VIEWS)
        connection.execute("INSERT OR REPLACE INTO meta VALUES ('schema_version', ?)", (str(SCHEMA_VERSION),))
        connection.commit()
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA foreign_keys = ON")
    connection.create_aggregate("median", 1, Median)
    connection.create_function("inflate", 1, inflate, deterministic=True)
    return connection


def fill_from_raw(connection: sqlite3.Connection) -> None:
    """Offer columns added after an offer was recorded, filled from the JSON kept with it (and its machine's host)."""
    for column, field in OFFER_FIELDS.items():
        connection.execute(f"UPDATE offer SET {column} = json_extract(raw, '$.{field}') "
                           f"WHERE raw IS NOT NULL AND {column} IS NULL AND json_extract(raw, '$.{field}') IS NOT NULL")
    connection.execute("UPDATE machine SET host = (SELECT CAST(json_extract(o.raw, '$.host_id') AS TEXT) FROM offer o "
                       "WHERE o.machine_id = machine.id AND o.raw IS NOT NULL LIMIT 1) WHERE host IS NULL")


def inflate(blob):
    return None if blob is None else zlib.decompress(blob).decode("utf-8", "replace")


class Median:
    """median(x) in queries: repeated probes carry the odd clock dip, which a mean takes in."""

    def __init__(self) -> None:
        self.values: list[float] = []

    def step(self, value) -> None:
        if value is not None:
            self.values.append(float(value))

    def finalize(self):
        return statistics.median(self.values) if self.values else None


def relative(path: str | None) -> str | None:
    """A path inside the repository as a repository-relative one, so the database names no drive."""
    if not path:
        return path
    resolved = Path(path).resolve()
    try:
        return resolved.relative_to(ROOT).as_posix()
    except ValueError:
        return Path(path).as_posix()


def platform_of(log: Path) -> dict[str, str]:
    """Card and driver from Quadrature's platform line in a client log."""
    with log.open(encoding="utf-8", errors="replace") as handle:
        for line in handle:
            match = PLATFORM.search(line)
            if match:
                return {"card": match["card"].strip(), "driver": match["driver"]}
    return {}


def tier_rows(path: Path, skip: int = 0) -> list[tuple]:
    """Measurement rows from a tier benchmark record; ``skip`` leaves out lines an earlier tour wrote."""
    rows = []
    lines = [line for line in path.read_text(encoding="utf-8").splitlines() if line.strip()][skip:]
    for line in lines:
        entry = json.loads(line)
        result = entry.get("result")
        if not result or not entry.get("settled", True):
            continue
        place, sample = entry.get("at"), entry.get("run")

        def add(metric, value, unit, part=""):
            if value is not None:
                rows.append(("tier-bench", place, None, sample, metric, part, float(value), unit, None))

        for terrain in result.get("terrain", []):
            size = f"{terrain['size'][0]}x{terrain['size'][1]}"
            add("terrain_ms", terrain.get("ms"), "ms", size)
            add("terrain_fragments", terrain.get("fragments"), "count", size)
            add("terrain_primitives", terrain.get("primitives"), "count", size)
        for trace in result.get("trace", []):
            size = f"{trace['size'][0]}x{trace['size'][1]}"
            add("trace_ms", trace.get("ms"), "ms", size)
            add("trace_rays", trace.get("rays"), "count", size)
        add("light_ms", result.get("lightMs"), "ms")
        add("light_primitives", result.get("lightPrimitives"), "count")
        add("light_fragments", result.get("lightFragments"), "count")
        add("bandwidth_small", result.get("smallGBs"), "GB/s")
        add("bandwidth_large", result.get("largeGBs"), "GB/s")
        add("spin_ms", entry.get("spinMs"), "ms")
        add("frames", entry.get("frames"), "count")
        add("available_memory", entry.get("availableMiB"), "MiB")
    return rows


def stage_rows(log: Path, tour: Path | None) -> list[tuple]:
    """Per captured view: each stage's CPU and GPU median and 95th percentile, and the meshes still waiting."""
    labels = stage_views.tour_labels(tour) if tour else None
    _, views = stage_views.read(log, labels)
    rows = []
    for view in views:
        if view.report is None:
            continue
        for name, (cpu, cpu95, gpu, gpu95) in view.report.stages.items():
            for metric, value in (("cpu_ms", cpu), ("cpu95_ms", cpu95), ("gpu_ms", gpu), ("gpu95_ms", gpu95)):
                rows.append(("stage", None, view.label, None, metric, name, value, "ms", None))
        for key, value in view.report.terrain.items():
            rows.append(("stage", None, view.label, None, f"terrain_{key}", "", float(value), None, None))
    return rows


def frame_rows(report: Path, against: str | None) -> list[tuple]:
    """Per frame of a frames.py compare report: passed (1 or 0) and every numeric measure."""
    data = json.loads(report.read_text(encoding="utf-8"))
    reference = against or data.get("reference")
    rows = []
    for frame in data.get("frames", []):
        view = Path(frame["frame"]).stem
        rows.append(("frames", None, view, None, "passed", "", 1.0 if frame.get("passed") else 0.0, None, reference))
        for key, value in frame.items():
            if key not in ("frame", "passed") and isinstance(value, (int, float)) and not isinstance(value, bool):
                rows.append(("frames", None, view, None, key, "", float(value), None, reference))
    return rows


MACHINE_FIELDS = {  # machine column: the offer field it comes from
    "gpu": "gpu_name", "gpu_ram_mib": "gpu_ram", "gpu_mem_bw": "gpu_mem_bw", "pcie_bw": "pcie_bw", "cpu": "cpu_name",
    "cpu_cores_effective": "cpu_cores_effective", "cpu_ram_mib": "cpu_ram", "cpu_ghz": "cpu_ghz", "disk_bw": "disk_bw",
    "inet_up": "inet_up", "inet_down": "inet_down", "place": "geolocation", "host": "host_id",
}
OFFER_FIELDS = {  # offer column: the offer field it comes from
    "dph": "dph_total", "storage_cost": "storage_cost", "inet_up_cost": "inet_up_cost",
    "inet_down_cost": "inet_down_cost", "driver": "driver_version", "cuda": "cuda_max_good",
    "reliability": "reliability", "verified": "verified", "dlperf": "dlperf", "dph_base": "dph_base",
    "storage_hourly": "storage_total_cost", "min_bid": "min_bid", "discounted_dph": "discounted_dph_total",
    "credit_discount_max": "credit_discount_max", "rented": "rented", "duration_s": "duration", "end_date": "end_date",
    "score": "score",
}
RENTAL_FIELDS = ("offer", "label", "purpose", "created", "destroyed", "held_s", "dph_quoted", "dph_billed", "disk_gb",
                 "driver", "outcome", "failure", "to_running_s", "to_ssh_s", "upload_s", "upload_bytes", "bootstrap_s",
                 "work_s", "fetch_s", "download_bytes")


def clean(value):
    return value.strip() if isinstance(value, str) else value


def upsert_machine(connection: sqlite3.Connection, provider: str, key: str, specs: dict) -> int:
    """The machine's id; specs given replace those recorded (a host's container may change between rentals)."""
    specs = {name: clean(value) for name, value in specs.items() if name in MACHINE_FIELDS or name == "note"}
    specs = {name: value for name, value in specs.items() if value is not None}
    row = connection.execute("SELECT id FROM machine WHERE provider = ? AND key = ?", (provider, key)).fetchone()
    if row is None:
        names = ["provider", "key", *specs]
        return connection.execute(f"INSERT INTO machine ({', '.join(names)}) VALUES ({', '.join('?' * len(names))})",
                                  (provider, key, *specs.values())).lastrowid
    if specs:
        connection.execute(f"UPDATE machine SET {', '.join(f'{name} = ?' for name in specs)} WHERE id = ?",
                           (*specs.values(), row["id"]))
    return row["id"]


def machine_of_offer(connection: sqlite3.Connection, provider: str, offer: dict) -> int | None:
    if offer.get("machine_id") is None:
        return None
    specs = {column: offer.get(field) for column, field in MACHINE_FIELDS.items()}
    return upsert_machine(connection, provider, str(offer["machine_id"]), specs)


def record_offer(connection: sqlite3.Connection, provider: str, observed: str, offer: dict,
                 call_id: int | None = None) -> None:
    """One observation of an offer. Observations from a stored response keep no copy of the offer: its JSON is
    in the response (``json_each(inflate(response), '$.offers')``)."""
    machine = machine_of_offer(connection, provider, offer)
    values = {column: clean(offer.get(field)) for column, field in OFFER_FIELDS.items()}
    for flag in ("verified", "rented"):
        if values[flag] is not None:
            values[flag] = int(bool(values[flag]))
    connection.execute(f"INSERT OR REPLACE INTO offer (observed, provider, offer, machine_id, call_id, "
                       f"{', '.join(values)}, raw) VALUES (?, ?, ?, ?, ?, {', '.join('?' * len(values))}, ?)",
                       (observed, provider, str(offer["id"]), machine, call_id, *values.values(),
                        None if call_id else json.dumps(offer)))


def now_utc() -> str:
    moment = time.time()
    return time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime(moment)) + f".{int(moment % 1 * 1000):03d}Z"


def record_call(connection: sqlite3.Connection, provider: str, method: str, endpoint: str, request, status: int | None,
                duration_ms: float, body: bytes, purpose: str | None = None, sent: str | None = None) -> int:
    """A request and its response; offers it listed become observations at the time it was sent."""
    sent = sent or now_utc()
    try:
        data = json.loads(body or b"null")
    except ValueError:
        data = None
    listed = data.get("offers") if isinstance(data, dict) else None
    instances = data.get("instances") if isinstance(data, dict) else None
    count = len(listed) if isinstance(listed, list) else len(instances) if isinstance(instances, list) else None
    request_text = request if isinstance(request, str) or request is None else json.dumps(request)
    with connection:
        call_id = connection.execute(
            "INSERT INTO api_call (time, provider, method, endpoint, request, status, duration_ms, bytes, result_count,"
            " purpose, response) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (sent, provider, method, endpoint, request_text, status, round(duration_ms, 1), len(body or b""), count,
             purpose, zlib.compress(body or b"", 9))).lastrowid
        if isinstance(listed, list):
            seen = set()
            for offer in listed:
                if isinstance(offer, dict) and "id" in offer and offer["id"] not in seen:
                    seen.add(offer["id"])
                    record_offer(connection, provider, sent, offer, call_id)
    return call_id


def add_offers(connection: sqlite3.Connection, path: Path) -> int:
    """A market snapshot: {"provider", "observed", "offers": [the provider's offers as listed]}."""
    data = json.loads(path.read_text(encoding="utf-8"))
    with connection:
        for offer in data["offers"]:
            record_offer(connection, data["provider"], data["observed"], offer)
    print(f"{len(data['offers'])} offers observed {data['observed']}")
    return 0


def add_rental(connection: sqlite3.Connection, path: Path) -> int:
    """A rental record: {"provider", "instance", "observed", "offer": {the offer as listed}, and RENTAL_FIELDS}.
    Recording a rental again replaces it; runs keep pointing at it."""
    data = json.loads(path.read_text(encoding="utf-8"))
    provider, instance = data["provider"], str(data["instance"])
    offer = data.get("offer")
    with connection:
        machine = None
        fields = {name: data.get(name) for name in RENTAL_FIELDS}
        if isinstance(offer, dict):
            record_offer(connection, provider, data.get("observed") or data.get("created"), offer)
            machine = machine_of_offer(connection, provider, offer)
            fields["offer"] = str(offer["id"])
            fields["dph_quoted"] = fields["dph_quoted"] or offer.get("dph_total")
            fields["driver"] = fields["driver"] or offer.get("driver_version")
        row = connection.execute("SELECT id FROM rental WHERE provider = ? AND instance = ?",
                                 (provider, instance)).fetchone()
        if row is None:
            names = ["provider", "instance", "machine_id", *fields]
            connection.execute(f"INSERT INTO rental ({', '.join(names)}) VALUES ({', '.join('?' * len(names))})",
                               (provider, instance, machine, *fields.values()))
        else:
            connection.execute(f"UPDATE rental SET machine_id = ?, {', '.join(f'{name} = ?' for name in fields)} "
                               "WHERE id = ?", (machine, *fields.values(), row["id"]))
    print(f"rental {provider} {instance}: {fields.get('outcome')}, {(fields.get('held_s') or 0) / 60:.1f} min")
    return 0


def add_charges(connection: sqlite3.Connection, path: Path) -> int:
    """Billed charges: {"provider", "results": [the provider's per-instance charges, each with "source"
    "instance-<id>", "start" (epoch seconds) and "items" of a "type" and an "amount"]}."""
    data = json.loads(path.read_text(encoding="utf-8"))
    count = 0
    with connection:
        for entry in data["results"]:
            source = str(entry.get("source") or "")
            if not source.startswith("instance-"):
                continue
            instance = source.removeprefix("instance-")
            day = datetime.datetime.fromtimestamp(entry["start"], datetime.timezone.utc).date().isoformat()
            for item in entry.get("items") or [{"type": entry.get("type"), "amount": entry["amount"]}]:
                connection.execute("INSERT OR REPLACE INTO charge VALUES (?, ?, ?, ?, ?, ?)",
                                   (data["provider"], instance, day, item["type"], float(item["amount"]),
                                    item.get("description")))
                count += 1
    print(f"{count} charge items")
    return 0


def find_id(connection: sqlite3.Connection, table: str, reference: str | None) -> int | None:
    """A machine or rental named PROVIDER:KEY (a rental by its instance id)."""
    if not reference:
        return None
    provider, _, key = reference.partition(":")
    column = "key" if table == "machine" else "instance"
    row = connection.execute(f"SELECT id FROM {table} WHERE provider = ? AND {column} = ?", (provider, key)).fetchone()
    if row is None:
        raise SystemExit(f"no {table} {reference}")
    return row["id"]


def add_run(connection: sqlite3.Connection, args: argparse.Namespace) -> int:
    fields = {name: getattr(args, name) for name in RUN_FIELDS}
    fields["date"] = fields["date"] or datetime.date.today().isoformat()
    fields["evidence"] = relative(fields["evidence"])
    fields["tour"] = relative(fields["tour"])
    if args.client_log:
        for key, value in platform_of(Path(args.client_log)).items():
            fields[key] = fields[key] or value
    rows: list[tuple] = []
    if args.client_log:
        rows += stage_rows(Path(args.client_log), Path(args.tour) if args.tour else None)
    if args.tiers:
        rows += tier_rows(Path(args.tiers), args.tiers_skip)
    if args.frames:
        rows += frame_rows(Path(args.frames), args.against)
    with connection:
        existing = connection.execute("SELECT id FROM run WHERE label = ?", (args.label,)).fetchone()
        if existing and not args.replace:
            raise SystemExit(f"run {args.label} is recorded already; --replace records it again")
        if existing:
            connection.execute("DELETE FROM run WHERE id = ?", (existing["id"],))
        names = ", ".join(("label",) + RUN_FIELDS)
        marks = ", ".join("?" * (len(RUN_FIELDS) + 1))
        run_id = connection.execute(f"INSERT INTO run ({names}) VALUES ({marks})",
                                    (args.label, *(fields[name] for name in RUN_FIELDS))).lastrowid
        connection.execute("UPDATE run SET machine_id = ?, rental_id = ? WHERE id = ?",
                           (find_id(connection, "machine", args.machine), find_id(connection, "rental", args.rental),
                            run_id))
        connection.executemany("INSERT INTO measurement (run_id, source, place, view, sample, metric, part, value, unit,"
                               " against) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", [(run_id, *row) for row in rows])
    print(f"run {args.label}: {len(rows)} measurements")
    return 0


def add_finding(connection: sqlite3.Connection, args: argparse.Namespace) -> int:
    labels = [label for label in (args.runs or "").split(",") if label]
    with connection:
        ids = []
        for label in labels:
            row = connection.execute("SELECT id FROM run WHERE label = ?", (label,)).fetchone()
            if not row:
                raise SystemExit(f"no run {label}")
            ids.append(row["id"])
        if connection.execute("SELECT 1 FROM finding WHERE id = ?", (args.id,)).fetchone() and not args.replace:
            raise SystemExit(f"finding {args.id} is recorded already; --replace records it again")
        connection.execute("DELETE FROM finding WHERE id = ?", (args.id,))
        connection.execute("INSERT INTO finding VALUES (?, ?, ?, ?, ?, ?, ?)",
                           (args.id, args.date or datetime.date.today().isoformat(), args.title, args.statement,
                            args.status, None, relative(args.evidence)))
        connection.executemany("INSERT INTO finding_run VALUES (?, ?)", [(args.id, run_id) for run_id in ids])
        if args.supersedes:
            changed = connection.execute("UPDATE finding SET status = 'superseded', superseded_by = ? WHERE id = ?",
                                         (args.id, args.supersedes)).rowcount
            if not changed:
                raise SystemExit(f"no finding {args.supersedes} to supersede")
    print(f"finding {args.id}: {args.status}, {len(ids)} runs")
    return 0


def link_run(connection: sqlite3.Connection, args: argparse.Namespace) -> int:
    with connection:
        changed = connection.execute(
            "UPDATE run SET machine_id = coalesce(?, machine_id), rental_id = coalesce(?, rental_id) WHERE label = ?",
            (find_id(connection, "machine", args.machine), find_id(connection, "rental", args.rental), args.label),
        ).rowcount
    if not changed:
        print(f"no run {args.label}")
        return 1
    print(f"run {args.label} linked")
    return 0


def add_machine(connection: sqlite3.Connection, args: argparse.Namespace) -> int:
    specs = {name: getattr(args, name) for name in (*MACHINE_FIELDS, "note")}
    with connection:
        upsert_machine(connection, args.provider, args.key, specs)
    print(f"machine {args.provider}:{args.key}")
    return 0


def print_rows(rows: list[sqlite3.Row]) -> None:
    if not rows:
        print("(no rows)")
        return
    keys = rows[0].keys()
    text = [[("" if row[key] is None else str(row[key])) for key in keys] for row in rows]
    widths = [min(60, max(len(key), *(len(line[i]) for line in text))) for i, key in enumerate(keys)]
    print("  ".join(key.ljust(width) for key, width in zip(keys, widths)))
    for line in text:
        print("  ".join(cell[:width].ljust(width) for cell, width in zip(line, widths)))


def show(connection: sqlite3.Connection, name: str) -> int:
    run = connection.execute("SELECT * FROM run WHERE label = ?", (name,)).fetchone()
    if run:
        for key in run.keys():
            if run[key] is not None:
                print(f"{key:11} {run[key]}")
        print_rows(connection.execute("SELECT source, metric, part, count(*) AS n, round(avg(value), 4) AS mean "
                                      "FROM measurement WHERE run_id = ? GROUP BY source, metric, part "
                                      "ORDER BY source, metric, part", (run["id"],)).fetchall())
        return 0
    finding = connection.execute("SELECT * FROM finding WHERE id = ?", (name,)).fetchone()
    if finding:
        for key in finding.keys():
            if finding[key] is not None:
                print(f"{key:13} {finding[key]}")
        print_rows(connection.execute("SELECT r.label, r.date, r.os, r.card, r.driver FROM finding_run f "
                                      "JOIN run r ON r.id = f.run_id WHERE f.finding_id = ?", (name,)).fetchall())
        return 0
    print(f"no run or finding {name}")
    return 1


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--db", help="the database file (default .local/results/results.sqlite)")
    commands = parser.add_subparsers(dest="command", required=True)

    r = commands.add_parser("add-run", help="record a run and its measurements")
    r.add_argument("label")
    for name in RUN_FIELDS:
        r.add_argument("--" + name.replace("_", "-"), dest=name, type=int if name in ("radius", "exit") else str)
    r.add_argument("--client-log", help="a client log: card and driver, and each captured view's stage times")
    r.add_argument("--tiers", help="a quadrature-tier-runs.jsonl")
    r.add_argument("--tiers-skip", type=int, default=0, help="leading lines of --tiers written by earlier tours")
    r.add_argument("--frames", help="a frames.py compare report.json")
    r.add_argument("--against", help="the run the frames were compared with (default the report's reference)")
    r.add_argument("--machine", help="the machine it ran on, PROVIDER:KEY")
    r.add_argument("--rental", help="the rental it ran in, PROVIDER:INSTANCE")
    r.add_argument("--replace", action="store_true")

    k = commands.add_parser("link-run", help="set a recorded run's machine or rental")
    k.add_argument("label")
    k.add_argument("--machine")
    k.add_argument("--rental")

    a = commands.add_parser("add-machine", help="record a machine's specs (ours, or a host by hand)")
    a.add_argument("provider")
    a.add_argument("key")
    for name in (*MACHINE_FIELDS, "note"):
        a.add_argument("--" + name.replace("_", "-"), dest=name,
                       type=str if name in ("gpu", "cpu", "place", "note") else float)
    for name, help_text in (("add-offers", "record a market snapshot"), ("add-rental", "record a rental"),
                            ("add-charges", "record the provider's billed charges")):
        commands.add_parser(name, help=help_text).add_argument("file", type=Path)
    commands.add_parser("costs", help="each rental's specs, time, price and billed cost")
    c = commands.add_parser("calls", help="requests made to providers, by hour, with what they returned")
    c.add_argument("--since", help="a UTC time, e.g. 2026-11-20 or 2026-11-20T18:00")
    d = commands.add_parser("prune-responses", help="drop stored response bodies, keeping the observations")
    d.add_argument("--before", required=True, help="a UTC time; responses of requests sent before it are dropped")

    f = commands.add_parser("add-finding", help="record a formal result")
    f.add_argument("id")
    f.add_argument("--title", required=True)
    f.add_argument("--statement", required=True)
    f.add_argument("--status", required=True, choices=STATUSES)
    f.add_argument("--runs", help="comma-separated run labels the finding rests on")
    f.add_argument("--evidence")
    f.add_argument("--date")
    f.add_argument("--supersedes", help="a finding this one replaces")
    f.add_argument("--replace", action="store_true")

    l = commands.add_parser("runs", help="list runs")
    l.add_argument("--like", help="an SQL LIKE pattern on the label")
    s = commands.add_parser("findings", help="list findings")
    s.add_argument("--status", choices=STATUSES)
    w = commands.add_parser("show", help="a run's fields and measurements, or a finding and its runs")
    w.add_argument("name")
    q = commands.add_parser("query", help="run a read-only SQL statement")
    q.add_argument("sql")

    args = parser.parse_args(argv)
    connection = connect(database_path(args.db), read_only=args.command == "query")
    try:
        return dispatch(connection, args)
    finally:
        connection.close()


def dispatch(connection: sqlite3.Connection, args: argparse.Namespace) -> int:
    if args.command == "query":
        try:
            print_rows(connection.execute(args.sql).fetchall())
        except sqlite3.Error as error:
            print(f"query failed: {error}")
            return 2
        return 0
    if args.command == "add-run":
        return add_run(connection, args)
    if args.command == "add-finding":
        return add_finding(connection, args)
    if args.command == "link-run":
        return link_run(connection, args)
    if args.command == "add-machine":
        return add_machine(connection, args)
    if args.command == "add-offers":
        return add_offers(connection, args.file)
    if args.command == "add-rental":
        return add_rental(connection, args.file)
    if args.command == "add-charges":
        return add_charges(connection, args.file)
    if args.command == "calls":
        print_rows(connection.execute(
            "SELECT substr(time, 1, 13) || ':00' AS hour, provider, purpose, endpoint, count(*) AS calls, "
            "sum(status >= 400) AS errors, round(median(duration_ms)) AS median_ms, sum(result_count) AS listed, "
            "round(sum(length(response)) / 1048576.0, 2) AS stored_mib FROM api_call WHERE time >= ? "
            "GROUP BY 1, 2, 3, 4 ORDER BY 1, 2, 3, 4", (args.since or "",)).fetchall())
        return 0
    if args.command == "prune-responses":
        with connection:
            dropped = connection.execute("UPDATE api_call SET response = NULL WHERE time < ? AND response IS NOT NULL",
                                         (args.before,)).rowcount
        connection.execute("VACUUM")
        print(f"{dropped} responses dropped")
        return 0
    if args.command == "costs":
        print_rows(connection.execute(
            "SELECT instance, label, purpose, outcome, round(held_min, 1) AS min, dph_billed AS dph, "
            "round(estimated, 4) AS estimated, billed, runs, gpu, cpu_cores_effective AS cores, place "
            "FROM rental_cost ORDER BY created").fetchall())
        return 0
    if args.command == "runs":
        print_rows(connection.execute("SELECT label, date, os, card, driver, host, exit FROM run "
                                      "WHERE label LIKE ? ORDER BY date, label", (args.like or "%",)).fetchall())
        return 0
    if args.command == "findings":
        sql = "SELECT id, date, status, title FROM finding"
        rows = (connection.execute(sql + " WHERE status = ? ORDER BY date, id", (args.status,)) if args.status
                else connection.execute(sql + " ORDER BY date, id"))
        print_rows(rows.fetchall())
        return 0
    return show(connection, args.name)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
