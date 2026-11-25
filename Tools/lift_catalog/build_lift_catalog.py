#!/usr/bin/env python3
"""Build the Lift Log's bundled exercise catalogue from free-exercise-db.

Source: https://github.com/yuhonas/free-exercise-db (The Unlicense, public domain), pinned to
SOURCE_SHA below so a rebuild is reproducible. Only exercise NAMES, MUSCLES and EQUIPMENT are taken.
The source's photos and instruction texts are deliberately left out: the project owner states the
photos' provenance is unknown (free-exercise-db issue #2), and the instructions descend from the
same scraped corpus, so neither is safe to redistribute.

The source's 17 muscle names are mapped onto NOOP's 20 `LiftMuscle` cases. Where the source is
coarser than NOOP (one "shoulders" for three deltoid heads, one "abdominals" for abs and
obliques) the split is decided from the exercise name and its push/pull force, which is a
heuristic: a user can always reclassify an exercise after picking it.

Usage:
    python3 Tools/lift_catalog/build_lift_catalog.py            # downloads the pinned source
    python3 Tools/lift_catalog/build_lift_catalog.py exercises.json
"""
import json
import pathlib
import sys
import urllib.request

SOURCE_SHA = "f00c92c7dcf1216a928a52c3706c7ce8e2f71ed5"
SOURCE_URL = f"https://raw.githubusercontent.com/yuhonas/free-exercise-db/{SOURCE_SHA}/dist/exercises.json"

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = ROOT / "android/app/src/main/assets/lift/exercise_catalog.json"

# Resistance training only: stretches, plyometrics and cardio are not logged as sets of weight x reps.
CATEGORIES = {"strength", "powerlifting", "olympic weightlifting", "strongman"}

EQUIPMENT = {
    "barbell": "barbell",
    "dumbbell": "dumbbell",
    "cable": "cable",
    "machine": "machine",
    "body only": "bodyweight",
    "kettlebells": "kettlebell",
    "bands": "band",
    "e-z curl bar": "ezBar",
}

DIRECT = {
    "chest": "chest",
    "triceps": "triceps",
    "lats": "lats",
    "middle back": "upperBack",
    "traps": "traps",
    "biceps": "biceps",
    "forearms": "forearms",
    "quadriceps": "quads",
    "hamstrings": "hamstrings",
    "glutes": "glutes",
    "adductors": "adductors",
    "abductors": "abductors",
    "calves": "calves",
    "lower back": "lowerBack",
    "neck": "neck",
}

REAR = ("rear delt", "reverse fly", "reverse flye", "face pull", "rear lateral", "bent over lateral",
        "bent-over lateral", "reverse cable", "reverse machine")
SIDE = ("lateral raise", "side lateral", "upright row", "lateral delt", "side raise")
OBLIQUE = ("oblique", "side bend", "russian twist", "woodchop", "wood chop", "twist", "side plank",
           "windmill", "landmine 180")


def shoulder(name: str, force: str | None) -> str:
    n = name.lower()
    if any(k in n for k in REAR):
        return "rearDelts"
    if any(k in n for k in SIDE):
        return "sideDelts"
    return "rearDelts" if force == "pull" else "frontDelts"


def muscle(raw: str, name: str, force: str | None) -> str:
    if raw == "shoulders":
        return shoulder(name, force)
    if raw == "abdominals":
        return "obliques" if any(k in name.lower() for k in OBLIQUE) else "abs"
    return DIRECT[raw]


def build(source: list[dict]) -> list[dict]:
    out: dict[str, dict] = {}
    for x in source:
        if x.get("category") not in CATEGORIES:
            continue
        name = " ".join(x["name"].split())
        force = x.get("force")
        raw = [m for m in x.get("primaryMuscles", []) + x.get("secondaryMuscles", [])]
        mapped: list[str] = []
        for m in raw:
            v = muscle(m, name, force)
            if v not in mapped:
                mapped.append(v)
        if not mapped:
            continue
        key = name.lower()
        if key in out:
            continue
        out[key] = {
            "n": name,
            "p": mapped[0],
            "s": mapped[1:],
            "e": EQUIPMENT.get(x.get("equipment") or "", "other"),
        }
    return sorted(out.values(), key=lambda r: r["n"].lower())


def main() -> None:
    if len(sys.argv) > 1:
        source = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
    else:
        with urllib.request.urlopen(SOURCE_URL) as r:  # noqa: S310 (pinned https URL)
            source = json.loads(r.read().decode("utf-8"))
    rows = build(source)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    body = {"source": f"free-exercise-db@{SOURCE_SHA} (The Unlicense)", "exercises": rows}
    OUT.write_text(json.dumps(body, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
    print(f"wrote {len(rows)} exercises to {OUT.relative_to(ROOT)} ({OUT.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
