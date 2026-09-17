#!/usr/bin/env python3
"""Strip Google cmdline-tools down to the Java sdkmanager classpath.

The official commandlinetools-linux zip is ~150MB because it ships lint,
R8, kotlin-compiler and apkanalyzer. Those are x86_64-unrelated Java, but
they blow the APK/GitHub size budget. sdkmanager only needs
lib/sdkmanager-classpath.jar plus the jars listed in its Class-Path.
"""
from __future__ import annotations

import argparse
import io
import zipfile
from pathlib import Path

KEEP_ALWAYS = (
    "cmdline-tools/bin/sdkmanager",
    "cmdline-tools/lib/sdkmanager-classpath.jar",
    "cmdline-tools/source.properties",
    "cmdline-tools/NOTICE.txt",
    "cmdline-tools/lib/README",
)


def _unfold_manifest(text: str) -> list[str]:
    lines: list[str] = []
    buf = ""
    for line in text.splitlines():
        if line.startswith(" ") and buf:
            buf += line[1:]
            continue
        if buf:
            lines.append(buf)
        buf = line
    if buf:
        lines.append(buf)
    return lines


def _classpath_from_manifest(data: bytes, jar_name: str) -> list[str]:
    with zipfile.ZipFile(io.BytesIO(data)) as jz:
        try:
            mf = jz.read("META-INF/MANIFEST.MF").decode("utf-8", "replace")
        except KeyError:
            return []
    cp = ""
    for line in _unfold_manifest(mf):
        if line.lower().startswith("class-path:"):
            cp = line.split(":", 1)[1].strip()
            break
    base = str(Path(jar_name).parent).replace("\\", "/")
    out: list[str] = []
    for rel in cp.split():
        parts: list[str] = []
        for p in f"{base}/{rel}".replace("\\", "/").split("/"):
            if p == "..":
                if parts:
                    parts.pop()
            elif p and p != ".":
                parts.append(p)
        out.append("/".join(parts))
    return out


def slim(src: Path, dest: Path) -> None:
    keep: list[str] = []
    with zipfile.ZipFile(src) as z:
        names = {n.replace("\\", "/") for n in z.namelist()}
        needed = set(KEEP_ALWAYS)
        needed.update(
            _classpath_from_manifest(
                z.read("cmdline-tools/lib/sdkmanager-classpath.jar"),
                "cmdline-tools/lib/sdkmanager-classpath.jar",
            )
        )
        for n in sorted(needed):
            if n not in names:
                raise SystemExit(f"missing {n} in {src}")
            keep.append(n)
        dest.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(dest, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zo:
            for n in keep:
                mapped = "cmdline-tools/latest/" + n[len("cmdline-tools/") :]
                zo.writestr(mapped, z.read(n))
    print(f"slim {src} -> {dest} ({dest.stat().st_size} bytes, {len(keep)} files)")


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("src")
    p.add_argument("dest")
    args = p.parse_args()
    slim(Path(args.src), Path(args.dest))


if __name__ == "__main__":
    main()
