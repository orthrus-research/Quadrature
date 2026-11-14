"""Survey how the mods in a folder touch OpenGL state and the game's rendering classes.

Reads each JAR's class files and lists, from their constant pools, which methods they call
and which rendering classes they patch. Nothing is executed and no mod code is read as
source: the survey sees only the names a class refers to.

    python tools/mod_survey.py MODS_DIR [--json OUT.json]

A reference is not proof of use at runtime, and a call's arguments are not visible: a mod
that calls glDepthFunc is listed whether it passes the game's usual comparison or another.
"""

from __future__ import annotations

import argparse
import json
import struct
import sys
import zipfile
from collections import defaultdict
from pathlib import Path

GL = "org/lwjgl/opengl/"
STATE = "net/minecraft/client/renderer/GlStateManager"
HELPER = "net/minecraft/client/renderer/OpenGlHelper"

# category -> list of (owner class or prefix, method names). An owner ending in "/" is a prefix.
CALLS = {
    "depth function, direct": [(GL, {"glDepthFunc"})],
    "depth function, through the game": [(STATE, {"func_179143_c", "depthFunc"})],
    "depth mask, direct": [(GL, {"glDepthMask"})],
    "depth range": [(GL, {"glDepthRange"})],
    "clear depth value": [(GL, {"glClearDepth"}), (STATE, {"func_179151_a", "clearDepth"})],
    "polygon offset, direct": [(GL, {"glPolygonOffset"})],
    "polygon offset, through the game": [(STATE, {"func_179136_a", "doPolygonOffset"})],
    "enable or disable, direct": [(GL, {"glEnable", "glDisable"})],
    "blend function, direct": [(GL, {"glBlendFunc", "glBlendFuncSeparate"})],
    "alpha function, direct": [(GL, {"glAlphaFunc"})],
    "attribute stack": [(GL, {"glPushAttrib", "glPopAttrib"}), (STATE, {"func_179123_a", "pushAttrib"})],
    "display lists": [(GL, {"glNewList", "glCallList", "glGenLists"})],
    "immediate mode": [(GL, {"glBegin"})],
    "read pixels": [(GL, {"glReadPixels"})],
    "copy framebuffer to texture": [(GL, {"glCopyTexImage2D", "glCopyTexSubImage2D"})],
    "stencil": [(GL, {"glStencilFunc", "glStencilOp", "glStencilMask"})],
    "own shader programs": [
        (GL, {"glUseProgram", "glUseProgramObjectARB"}),
        (HELPER, {"func_153161_d", "glUseProgram"}),
    ],
    "own framebuffers": [
        (GL, {"glBindFramebuffer", "glBindFramebufferEXT"}),
        (HELPER, {"func_153171_g", "glBindFramebuffer"}),
    ],
    "vertex array objects": [(GL, {"glBindVertexArray"})],
    "generic vertex attributes": [(GL, {"glEnableVertexAttribArray", "glVertexAttribPointer"})],
    "buffer objects, direct": [(GL, {"glBindBuffer", "glBindBufferARB"})],
    "matrices, direct": [
        (GL, {"glLoadMatrix", "glLoadMatrixf", "glMultMatrix", "glMultMatrixf", "glMatrixMode", "glOrtho",
              "glFrustum", "glLoadIdentity"}),
        ("org/lwjgl/util/glu/", {"gluPerspective"}),
    ],
    "pixel store": [(GL, {"glPixelStorei"})],
    "scissor or viewport, direct": [(GL, {"glScissor", "glViewport"})],
}

# Rendering classes whose patching matters to a renderer, by internal name.
TARGETS = {
    "EntityRenderer": "net/minecraft/client/renderer/EntityRenderer",
    "RenderGlobal": "net/minecraft/client/renderer/RenderGlobal",
    "RenderChunk": "net/minecraft/client/renderer/chunk/RenderChunk",
    "BlockRendererDispatcher": "net/minecraft/client/renderer/BlockRendererDispatcher",
    "BlockModelRenderer": "net/minecraft/client/renderer/BlockModelRenderer",
    "BlockFluidRenderer": "net/minecraft/client/renderer/BlockFluidRenderer",
    "BufferBuilder": "net/minecraft/client/renderer/BufferBuilder",
    "GlStateManager": STATE,
    "TextureMap": "net/minecraft/client/renderer/texture/TextureMap",
    "ChunkCache": "net/minecraft/world/ChunkCache",
    "ForgeHooksClient": "net/minecraftforge/client/ForgeHooksClient",
}
MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;"
TRANSFORMER = "net/minecraft/launchwrapper/IClassTransformer"


def constant_pool(data: bytes):
    """Returns (utf8 strings, class names, method references as (owner, name)) for one class file."""
    if data[:4] != b"\xca\xfe\xba\xbe":
        return set(), set(), set()
    count = struct.unpack_from(">H", data, 8)[0]
    position = 10
    utf8, classes, name_and_type, member_refs = {}, {}, {}, []
    index = 1
    try:
        while index < count:
            tag = data[position]
            if tag == 1:
                length = struct.unpack_from(">H", data, position + 1)[0]
                utf8[index] = data[position + 3:position + 3 + length].decode("utf-8", "replace")
                position += 3 + length
            elif tag in (3, 4):
                position += 5
            elif tag in (5, 6):
                position += 9
                index += 1
            elif tag == 7:
                classes[index] = struct.unpack_from(">H", data, position + 1)[0]
                position += 3
            elif tag in (8, 16, 19, 20):
                position += 3
            elif tag in (9, 10, 11):
                member_refs.append(struct.unpack_from(">HH", data, position + 1))
                position += 5
            elif tag == 12:
                name_and_type[index] = struct.unpack_from(">H", data, position + 1)[0]
                position += 5
            elif tag == 15:
                position += 4
            elif tag in (17, 18):
                position += 5
            else:
                return set(utf8.values()), set(), set()
            index += 1
    except (IndexError, struct.error):
        return set(utf8.values()), set(), set()
    class_names = {utf8.get(i, "") for i in classes.values()}
    methods = set()
    for class_index, nat_index in member_refs:
        owner = utf8.get(classes.get(class_index, 0), "")
        name = utf8.get(name_and_type.get(nat_index, 0), "")
        methods.add((owner, name))
    return set(utf8.values()), class_names, methods


# Calls that can move depth out of the depth buffer, and the constants that say they do.
DEPTH_CALLS = {
    "reads depth pixels": {"glReadPixels"},
    "copies depth to a texture": {"glCopyTexImage2D", "glCopyTexSubImage2D"},
    "creates a depth texture": {"glTexImage2D", "glTexStorage2D"},
    "attaches a depth texture": {"glFramebufferTexture2D", "glFramebufferTexture2DEXT", "glFramebufferTexture"},
}
# DEPTH_COMPONENT, DEPTH_STENCIL, the sized depth formats and the depth attachment points.
DEPTH_CONSTANTS = {0x1902, 0x84F9, 0x81A5, 0x81A6, 0x81A7, 0x8CAC, 0x8CAD, 0x88F0, 0x8D00, 0x821A}


def depth_reads(data: bytes) -> set[str]:
    """Categories of DEPTH_CALLS for which one method both makes the call and names a depth constant.

    A heuristic over raw bytecode: it looks for the call's and the constant's instruction bytes in
    the same method body without decoding instructions, so it can report a pair that is unrelated.
    """
    if data[:4] != b"\xca\xfe\xba\xbe":
        return set()
    count = struct.unpack_from(">H", data, 8)[0]
    position = 10
    utf8, classes, name_and_type, member_refs, integers = {}, {}, {}, {}, {}
    index = 1
    try:
        while index < count:
            tag = data[position]
            if tag == 1:
                length = struct.unpack_from(">H", data, position + 1)[0]
                utf8[index] = data[position + 3:position + 3 + length].decode("utf-8", "replace")
                position += 3 + length
            elif tag == 3:
                integers[index] = struct.unpack_from(">i", data, position + 1)[0]
                position += 5
            elif tag == 4:
                position += 5
            elif tag in (5, 6):
                position += 9
                index += 1
            elif tag == 7:
                classes[index] = struct.unpack_from(">H", data, position + 1)[0]
                position += 3
            elif tag in (8, 16, 19, 20):
                position += 3
            elif tag in (9, 10, 11):
                member_refs[index] = struct.unpack_from(">HH", data, position + 1)
                position += 5
            elif tag == 12:
                name_and_type[index] = struct.unpack_from(">H", data, position + 1)[0]
                position += 5
            elif tag == 15:
                position += 4
            elif tag in (17, 18):
                position += 5
            else:
                return set()
            index += 1
        # Instruction bytes that push a depth constant: sipush for small values, ldc for the rest.
        pushes = [b"\x11" + struct.pack(">h", c) for c in DEPTH_CONSTANTS if c <= 0x7FFF]
        for pool_index, value in integers.items():
            if value in DEPTH_CONSTANTS:
                pushes.append(b"\x13" + struct.pack(">H", pool_index))
                if pool_index < 256:
                    pushes.append(b"\x12" + bytes([pool_index]))
        calls = defaultdict(list)
        for pool_index, (class_index, nat_index) in member_refs.items():
            owner = utf8.get(classes.get(class_index, 0), "")
            name = utf8.get(name_and_type.get(nat_index, 0), "")
            if "/opengl/" not in owner and owner != HELPER:
                continue
            for category, names in DEPTH_CALLS.items():
                if name in names:
                    calls[category].append(b"\xb8" + struct.pack(">H", pool_index))
        if not calls:
            return set()
        # Skip the class header and interfaces to reach the fields and methods.
        position += 6
        position += 2 + 2 * struct.unpack_from(">H", data, position)[0]
        found = set()
        for members in ("fields", "methods"):
            member_count = struct.unpack_from(">H", data, position)[0]
            position += 2
            for _ in range(member_count):
                attribute_count = struct.unpack_from(">H", data, position + 6)[0]
                position += 8
                for _ in range(attribute_count):
                    name_index, length = struct.unpack_from(">HI", data, position)
                    body = data[position + 6:position + 6 + length]
                    position += 6 + length
                    if members == "methods" and utf8.get(name_index) == "Code" \
                            and any(push in body for push in pushes):
                        for category, patterns in calls.items():
                            if any(pattern in body for pattern in patterns):
                                found.add(category)
        return found
    except (IndexError, struct.error):
        return set()


def survey_jar(path: Path) -> dict:
    calls = defaultdict(int)
    patches = defaultdict(set)
    depth = defaultdict(set)
    try:
        archive = zipfile.ZipFile(path)
    except zipfile.BadZipFile:
        return {"calls": {}, "patches": {}, "classes": 0, "depth": {}, "error": "not a readable archive"}
    classes = 0
    with archive:
        for entry in archive.infolist():
            if not entry.filename.endswith(".class"):
                continue
            classes += 1
            data = archive.read(entry)
            strings, class_names, methods = constant_pool(data)
            for category in depth_reads(data):
                depth[category].add(entry.filename[:-6])
            for category, owners in CALLS.items():
                for owner, names in owners:
                    if any((o == owner or (owner.endswith("/") and o.startswith(owner))) and n in names
                           for o, n in methods):
                        calls[category] += 1
                        break
            is_mixin = MIXIN in strings
            is_transformer = TRANSFORMER in class_names
            if is_mixin or is_transformer:
                for label, internal in TARGETS.items():
                    dotted = internal.replace("/", ".")
                    if internal in class_names or internal in strings or dotted in strings \
                            or ("L" + internal + ";") in strings:
                        patches[label].add("Mixin" if is_mixin else "transformer")
    return {"calls": dict(calls), "patches": {k: sorted(v) for k, v in patches.items()}, "classes": classes,
            "depth": {k: sorted(v) for k, v in depth.items()}}


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("mods", type=Path)
    parser.add_argument("--json", type=Path, help="write the full per-mod result here")
    args = parser.parse_args(argv)
    jars = sorted(args.mods.rglob("*.jar"))
    if not jars:
        print(f"No JAR files under {args.mods}", file=sys.stderr)
        return 2
    results = {jar.name: survey_jar(jar) for jar in jars}

    print(f"{len(jars)} JARs, {sum(r['classes'] for r in results.values())} classes")
    print("\nCalls (mods with at least one class that makes the call):")
    for category in CALLS:
        mods = sorted(name for name, r in results.items() if category in r["calls"])
        print(f"  {len(mods):4d}  {category}")
    print("\nPatches to rendering classes (mods with a Mixin or transformer naming the class):")
    for label in TARGETS:
        mods = sorted(name for name, r in results.items() if label in r["patches"])
        print(f"  {len(mods):4d}  {label}: " + ", ".join(mods))
    print("\nDepth leaving the depth buffer (a method makes the call and names a depth constant):")
    for category in DEPTH_CALLS:
        print(f"  {category}:")
        for name, r in sorted(results.items()):
            if category in r["depth"]:
                print(f"    {name}: " + ", ".join(c.rsplit("/", 1)[-1] for c in r["depth"][category]))
    if args.json:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(json.dumps(results, indent=1) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
