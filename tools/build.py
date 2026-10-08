#!/usr/bin/env python3
import glob
import json
import os
import re
import shutil
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CFG = json.load(open(os.path.join(ROOT, "tools", "versions.json")))
OUT = os.path.join(ROOT, "build")
DIST = os.path.join(ROOT, "dist")
GRADLE = glob.glob(os.path.expanduser("~/.gradle/wrapper/dists/gradle-9.7.1-bin/*/gradle-9.7.1/bin/gradle"))[0]
JAVA_HOME = "/usr/lib/jvm/java-25-openjdk"
LOADERS = ("fabric", "neoforge")


def vkey(v):
    return tuple(int(x) for x in v.split("."))


def obfuscated(mc):
    return vkey(mc) < (26,)


COND = re.compile(r"\s*//#(if|elif|else|endif)\b\s*(.*)")


def evaluate(expr, mc, loader):
    for part in re.split(r"\s*&&\s*", expr.strip()):
        m = re.fullmatch(r"(MC|LOADER)\s*(>=|<=|==|!=|>|<)\s*(\S+)", part)
        if not m:
            raise ValueError("bad condition: " + expr)
        key, op, val = m.groups()
        a, b = (vkey(mc), vkey(val)) if key == "MC" else (loader, val)
        if not {">=": a >= b, "<=": a <= b, "==": a == b, "!=": a != b, ">": a > b, "<": a < b}[op]:
            return False
    return True


def preprocess(text, mc, loader, name):
    out = []
    stack = []
    for n, line in enumerate(text.split("\n"), 1):
        m = COND.match(line)
        if m:
            kind, expr = m.groups()
            if kind == "if":
                parent = all(s[0] for s in stack)
                hit = evaluate(expr, mc, loader)
                stack.append([hit, hit, parent])
            elif not stack:
                raise ValueError(f"{name}:{n}: //#{kind} without //#if")
            elif kind == "elif":
                hit = not stack[-1][1] and evaluate(expr, mc, loader)
                stack[-1][0] = hit
                stack[-1][1] |= hit
            elif kind == "else":
                stack[-1][0] = not stack[-1][1]
                stack[-1][1] = True
            else:
                stack.pop()
            out.append("")
            continue
        if all(s[0] for s in stack):
            out.append(re.sub(r"^(\s*)//\$ ?", r"\1", line))
        else:
            out.append("")
    if stack:
        raise ValueError(f"{name}: unclosed //#if")
    return "\n".join(out)


def mc_range(mc):
    return mc


def subst(text, mapping):
    for k, v in mapping.items():
        text = text.replace("@@" + k + "@@", str(v))
    return text


def generate(loader, mc):
    info = CFG["versions"][mc]
    d = os.path.join(OUT, f"{loader}-{mc}")
    src = os.path.join(d, "src", "main")
    if os.path.exists(src):
        shutil.rmtree(src)
    os.makedirs(d, exist_ok=True)

    mapping = {
        "VERSION": CFG["mod_version"], "MC": mc, "MC_RANGE": mc_range(mc), "JAVA": info["java"],
        "FABRIC_LOADER": CFG["fabric_loader"], "LOOM": CFG["loom"], "MODDEV": CFG["moddev"],
        "NEOFORGE": info["neoforge"],
        "LOOM_ID": "net.fabricmc.fabric-loom-remap" if obfuscated(mc) else "net.fabricmc.fabric-loom",
        "MAPPINGS": "mappings loom.officialMojangMappings()" if obfuscated(mc) else "",
        "IMPL": "modImplementation" if obfuscated(mc) else "implementation",
    }

    for base, dirs, files in os.walk(os.path.join(ROOT, "src", "main")):
        for f in files:
            rel = os.path.relpath(os.path.join(base, f), os.path.join(ROOT, "src", "main"))
            dst = os.path.join(src, rel)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            text = open(os.path.join(base, f), encoding="utf-8").read()
            if f.endswith(".java"):
                text = preprocess(text, mc, loader, rel)
            elif f.endswith(".json"):
                text = text.replace("JAVA_21", "JAVA_" + str(info["java"]))
            open(dst, "w", encoding="utf-8").write(text)

    ldir = os.path.join(ROOT, "loader", loader)
    open(os.path.join(d, "build.gradle"), "w").write(subst(open(os.path.join(ldir, "build.gradle")).read(), mapping))
    open(os.path.join(d, "settings.gradle"), "w").write(
        "pluginManagement {\n\trepositories {\n"
        "\t\tmaven { url = 'https://maven.fabricmc.net/' }\n"
        "\t\tmaven { url = 'https://maven.neoforged.net/releases' }\n"
        "\t\tgradlePluginPortal()\n\t}\n}\n"
        f"rootProject.name = 'instapack-{loader}-{mc}'\n")
    open(os.path.join(d, "gradle.properties"), "w").write(
        "org.gradle.jvmargs=-Xmx2G\norg.gradle.workers.max=2\norg.gradle.daemon=true\norg.gradle.configuration-cache=false\n")

    res = os.path.join(src, "resources")
    os.makedirs(res, exist_ok=True)
    if loader == "fabric":
        meta = subst(open(os.path.join(ldir, "fabric.mod.json")).read(), mapping)
        open(os.path.join(res, "fabric.mod.json"), "w").write(meta)
    else:
        os.makedirs(os.path.join(res, "META-INF"), exist_ok=True)
        meta = subst(open(os.path.join(ldir, "neoforge.mods.toml")).read(), mapping)
        open(os.path.join(res, "META-INF", "neoforge.mods.toml"), "w").write(meta)
        java = os.path.join(src, "java")
        for base, dirs, files in os.walk(os.path.join(ldir, "java")):
            for f in files:
                rel = os.path.relpath(os.path.join(base, f), os.path.join(ldir, "java"))
                dst = os.path.join(java, rel)
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                text = preprocess(open(os.path.join(base, f)).read(), mc, loader, rel)
                open(dst, "w").write(text)
    return d


def build(loader, mc):
    d = generate(loader, mc)
    env = dict(os.environ, JAVA_HOME=JAVA_HOME)
    log = os.path.join(d, "build.log")
    with open(log, "w") as fh:
        rc = subprocess.call([GRADLE, "--no-daemon" if os.environ.get("NO_DAEMON") else "--daemon", "-q", "build", "-x", "test"],
                             cwd=d, env=env, stdout=fh, stderr=subprocess.STDOUT)
    if rc == 0:
        os.makedirs(DIST, exist_ok=True)
        jars = [j for j in glob.glob(os.path.join(d, "build", "libs", "*.jar")) if not j.endswith(("-sources.jar", "-dev.jar"))]
        for j in jars:
            shutil.copy(j, DIST)
    return loader, mc, rc, log


def targets(args):
    out = []
    for a in args or ["fabric", "neoforge"]:
        loader, _, mc = a.partition(":")
        loaders = LOADERS if loader in ("", "all") else (loader,)
        for l in loaders:
            for v in ([mc] if mc else CFG["versions"].keys()):
                out.append((l, v))
    return out


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    jobs = int(os.environ.get("JOBS", "3"))
    todo = targets(args)
    if "--gen-only" in sys.argv:
        for t in todo:
            print(generate(*t))
        return
    failed = []
    with ThreadPoolExecutor(jobs) as pool:
        for loader, mc, rc, log in pool.map(lambda t: build(*t), todo):
            print(f"{'OK  ' if rc == 0 else 'FAIL'} {loader} {mc}" + ("" if rc == 0 else f"  ({log})"), flush=True)
            if rc:
                failed.append((loader, mc))
    print(f"{len(todo) - len(failed)}/{len(todo)} built")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
