#!/usr/bin/env python3
"""Checks that every Velocity and Adventure method the plugin calls exists in the proxy the network runs (bug B3).

The plugin compiles against velocity-api 3.4.0-SNAPSHOT from the PaperMC repository, which moves on; the proxy does
not. A call the proxy lacks compiles fine and fails only at run time (NoSuchMethodError), possibly only in a case a
test on the live proxy never hit. This script reads the built plugin jar's constant pools with javap, takes every
method reference into com/velocitypowered/ or net/kyori/, and looks each one up in the proxy's velocity.jar,
following parent classes and interfaces. Exit code 1 lists the missing ones.

Usage: tools/check_proxy_api.sh [plugin jar] [proxy velocity.jar]
Defaults: the newest build/libs/bsp_velocity_additions-*.jar, then server/velocity.jar or
../BSP-Servers/BSP-Velocity/velocity.jar.
"""

import glob
import os
import re
import subprocess
import sys
import tempfile
import zipfile

OWN_PACKAGE = "com/mrgregles/bsp_velocity_additions/"
SHADED = OWN_PACKAGE + "shaded/"
PROVIDED = ("com/velocitypowered/", "net/kyori/")
REF = re.compile(r"= (?:Interface)?Methodref\s+\S+\s+// (?:\"?)([\w/$]+)\.(\"?<init>\"?|[\w$]+):(\(\S*)")
OBJECT_METHODS = {"equals", "hashCode", "toString", "getClass", "notify", "notifyAll", "wait"}


def default_plugin_jar(root):
    jars = sorted(glob.glob(os.path.join(root, "build/libs/bsp_velocity_additions-*.jar")), key=os.path.getmtime)
    return jars[-1] if jars else None


def default_proxy_jar(root):
    for candidate in ("server/velocity.jar", "../BSP-Servers/BSP-Velocity/velocity.jar"):
        path = os.path.join(root, candidate)
        if os.path.exists(path):
            return path
    return None


def javap(classpath, names, flags):
    out = subprocess.run(["javap", *flags, "-cp", classpath, *names], capture_output=True, text=True)
    return out.stdout


def used_methods(plugin_jar, work):
    """Every (owner, name, descriptor) the plugin's own classes reference in a provided package."""
    target = os.path.join(work, "plugin")
    with zipfile.ZipFile(plugin_jar) as z:
        names = [n for n in z.namelist() if n.startswith(OWN_PACKAGE) and not n.startswith(SHADED) and n.endswith(".class")]
        z.extractall(target, names)
    classes = [n[:-len(".class")].replace("/", ".") for n in names]
    found = set()
    for i in range(0, len(classes), 40):
        for m in REF.finditer(javap(target, classes[i:i + 40], ["-v", "-p"])):
            owner, name, desc = m.group(1), m.group(2).strip('"'), m.group(3)
            if owner.startswith(PROVIDED):
                found.add((owner, name, desc))
    return found


class Proxy:
    """Method tables of the proxy jar's classes, read on demand."""

    def __init__(self, proxy_jar, work):
        self.dir = os.path.join(work, "proxy")
        with zipfile.ZipFile(proxy_jar) as z:
            z.extractall(self.dir, [n for n in z.namelist() if n.startswith(PROVIDED) and n.endswith(".class")])
        self.cache = {}

    def info(self, owner):
        if owner in self.cache:
            return self.cache[owner]
        if not os.path.exists(os.path.join(self.dir, owner + ".class")):
            self.cache[owner] = None
            return None
        text = javap(self.dir, [owner.replace("/", ".")], ["-s", "-p"])
        methods, parents, last = set(), [], None
        for line in text.splitlines():
            stripped = line.strip()
            if not parents and re.match(r"^(public |protected |private |abstract |final |sealed |non-sealed |static )*(class|interface|enum|record) ", stripped):
                header = re.sub(r"<[^<>]*(<[^<>]*>[^<>]*)*>", "", stripped).rstrip("{ ")
                for kw in (" extends ", " implements "):
                    if kw in header:
                        part = header.split(kw, 1)[1].split(" implements ")[0].split(" permits ")[0]
                        parents += [p.strip().replace(".", "/") for p in part.split(",") if p.strip()]
                if not parents and " interface " not in " " + header:
                    parents = ["java/lang/Object"]
                parents = parents or ["java/lang/Object"]
            elif "(" in stripped and not stripped.startswith("descriptor:"):
                head = stripped.split("(", 1)[0].split()
                last = head[-1] if head else None
            elif stripped.startswith("descriptor:") and last:
                name = "<init>" if last == owner.replace("/", ".") else last.split(".")[-1]
                methods.add((name, stripped.split(":", 1)[1].strip()))
                last = None
        self.cache[owner] = (methods, parents)
        return self.cache[owner]

    def has(self, owner, name, desc, seen=None):
        seen = seen or set()
        if owner in seen:
            return False
        seen.add(owner)
        if owner.startswith("java/"):
            return name in OBJECT_METHODS or owner != "java/lang/Object"
        info = self.info(owner)
        if info is None:
            return False
        methods, parents = info
        if (name, desc) in methods:
            return True
        return name != "<init>" and any(self.has(p, name, desc, seen) for p in parents)


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    plugin_jar = sys.argv[1] if len(sys.argv) > 1 else default_plugin_jar(root)
    proxy_jar = sys.argv[2] if len(sys.argv) > 2 else default_proxy_jar(root)
    if not plugin_jar or not proxy_jar:
        print("Need the plugin jar (run ./gradlew build) and the proxy's velocity.jar (server/ or ../BSP-Servers/BSP-Velocity/).")
        return 2
    with tempfile.TemporaryDirectory() as work:
        used = used_methods(plugin_jar, work)
        proxy = Proxy(proxy_jar, work)
        missing = sorted(u for u in used if not proxy.has(*u))
    print(f"Checked {len(used)} Velocity and Adventure method(s) used by {os.path.basename(plugin_jar)} against {proxy_jar}.")
    for owner, name, desc in missing:
        print(f"  MISSING in the proxy: {owner.replace('/', '.')}.{name}{desc}")
    print("OK: the proxy has all of them." if not missing else f"{len(missing)} missing: this jar would fail on that proxy.")
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main())
