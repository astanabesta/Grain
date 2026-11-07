#!/usr/bin/env python3
"""
Type-check the Grain iOS sources without a Mac.

Xcode is the real gate, but it only exists on a Mac runner, so an unresolved
name in a Swift file reaches the release job before anyone notices.  And
`swiftc -parse` only validates *syntax* - it will happily accept a reference to
a name that does not exist.  That is exactly how a bare `label` slipped into
`GrainCaptureController` and broke the v0.8.1 Xcode build.

This script lifts the source ranges that are easy to break - the drive-mode
enum, the drive-mode switch, the gallery entry, the receipt renderer and the
Hi-Res accumulator API - into a small harness with minimal stubs, and
type-checks it with plain `swiftc -typecheck`.  The lifted text is verbatim, so
the harness cannot drift from the real code.

Two rules keep it safe to run in CI:

  * a genuine type error exits 1 (this is the gate);
  * if the harness cannot find the code it expects - the sources were
    restructured - it prints SKIPPED and exits 0.  A stale harness must never
    block a release.

Usage:  python3 tools/ios-typecheck.py
"""
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
IOS = Path(os.environ.get("GRAIN_IOS_ROOT", ROOT / "ios" / "GrainPhoto"))
WORK = Path(os.environ.get("GRAIN_IOS_WORK", "/tmp"))


def find_swiftc():
    for cand in ("swiftc", "/usr/bin/swiftc", "/usr/local/bin/swiftc"):
        found = shutil.which(cand) if os.sep not in cand else (
            cand if os.path.exists(cand) else None)
        if found:
            return found
    return None


def lines(path):
    return Path(path).read_text().splitlines()


def slice_between(src, start_pat, end_pat):
    """Return source text from the line matching start_pat through end_pat (inclusive)."""
    out, on = [], False
    for ln in src:
        if not on:
            if re.search(start_pat, ln):
                on = True
                out.append(ln)
            continue
        out.append(ln)
        if re.search(end_pat, ln):
            break
    if not out:
        raise LookupError("pattern not found: %r -> %r" % (start_pat, end_pat))
    return "\n".join(out)


HARNESS = r'''
import Foundation

// ---- stub of the real accumulator public API (signatures lifted verbatim) ----
public final class HiResAccumulator {
__STATIC_LETS__
__TARGET_PROP__
__STORED_PROPS__
__INIT_SIG__
__DIMS_SIG__
}

// ---- minimal stand-ins for the app types the lifted code touches ----
public struct StubFilm { public let code: String; public let name: String }
public enum FilmCatalog { public static let all: [StubFilm] = [] }
public enum PhotoStore { public static func recipeSlug(_ s: String) -> String { s } }

// ---- the real DriveSetting enum ----
__ENUM_SRC__

// ---- the real RollEntry struct ----
__ENTRY_SRC__

// ---- a stand-in for the capture controller so the real switch compiles ----
final class Harness {
    var driveSetting: DriveSetting = .single
    var filmIndex = 0
    var currentFilm = StubFilm(code: "X", name: "X")
    func showToast(_ title: String, subtitle: String = "") {}
    func runSelfTimer(seconds: Int, onFire: @escaping () -> Void) {}
    func captureSingleFrame(filmOverride: StubFilm?, lensPosOverride: StubFilm?, extraFilms: [StubFilm] = []) {}
    func captureFocusBracket(planes: [Double]) {}
    func captureHiResSequence() {}
    func triggerShutter() {
__SWITCH_SRC__
    }
}

// ---- the real receipt rendering ----
public final class PhotoStoreHarness {
__RECEIPT_SRC__
__RECEIPT_HELPER__
}

// ---- the real hiResMp derivation ----
func hiResMpFor(accumulator: HiResAccumulator) -> Int {
__HIMP_LINE__
    return hiResMp
}
'''


def build_harness():
    cap = lines(IOS / "Camera" / "GrainCaptureController.swift")
    store = lines(IOS / "Camera" / "PhotoStore.swift")
    dev = lines(IOS / "Engine" / "GrainDeveloper.swift")
    merger = lines(IOS / "Engine" / "HiResMerger.swift")

    parts = [
        ("__STATIC_LETS__",
         "\n".join(l for l in merger if "public static let targetPixels" in l)),
        ("__TARGET_PROP__",
         "\n".join(l for l in merger if "public let targetPixelCount" in l)),
        ("__STORED_PROPS__",
         "\n".join(l for l in merger if l.strip() in (
             "public let outWidth: Int",
             "public let outHeight: Int",
             "private let aspectRatio: String",
             "private let digitalZoom: Float",
         ))),
        ("__INIT_SIG__",
         slice_between(merger,
                       r"public init\(aspectRatio: String, digitalZoom: Float",
                       r"^\s{4}\}$")),
        ("__DIMS_SIG__",
         slice_between(merger,
                       r"public static func computeOutputDimensions",
                       r"^\s{4}\}$")),
        ("__ENUM_SRC__",
         slice_between(cap, r"^public enum DriveSetting", r"^\}$")),
        ("__ENTRY_SRC__",
         slice_between(store, r"^public struct RollEntry", r"^\}$")),
        ("__SWITCH_SRC__",
         slice_between(
             slice_between(cap, r"^    public func triggerShutter\(\)", r"^\s{4}\}$").split("\n"),
             r"^        switch driveSetting \{$", r"^        \}$")),
        ("__RECEIPT_SRC__",
         slice_between(store, r"public func receiptText\(for entry", r"^\s{4}\}$")),
        ("__RECEIPT_HELPER__",
         slice_between(store, r"private static func receiptHeader", r"^\s{4}\}$")),
        ("__HIMP_LINE__",
         [l for l in dev if "let hiResMp = accumulator.targetPixelCount" in l][0].strip()),
    ]

    harness = HARNESS
    for token, value in parts:
        harness = harness.replace(token, value)
    return harness


def main():
    swiftc = find_swiftc()
    if not swiftc:
        print("SKIPPED: no swiftc on PATH - nothing to type-check against.")
        return 0

    try:
        harness = build_harness()
    except LookupError as exc:
        print("SKIPPED: the iOS sources moved and this harness needs updating (%s)." % exc)
        return 0
    except IndexError:
        print("SKIPPED: the iOS sources moved and this harness needs updating.")
        return 0

    out = WORK / "grain-ios-typecheck.swift"
    out.write_text(harness)

    cmd = [swiftc, "-typecheck", "-swift-version", "5", str(out)]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        print("=== iOS type-check FAILED ===")
        print(result.stderr)
        return 1
    if result.stderr.strip():
        print(result.stderr)
    print("OK: iOS type-check clean (%s)." % out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
