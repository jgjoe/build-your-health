"""Generate the V3 raw-data manifest; never upload or stage the observations."""
import hashlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "remake/loadtest/results/v3-raw-sha256.txt"


def main():
    paths = []
    for base in ("remake/perf/results", "remake/loadtest/results"):
        for run in (ROOT / base).glob("v3-*"):
            if run.is_dir():
                for path in run.rglob("*"):
                    if path.is_file() and (
                        path.name.startswith("raw")
                        or path.suffix == ".jfr"
                        or path.name.endswith("samples.json.gz")
                    ):
                        paths.append(path)
    lines = [
        "# V3 raw observations are local, not committed or uploaded.",
        "# Format: sha256  project-root-relative-path  bytes",
    ]
    for path in sorted(paths):
        digest = hashlib.sha256()
        with path.open("rb") as source:
            for block in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(block)
        lines.append(f"{digest.hexdigest()}  {path.relative_to(ROOT).as_posix()}  {path.stat().st_size}")
    OUTPUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"{len(paths)} raw files; manifest={OUTPUT}")


if __name__ == "__main__":
    main()
