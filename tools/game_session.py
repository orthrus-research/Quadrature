"""A game started from a Workbench instance's receipt (instance.json, schema wb.lab.instance/1), for a diagnostics run.

Workbench builds the instance from a launch file: `wb play riven` builds Riven's at the set revision it resolves at,
under the lab home's instances folder, with the receipt and the launch file (launch.json) beside it. Every file of the
receipt is named under a root: {instance} (the instance folder), {cache} (the runtime cache: libraries, the client
JAR and assets) and {jdk:N} (the launch's JDK build). This module copies the instance fresh into a run's folder and
starts its server and client from the receipt's command lines, with the roots and {port} filled in.

Nothing here takes the GPU lease: the caller runs under a Workbench turn that holds it (`wb gpu run -- ...`, or a
driver session), which gives WB_TURN_TOKEN and WB_LEASE_FILE.

Where a root comes from:

- {cache}: the folder given, else WB_RUNTIME_CACHE (the name a machine's [lab] env gives the session's cache), else
  the runtime cache under WB_LAB_HOME or WB_STATE_DIR that holds the receipt's asset index;
- {jdk:N}: the session's JDK in a driver session (WB_SESSION's session.json), else JAVA_HOME; refused unless its
  release file names the receipt's build.
"""

from __future__ import annotations

import json
import os
import re
import shutil
import socket
import subprocess
import time
from pathlib import Path

SCHEMA = "wb.lab.instance/1"
RECEIPT = "instance.json"
LAUNCH = "launch.json"
SIDES = ("server", "client")
# What earlier games left in an instance; never part of a fresh copy.
LEFT_BEHIND = ("logs", "crash-reports", "screenshots", "java-tmp", "jfr-repository", "motion-evidence")
CACHE_ENV = "WB_RUNTIME_CACHE"
CACHE_CANDIDATES = (("WB_LAB_HOME", "out/cache/runtime"), ("WB_STATE_DIR", "cache/runtime"))
READY = "Done ("
# How a tour is driven when the launch's tour rules leave a key out (Workbench's own defaults for lab.run).
TOUR_DEFAULTS = {"setup": [], "spawn": "setworldspawn {x} {y} {z}", "joined": "joined the game", "spectator": True,
                 "client_jvm": [], "captured": "", "capture_timeout": 900, "leave": ["kick {player} Tour finished"],
                 "leave_seconds": 12}
ROOT = re.compile(r"\{(instance|cache|jdk:\d+)\}")


class GameError(RuntimeError):
    pass


def read_json(path: Path) -> dict:
    return json.loads(Path(path).read_text(encoding="utf-8"))


def read_receipt(path: Path) -> dict:
    path = Path(path)
    if not path.is_file():
        raise GameError(f"no instance receipt at {path} (wb play riven builds an instance and its instance.json)")
    receipt = read_json(path)
    if receipt.get("schema") != SCHEMA:
        raise GameError(f"{path} is not an instance receipt ({SCHEMA})")
    return receipt


def level_name(properties: Path) -> str:
    try:
        text = properties.read_text(encoding="utf-8")
    except OSError:
        return "world"
    found = re.search(r"^level-name=(.*)$", text, re.M)
    return found.group(1).strip() if found and found.group(1).strip() else "world"


def copy_instance(source: Path, target: Path) -> Path:
    """A fresh copy of the instance ``source`` in ``target``: mod and library JARs hard-linked where one volume allows,
    everything else copied, without the server's world and what earlier games left behind."""
    source, target = Path(source), Path(target)
    if target.exists():
        raise GameError(f"the copy's folder exists already: {target}")
    skip = {("server", level_name(source / "server" / "server.properties"))}
    skip.update((side, name) for side in SIDES for name in LEFT_BEHIND)
    for directory, folders, files in os.walk(source):
        here = Path(directory)
        relative = here.relative_to(source)
        folders[:] = [f for f in folders if tuple((relative / f).parts) not in skip]
        (target / relative).mkdir(parents=True, exist_ok=True)
        for name in files:
            path, made = here / name, target / relative / name
            if name.endswith(".jar"):
                try:
                    os.link(path, made)
                    continue
                except OSError:
                    pass  # another volume: a copy
            shutil.copy2(path, made)
    return target


def runtime_cache(receipt: dict, given: str | None = None, environ=os.environ) -> Path:
    """The runtime cache the receipt's {cache} paths are under (the module docstring says where it comes from)."""
    index = str(receipt["runtime"]["asset_index"]["path"])
    if not index.startswith("{cache}/"):
        raise GameError(f"the receipt's asset index {index} is not under {{cache}}")
    relative = index[len("{cache}/"):]
    named = given or environ.get(CACHE_ENV)
    if named:
        if not (Path(named) / relative).is_file():
            raise GameError(f"the runtime cache {named} does not hold the receipt's asset index {relative}")
        return Path(named)
    for variable, below in CACHE_CANDIDATES:
        if environ.get(variable) and (Path(environ[variable]) / below / relative).is_file():
            return Path(environ[variable]) / below
    raise GameError(f"no runtime cache holds the receipt's asset index {relative}; give the plan a cache, or "
                    f"{CACHE_ENV} (a machine's [lab] env names it for a driver session: {CACHE_ENV} = 'cache')")


def release(home: Path) -> dict:
    out = {}
    try:
        for line in (Path(home) / "release").read_text(encoding="utf-8").splitlines():
            key, sep, value = line.partition("=")
            if sep:
                out[key.strip()] = value.strip().strip('"')
    except OSError:
        pass
    return out


def java_home(receipt: dict, environ=os.environ) -> Path:
    """The JDK the instance was built on: the driver session's (session.json), else JAVA_HOME; its build checked."""
    (_major, entry), = receipt["jdk"].items()
    build = str(entry["build"])
    home = None
    session = environ.get("WB_SESSION")
    if session and (Path(session) / "session.json").is_file():
        home = (read_json(Path(session) / "session.json").get("jdk") or {}).get("home")
    home = Path(home or environ.get("JAVA_HOME") or "")
    found = release(home).get("JAVA_RUNTIME_VERSION", "")
    if found != build:
        raise GameError(f"the JDK at {home} is build {found or 'unknown'}, and the instance was built on {build}: set "
                        f"JAVA_HOME to a home of {build}")
    return home


def free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


def set_property(path: Path, key: str, value: str):
    text = path.read_text(encoding="utf-8") if path.exists() else ""
    pattern = re.compile(r"^(" + re.escape(key) + r")=.*$", re.M)
    text = pattern.sub(lambda m: f"{m.group(1)}={value}", text, count=1) if pattern.search(text) \
        else text + ("" if not text or text.endswith("\n") else "\n") + f"{key}={value}\n"
    path.write_text(text, encoding="utf-8", newline="")


def tour_rules(launch: dict) -> dict:
    """The launch's tour rules over Workbench's defaults."""
    return {**TOUR_DEFAULTS, **(launch.get("tour") or {})}


def count_lines(path: Path, text: str) -> int:
    try:
        return sum(1 for line in path.read_text(encoding="utf-8", errors="replace").splitlines() if text in line)
    except OSError:
        return 0


class Game:
    """The server and client of one instance copy, started from its receipt."""

    def __init__(self, receipt_path: Path, instance: Path, cache: Path, jdk: Path):
        self.receipt_path = Path(receipt_path)
        self.receipt = read_receipt(self.receipt_path)
        self.instance = Path(instance)
        (major, _), = self.receipt["jdk"].items()
        self.jdk = Path(jdk)
        self.roots = {"instance": str(self.instance), "cache": str(cache), f"jdk:{major}": str(self.jdk)}
        self.port = free_port()
        self.player = self.receipt["player"]["name"]
        self.ready = (self.receipt.get("session") or {}).get("ready") or READY
        self.processes: dict[str, subprocess.Popen] = {}
        self.logs: dict[str, Path] = {}

    def expand(self, value: str) -> str:
        return ROOT.sub(lambda m: self.roots[m.group(1)].rstrip("/\\"), value).replace("{port}", str(self.port))

    def argv(self, side: str, jvm: list[str] | None = None, window: str | None = None) -> list[str]:
        """The receipt's command line for ``side``, roots filled in, with ``jvm`` where a session's options go and,
        for the client, ``window`` (WIDTHxHEIGHT) in place of the receipt's size."""
        entry = self.receipt[side]
        command = [self.expand(str(token)) for token in entry["command"]]
        at = int(entry.get("jvm_at", 1))
        command[at:at] = list(jvm or [])
        if side == "client" and window:
            width, height = window.split("x")
            for flag, value in (("--width", width), ("--height", height)):
                if flag in command:
                    command[command.index(flag) + 1] = value
        return command

    def cwd(self, side: str) -> Path:
        return Path(self.expand(str(self.receipt[side]["cwd"])))

    def start(self, side: str, log: Path, jvm: list[str] | None = None, env: dict | None = None,
              window: str | None = None) -> subprocess.Popen:
        if side == "server":
            set_property(self.instance / "server" / "server.properties", "server-port", str(self.port))
        environment = {**os.environ, **(env or {}), "JAVA_HOME": str(self.jdk),
                       "PATH": str(self.jdk / "bin") + os.pathsep + os.environ.get("PATH", "")}
        stream = Path(log).open("w", encoding="utf-8")
        process = subprocess.Popen(self.argv(side, jvm, window), cwd=self.cwd(side), env=environment, stdout=stream,
                                   stderr=subprocess.STDOUT, stdin=subprocess.PIPE if side == "server" else
                                   subprocess.DEVNULL, text=True)
        stream.close()
        self.processes[side], self.logs[side] = process, Path(log)
        return process

    def alive(self, side: str) -> bool:
        process = self.processes.get(side)
        return process is not None and process.poll() is None

    def send(self, command: str):
        server = self.processes.get("server")
        if server is None or server.poll() is not None:
            raise GameError(f"the server is gone; could not send {command!r}")
        server.stdin.write(command + "\n")
        server.stdin.flush()

    def wait_for(self, side: str, text: str, count: int, timeout: float, what: str):
        """Until ``side``'s console holds ``text`` ``count`` times; refused when a game exits first or time runs out."""
        deadline = time.monotonic() + timeout
        while count_lines(self.logs[side], text) < count:
            for name in self.processes:
                if not self.alive(name):
                    raise GameError(f"the {name} exited {self.processes[name].returncode} before {what}")
            if time.monotonic() > deadline:
                raise GameError(f"no {what} within {timeout:.0f} s")
            time.sleep(0.5)

    def stop(self, grace: float = 180.0) -> dict:
        """The client closed, then the server asked to stop and given ``grace`` seconds to exit by itself."""
        exits = {}
        client = self.processes.get("client")
        if client is not None:
            if client.poll() is None:
                client.terminate()
                try:
                    client.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    client.kill()
                    client.wait()
            exits["client"] = client.returncode
        server = self.processes.get("server")
        if server is not None:
            if server.poll() is None:
                try:
                    self.send("stop")
                    server.wait(timeout=grace)
                except (GameError, OSError, subprocess.TimeoutExpired):
                    server.kill()
                    server.wait()
            exits["server"] = server.returncode
            if server.stdin:
                server.stdin.close()
        return exits
