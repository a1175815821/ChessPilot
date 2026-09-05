#!/usr/bin/env python3
"""
Re-derive R8-obfuscated chess class names for a new Duolingo APK.

Repeats the manual procedure documented in docs/REVERSE_ENGINEERING.md:
  1. mine dex files for Kotlin data-class toString() markers
     ("ChessGameState(fen=", "ChessFen(piecePlacementString=", ...)
  2. resolve which minified class owns each marker
  3. locate the Rive board controller: the class with a 0-arg getter returning
     ChessGameState AND a 6-arg (Square, Square, boolean, ArrowType, int, ArrowColor)
     arrow method
  4. write module/src/main/assets/remap.json

Usage:
  python tools/remap.py --apk 多邻国_6.85.7.apks   # .apks/.apkm bundle or plain .apk
  pip install androguard==3.3.5                    # required
"""
import argparse
import io
import json
import re
import sys
import zipfile

try:
    from androguard.core.bytecodes.dvm import DalvikVMFormat
except ImportError:
    sys.exit("pip install androguard==3.3.5")

MARKERS = {
    "chessGameState": "ChessGameState(fen=",
    "chessFen": "ChessFen(piecePlacementString=",
    "setupModel": "ChessGameSetupModel(fenNotation=",
    "arrowCoordinates": "RiveArrowCoordinates(start=",
    "resyncState": "ResyncGameState(fen=",
}

# Kept (unobfuscated) names — stable across versions.
KEPT = {
    "arrowType": "com.duolingo.chess.model.rive.ArrowType",
    "arrowColor": "com.duolingo.chess.model.rive.ArrowColor",
    "squareHighlightType": "com.duolingo.chess.model.rive.SquareHighlightType",
    "pieceType": "com.duolingo.chess.model.ChessPieceType",
    "playerColor": "com.duolingo.chess.model.ChessPlayerColor",
}


def extract_base_apk(path):
    """Return bytes of the base APK from an .apks/.apkm bundle, or the file itself."""
    if path.endswith((".apks", ".apkm")):
        with zipfile.ZipFile(path) as z:
            for n in z.namelist():
                if n.endswith(".apk") and ("base" in n.lower() or len(z.namelist()) == 1):
                    return z.read(n)
        sys.exit("no base apk in bundle")
    with open(path, "rb") as f:
        return f.read()


def load_dexes(base_apk_bytes):
    dexes = {}
    with zipfile.ZipFile(io.BytesIO(base_apk_bytes)) as z:
        for n in z.namelist():
            if re.match(r"classes\d*\.dex$", n):
                dexes[n] = DalvikVMFormat(z.read(n))
    return dexes


def find_marker_owners(dexes):
    """Marker string → defining class (via toString() bodies)."""
    owners = {}
    for d in dexes.values():
        for m in d.get_methods():
            code = m.get_code()
            if not code or m.get_name() != "toString":
                continue
            try:
                for inst in code.get_bc().get_instructions():
                    if "const-string" not in inst.get_name():
                        continue
                    s = inst.get_string()
                    for key, marker in MARKERS.items():
                        if marker in s:
                            owners[key] = m.get_class_name()
            except Exception:
                continue
    return owners


def short(cls):
    if cls and cls.startswith("L") and cls.endswith(";"):
        return cls[1:-1].replace("/", ".")
    return cls


def parse_params(descriptor):
    """Split a JVM method descriptor into param type strings.

    e.g. "(Lge4;Lge4;ZLpkg/ArrowType;ILpkg/ArrowColor;)V"
      -> ["Lge4;", "Lge4;", "Z", "Lpkg/ArrowType;", "I", "Lpkg/ArrowColor;"]
    """
    inner = descriptor.split("(", 1)[1].rsplit(")", 1)[0]
    params = []
    i = 0
    while i < len(inner):
        c = inner[i]
        if c == "L":
            j = inner.index(";", i)
            params.append(inner[i:j + 1])
            i = j + 1
        elif c == "[":
            # array: consume brackets then the element type
            j = i
            while inner[j] == "[":
                j += 1
            if inner[j] == "L":
                k = inner.index(";", j)
                params.append(inner[i:k + 1])
                i = k + 1
            else:
                params.append(inner[i:j + 1])
                i = j + 1
        else:
            params.append(c)
            i += 1
    return params


def find_controller(dexes, state_short):
    """
    Board controller = non-com/duolingo class with
      - a 0-arg method returning the ChessGameState type (g())
      - a 6-arg method whose 3rd param is boolean (s(from, to, animate, ...))
    Returns (controller_class, square_class): the first two params of the
    arrow method ARE the Square type (mirrors ArrowPainter.resolveDrawMethod).
    """
    state_desc = "L%s;" % state_short.replace(".", "/")
    candidates = []
    for d in dexes.values():
        for c in d.get_classes():
            cname = c.get_name()
            if cname.startswith("Lcom/duolingo") or cname.startswith("Ljava/") \
                    or cname.startswith("Lkotlin") or cname.startswith("Landroid"):
                continue
            methods = c.get_methods()
            has_state_getter = any(
                m.get_code() is not None and
                len(m.get_descriptor().split(")")) == 2 and
                m.get_descriptor().split(")")[1] == state_desc and
                m.get_descriptor().startswith("()")
                for m in methods
            )
            if not has_state_getter:
                continue
            arrow_methods = [
                m for m in methods
                if m.get_code() is not None and
                len(parse_params(m.get_descriptor())) == 6 and
                parse_params(m.get_descriptor())[2] == "Z"
            ]
            if arrow_methods:
                candidates.append((cname, arrow_methods[0]))
    if not candidates:
        return None, None
    # Prefer the one whose name matches the previous controller if short (R8 names are 2-4 chars)
    ctrl, arrow = sorted(candidates, key=lambda n: len(n[0]))[0]
    square = parse_params(arrow.get_descriptor())[0]
    return ctrl, square


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", required=True)
    ap.add_argument("--out", default="module/src/main/assets/remap.json")
    args = ap.parse_args()

    base = extract_base_apk(args.apk)
    print("parsing dex files (this can take a minute)…")
    dexes = load_dexes(base)

    owners = find_marker_owners(dexes)
    remap = {"classes": {}}
    for key in MARKERS:
        if key in owners:
            dotted = short(owners[key])
            remap["classes"][key] = [dotted]
            print(f"  {key:18s} -> {dotted}")
        else:
            print(f"  {key:18s} -> NOT FOUND (marker string missing)")

    for key, kept in KEPT.items():
        remap["classes"][key] = [kept]

    state = remap["classes"].get("chessGameState", [None])[0]
    if state:
        ctrl, square = find_controller(dexes, state)
        if ctrl:
            remap["classes"]["boardController"] = [short(ctrl)]
            print(f"  {'boardController':18s} -> {short(ctrl)}")
        else:
            print("  boardController    -> NOT FOUND (fill in manually)")
        if square:
            remap["classes"]["square"] = [short(square)]
            print(f"  {'square':18s} -> {short(square)} (from arrow-method signature)")
        else:
            print("  square             -> NOT FOUND (fill in manually)")

    # Preserve keys this tool cannot auto-derive (move/board/piece/point/
    # arrowModel/…): they are per-version confirmations from the existing
    # table. Overwriting the whole file used to drop them, which silently
    # killed arrow drawing (`resolveClass("square") ?: return`) after an
    # app update. resolveClass() tolerates stale names (findClass miss is
    # skipped), so keeping them is a safe best-effort fallback.
    old_top = {}
    try:
        with open(args.out, encoding="utf-8") as f:
            old_top = json.load(f)
    except Exception:
        old_top = {}
    old_classes = old_top.get("classes", {})
    for key, val in old_classes.items():
        if key not in remap["classes"]:
            remap["classes"][key] = val
            print(f"  {key:18s} -> kept from existing table")

    # Real version name from the APK (was missing → generateRemapTable logged
    # `remap table vclasses` because it greps the first quoted value).
    app_version = "unknown"
    try:
        from androguard.core.bytecodes.apk import APK
        app_version = APK(io.BytesIO(base)).get_androidversion_name() or "unknown"
    except Exception as e:
        print(f"  appVersion         -> APK parse failed ({e}); keeping old value")
        app_version = old_top.get("appVersion", "unknown")
    remap["appVersion"] = app_version
    print(f"  {'appVersion':18s} -> {app_version}")

    out_path = args.out
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(remap, f, indent=2)
    print(f"wrote {out_path}")


if __name__ == "__main__":
    main()
