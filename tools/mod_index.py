"""Read mod jars without running them: their Mixins, the engine systems those patch, the Forge events
they subscribe to and the threads they start.

    python tools/mod_index.py MODS_DIR [MODS_DIR ...] [--json OUT]

Each jar's class files are read as bytes: the constant pool, the members and their annotations. A
Mixin configuration is any JSON at the jar's root naming a ``package`` and mixin lists; each of its
classes gives its targets (``@Mixin``), its overwrites (``@Overwrite``) and its handlers
(``@Inject``, ``@Redirect``, ``@WrapOperation`` and the rest) with the methods they name. A
refmap, where the configuration names one, maps development names to the runtime's. Methods
annotated ``@SubscribeEvent`` give the events a mod listens to, by their first parameter. A
reference to a thread's constructor or an executor factory marks a mod that starts threads.

A reference is not a call made at runtime, and a handler's target is what its annotation names, not
proof that it applied. ``tools/mixin_check.py`` applies Quadrature's own Mixins offline.
"""

from __future__ import annotations

import argparse
import json
import re
import struct
import sys
import zipfile
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from pathlib import Path


# ---------------------------------------------------------------------------------------------------
# Class files

@dataclass
class Annotation:
    type: str  # field descriptor, as Lorg/spongepowered/asm/mixin/Mixin;
    values: dict


@dataclass
class Member:
    name: str
    descriptor: str
    annotations: list[Annotation] = field(default_factory=list)


@dataclass
class ClassInfo:
    name: str  # internal name, as net/minecraft/world/World
    super_name: str | None
    annotations: list[Annotation] = field(default_factory=list)
    methods: list[Member] = field(default_factory=list)
    references: set[tuple[str, str]] = field(default_factory=set)  # (owner, member name)


class Reader:
    def __init__(self, data: bytes):
        self.data, self.at = data, 0

    def u1(self) -> int:
        self.at += 1
        return self.data[self.at - 1]

    def u2(self) -> int:
        self.at += 2
        return struct.unpack_from(">H", self.data, self.at - 2)[0]

    def u4(self) -> int:
        self.at += 4
        return struct.unpack_from(">I", self.data, self.at - 4)[0]

    def skip(self, n: int) -> None:
        self.at += n


def read_class(data: bytes) -> ClassInfo:
    """A class file's name, superclass, annotations, methods and the members it refers to."""
    r = Reader(data)
    if r.u4() != 0xCAFEBABE:
        raise ValueError("not a class file")
    r.skip(4)
    count = r.u2()
    pool: list = [None] * count
    i = 1
    while i < count:
        tag = r.u1()
        if tag == 1:
            length = r.u2()
            pool[i] = ("utf8", r.data[r.at:r.at + length].decode("utf-8", errors="replace"))
            r.skip(length)
        elif tag in (3, 4):
            r.skip(4)
        elif tag in (5, 6):
            r.skip(8)
            i += 1
        elif tag in (7, 8, 16, 19, 20):
            pool[i] = (tag, r.u2())
        elif tag in (9, 10, 11, 12, 17, 18):
            pool[i] = (tag, r.u2(), r.u2())
        elif tag == 15:
            r.skip(1)
            pool[i] = (tag, r.u2())
        else:
            raise ValueError(f"unknown constant tag {tag}")
        i += 1

    def utf8(index: int) -> str:
        entry = pool[index]
        return entry[1] if entry and entry[0] == "utf8" else ""

    def class_name(index: int) -> str:
        entry = pool[index]
        return utf8(entry[1]) if entry else ""

    def element(rr: Reader):
        tag = chr(rr.u1())
        if tag in "BCDFIJSZs":
            index = rr.u2()
            return utf8(index) if tag == "s" else index
        if tag == "e":
            return (utf8(rr.u2()), utf8(rr.u2()))
        if tag == "c":
            return utf8(rr.u2())
        if tag == "@":
            return annotation(rr)
        if tag == "[":
            return [element(rr) for _ in range(rr.u2())]
        raise ValueError(f"unknown element tag {tag}")

    def annotation(rr: Reader) -> Annotation:
        kind = utf8(rr.u2())
        values = {}
        for _ in range(rr.u2()):
            name = utf8(rr.u2())
            values[name] = element(rr)
        return Annotation(kind, values)

    def attributes(rr: Reader) -> list[Annotation]:
        found = []
        for _ in range(rr.u2()):
            name = utf8(rr.u2())
            length = rr.u4()
            end = rr.at + length
            if name in ("RuntimeVisibleAnnotations", "RuntimeInvisibleAnnotations"):
                for _ in range(rr.u2()):
                    found.append(annotation(rr))
            rr.at = end
        return found

    r.skip(2)
    this = class_name(r.u2())
    super_index = r.u2()
    super_name = class_name(super_index) if super_index else None
    r.skip(2 * r.u2())
    for _ in range(r.u2()):  # fields
        r.skip(6)
        attributes(r)
    methods = []
    for _ in range(r.u2()):
        r.skip(2)
        name, descriptor = utf8(r.u2()), utf8(r.u2())
        methods.append(Member(name, descriptor, attributes(r)))
    info = ClassInfo(this, super_name, attributes(r), methods)
    for entry in pool:
        if entry and entry[0] in (9, 10, 11):
            owner = class_name(entry[1])
            name_and_type = pool[entry[2]]
            info.references.add((owner, utf8(name_and_type[1])))
    return info


# ---------------------------------------------------------------------------------------------------
# Mods

MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;"
OVERWRITE = "Lorg/spongepowered/asm/mixin/Overwrite;"
SUBSCRIBE = "Lnet/minecraftforge/fml/common/eventhandler/SubscribeEvent;"
HANDLERS = {
    "Lorg/spongepowered/asm/mixin/injection/Inject;": "inject",
    "Lorg/spongepowered/asm/mixin/injection/Redirect;": "redirect",
    "Lorg/spongepowered/asm/mixin/injection/ModifyArg;": "modifyArg",
    "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;": "modifyArgs",
    "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;": "modifyVariable",
    "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;": "modifyConstant",
    "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;": "wrapOperation",
    "Lcom/llamalad7/mixinextras/injector/v2/WrapWithCondition;": "wrapWithCondition",
    "Lcom/llamalad7/mixinextras/injector/WrapWithCondition;": "wrapWithCondition",
    "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;": "modifyExpressionValue",
    "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;": "modifyReturnValue",
    "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;": "wrapMethod",
}
THREADS = {("java/lang/Thread", "<init>"), ("java/util/concurrent/ThreadPoolExecutor", "<init>"),
           ("java/util/concurrent/ForkJoinPool", "<init>"), ("java/util/concurrent/ScheduledThreadPoolExecutor", "<init>")}
EXECUTOR_FACTORY = re.compile(r"^new\w*(ThreadPool|Executor|ThreadExecutor)\w*$")

# Engine systems by the target's package, most specific first.
SYSTEMS = [
    ("net/minecraft/client/renderer/chunk/", "chunk rendering"),
    ("net/minecraft/client/renderer/entity/", "entity rendering"),
    ("net/minecraft/client/renderer/tileentity/", "block entity rendering"),
    ("net/minecraft/client/renderer/", "rendering"),
    ("net/minecraft/client/particle/", "particles"),
    ("net/minecraft/client/gui/", "interface"),
    ("net/minecraft/client/multiplayer/", "client world"),
    ("net/minecraft/client/network/", "network"),
    ("net/minecraft/client/", "client"),
    ("net/minecraft/world/chunk/storage/", "storage"),
    ("net/minecraft/world/chunk/", "chunks"),
    ("net/minecraft/world/gen/", "world generation"),
    ("net/minecraft/world/storage/", "storage"),
    ("net/minecraft/world/biome/", "biomes"),
    ("net/minecraft/world/", "world"),
    ("net/minecraft/entity/", "entities"),
    ("net/minecraft/tileentity/", "block entities"),
    ("net/minecraft/block/", "blocks"),
    ("net/minecraft/network/", "network"),
    ("net/minecraft/server/management/", "player tracking"),
    ("net/minecraft/server/", "server"),
    ("net/minecraft/util/", "utilities"),
    ("net/minecraft/", "game, other"),
    ("net/minecraftforge/", "Forge"),
    ("", "other mods"),
]

FORGE_MOD = "Lnet/minecraftforge/fml/common/Mod;"
REQUIRED = re.compile(r"required-(?:after|before|client|server)?:?([\w-]+)")


def system_of(target: str) -> str:
    for prefix, system in SYSTEMS:
        if target.startswith(prefix):
            return system
    return "other mods"


@dataclass
class Mixin:
    mod: str
    mixin: str
    targets: list[str]
    overwrites: list[str]
    handlers: list[tuple[str, str, list[str]]]  # (kind, handler method, target methods)


@dataclass
class ModRecord:
    jar: str
    mod: str
    classes: int = 0
    packages: Counter = field(default_factory=Counter)
    configs: list[str] = field(default_factory=list)
    mixins: list[Mixin] = field(default_factory=list)
    events: Counter = field(default_factory=Counter)
    threads: Counter = field(default_factory=Counter)
    errors: list[str] = field(default_factory=list)
    mod_ids: list[str] = field(default_factory=list)  # from @Mod annotations
    requires: set[str] = field(default_factory=set)  # mod ids its @Mod dependencies require


def jar_mod_name(names: list[str], owner_of) -> str:
    """The jar's mod: the owner most of its classes belong to."""
    owners = Counter(owner_of(n.replace("/", ".")[:-6] + ".x() line: 0") for n in names if n.endswith(".class"))
    owners.pop(None, None)
    owners.pop("Other", None)
    return owners.most_common(1)[0][0] if owners else "Other"


def descriptor_class(descriptor: str) -> str:
    return descriptor[1:-1] if descriptor.startswith("L") and descriptor.endswith(";") else descriptor


def runtime_name(refmap: dict, mixin: str, name: str) -> str:
    """A development name as the runtime knows it, through the refmap where it maps one."""
    mapped = refmap.get("mappings", {}).get(mixin, {}).get(name)
    if not mapped:
        return name
    match = re.search(r";?([\w$<>]+)\(", mapped)
    if match:
        return match.group(1)
    return mapped.split(";")[-1] if ";" in mapped else mapped


def index_jar(path: Path, owner_of) -> ModRecord:
    with zipfile.ZipFile(path) as jar:
        names = jar.namelist()
        record = ModRecord(path.name, jar_mod_name(names, owner_of))
        configs = {}
        for name in names:
            if "/" in name or not name.endswith(".json"):
                continue
            try:
                config = json.loads(jar.read(name).decode("utf-8"))
            except (ValueError, UnicodeDecodeError):
                continue
            if isinstance(config, dict) and "package" in config and any(
                    k in config for k in ("mixins", "client", "server")):
                configs[name] = config
        for name, config in configs.items():
            record.configs.append(name)
            refmap = {}
            if config.get("refmap") and config["refmap"] in names:
                try:
                    refmap = json.loads(jar.read(config["refmap"]).decode("utf-8"))
                except ValueError:
                    record.errors.append(f"refmap {config['refmap']} unreadable")
            package = config["package"].replace(".", "/")
            for side in ("mixins", "client", "server"):
                for entry in config.get(side) or []:
                    internal = f"{package}/{entry.replace('.', '/')}"
                    try:
                        info = read_class(jar.read(internal + ".class"))
                    except KeyError:
                        record.errors.append(f"{name}: {entry} not in the jar")
                        continue
                    record.mixins.append(mixin_of(record.mod, info, refmap))
        for name in names:
            if not name.endswith(".class"):
                continue
            record.classes += 1
            record.packages["/".join(name.split("/")[:3])] += 1
            try:
                info = read_class(jar.read(name))
            except (ValueError, struct.error, IndexError) as error:
                record.errors.append(f"{name}: {error}")
                continue
            for a in info.annotations:
                if a.type == FORGE_MOD and isinstance(a.values.get("modid"), str):
                    record.mod_ids.append(a.values["modid"])
                    dependencies = a.values.get("dependencies")
                    if isinstance(dependencies, str):
                        record.requires.update(m.lower() for m in REQUIRED.findall(dependencies))
            for method in info.methods:
                if any(a.type == SUBSCRIBE for a in method.annotations):
                    parameter = re.match(r"\(L([\w/$]+);", method.descriptor)
                    if parameter:
                        record.events[parameter.group(1).replace("/", ".")] += 1
            for owner, member in info.references:
                if (owner, member) in THREADS or (owner == "java/util/concurrent/Executors" and EXECUTOR_FACTORY.match(member)):
                    record.threads[f"{owner.rsplit('/', 1)[-1]}.{member}"] += 1
    return record


def mixin_of(mod: str, info: ClassInfo, refmap: dict) -> Mixin:
    targets = []
    for a in info.annotations:
        if a.type == MIXIN:
            targets += [descriptor_class(v) for v in a.values.get("value", [])]
            targets += [t.replace(".", "/") for t in a.values.get("targets", [])]
    overwrites, handlers = [], []
    for method in info.methods:
        for a in method.annotations:
            if a.type == OVERWRITE:
                overwrites.append(runtime_name(refmap, info.name, method.name))
            elif a.type in HANDLERS:
                named = a.values.get("method", [])
                named = named if isinstance(named, list) else [named]
                handlers.append((HANDLERS[a.type], method.name,
                                 [runtime_name(refmap, info.name, str(m)).split("(")[0] for m in named]))
    return Mixin(mod, info.name, targets, overwrites, handlers)


@dataclass
class MixinIndex:
    """Who overwrote which game method and whose handler a merged method is, for attribution."""
    overwrites: dict[tuple[str, str], str] = field(default_factory=dict)  # (class dotted, method) -> mod
    handlers: dict[tuple[str, str], str] = field(default_factory=dict)  # (class dotted, handler) -> mod

    @staticmethod
    def of(records: list[ModRecord]) -> "MixinIndex":
        index = MixinIndex()
        for record in records:
            for mixin in record.mixins:
                for target in mixin.targets:
                    dotted = target.replace("/", ".")
                    for name in mixin.overwrites:
                        index.overwrites[(dotted, name)] = record.mod
                    for _kind, handler, _methods in mixin.handlers:
                        index.handlers[(dotted, handler)] = record.mod
        return index

    def to_json(self) -> dict:
        return {"overwrites": [[c, m, mod] for (c, m), mod in sorted(self.overwrites.items())],
                "handlers": [[c, h, mod] for (c, h), mod in sorted(self.handlers.items())]}

    @staticmethod
    def from_json(data: dict) -> "MixinIndex":
        index = MixinIndex()
        for c, m, mod in data.get("overwrites", []):
            index.overwrites[(c, m)] = mod
        for c, h, mod in data.get("handlers", []):
            index.handlers[(c, h)] = mod
        return index


def survey(records: list[ModRecord]) -> dict:
    """Per mod: its Mixins by engine system, its overwrites, the events it subscribes to, its threads."""
    mods: dict = {}
    for record in records:
        entry = mods.setdefault(record.mod, {"jars": [], "classes": 0, "mixins": 0, "systems": Counter(),
                                             "overwrites": [], "handlers": Counter(), "events": Counter(),
                                             "threads": Counter(), "errors": []})
        entry["jars"].append(record.jar)
        entry.setdefault("modIds", []).extend(record.mod_ids)
        entry.setdefault("requires", []).extend(sorted(record.requires - set(record.mod_ids)))
        entry["classes"] += record.classes
        entry["mixins"] += len(record.mixins)
        for mixin in record.mixins:
            for target in mixin.targets:
                entry["systems"][system_of(target)] += 1
                entry["overwrites"] += [f"{target.rsplit('/', 1)[-1]}.{name}" for name in mixin.overwrites]
            for kind, _handler, _methods in mixin.handlers:
                entry["handlers"][kind] += 1
        entry["events"].update(record.events)
        entry["threads"].update(record.threads)
        entry["errors"] += record.errors[:20]
    return {mod: {k: (dict(v.most_common()) if isinstance(v, Counter) else v) for k, v in entry.items()}
            for mod, entry in sorted(mods.items())}


def index_dirs(dirs: list[Path], owner_of) -> list[ModRecord]:
    """Every jar of the folders, once: a jar of the same name and size on both sides counts once."""
    records, seen = [], set()
    for directory in dirs:
        for jar in sorted(directory.glob("*.jar")):
            key = (jar.name, jar.stat().st_size)
            if key not in seen:
                seen.add(key)
                records.append(index_jar(jar, owner_of))
    return records


def main(argv: list[str] | None = None) -> int:
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from diagnostics import owner_of

    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("dirs", type=Path, nargs="+")
    parser.add_argument("--json", type=Path)
    args = parser.parse_args(argv)
    records = index_dirs(args.dirs, owner_of)
    result = {"survey": survey(records), "index": MixinIndex.of(records).to_json()}
    for mod, entry in result["survey"].items():
        systems = ", ".join(f"{s} {n}" for s, n in list(entry["systems"].items())[:6])
        print(f"{mod:22s} {entry['classes']:5d} classes, {entry['mixins']:3d} mixins ({systems}); "
              f"{len(entry['overwrites'])} overwrites; {sum(entry['events'].values())} event handlers; "
              f"threads {sum(entry['threads'].values())}")
    if args.json:
        args.json.write_text(json.dumps(result, indent=2), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
