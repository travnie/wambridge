"""Compare control timing logs and hand-measured audible latency.

Samples are never sent to the speaker. Neither callback timing nor a successful
command reply proves the audio has changed at the listener.
"""

from __future__ import annotations

import argparse
import csv
import io
import math
import re
import statistics
from collections import defaultdict
from pathlib import Path

COMMANDS = frozenset({"pause", "resume", "volume", "release", "discard", "sleep"})
METRICS = frozenset({"dispatch_ms", "audible_ms"})
TIMING_LINE = re.compile(
    r"\bWAMBRIDGE CONTROL_TIMING command=(pause|resume|volume|release|discard|sleep)"
    r" dispatch_ms=([0-9]+(?:\.[0-9]+)?)\b"
)
Samples = dict[tuple[str, str], list[float]]


def read_samples(text: str) -> Samples:
    """Read helper logs or a CSV with command,metric,ms columns."""
    samples: Samples = defaultdict(list)
    stripped = text.lstrip("\ufeff \t\r\n")
    if stripped.startswith("command,"):
        reader = csv.DictReader(io.StringIO(stripped))
        if not {"command", "metric", "ms"}.issubset(reader.fieldnames or []):
            raise ValueError("CSV needs command,metric,ms columns")
        for number, row in enumerate(reader, start=2):
            command = (row["command"] or "").strip()
            metric = (row["metric"] or "").strip()
            if command not in COMMANDS or metric not in METRICS:
                raise ValueError(f"Invalid command or metric at CSV row {number}")
            try:
                ms = float(row["ms"] or "")
            except ValueError as exc:
                raise ValueError(f"Invalid duration at CSV row {number}") from exc
            if not math.isfinite(ms) or ms < 0:
                raise ValueError(f"Invalid duration at CSV row {number}")
            samples[(command, metric)].append(ms)
    else:
        for match in TIMING_LINE.finditer(text):
            samples[(match.group(1), "dispatch_ms")].append(float(match.group(2)))
    if not samples:
        raise ValueError("No control timings found")
    return dict(samples)


def stats(values: list[float]) -> tuple[int, float, float]:
    """Count, median, nearest-rank p95; no extrapolation for tiny samples."""
    ordered = sorted(values)
    return (
        len(ordered),
        statistics.median(ordered),
        ordered[math.ceil(0.95 * len(ordered)) - 1],
    )


def comparison_csv(before: Samples, after: Samples) -> str:
    """Negative median delta means the candidate is faster."""
    output = io.StringIO()
    writer = csv.writer(output, lineterminator="\n")
    writer.writerow(
        [
            "command", "metric", "before_n", "before_median_ms", "before_p95_ms",
            "after_n", "after_median_ms", "after_p95_ms", "delta_median_ms",
        ]
    )
    for command, metric in sorted(before.keys() | after.keys()):
        b = stats(before[(command, metric)]) if (command, metric) in before else None
        a = stats(after[(command, metric)]) if (command, metric) in after else None
        writer.writerow(
            [
                command, metric,
                b[0] if b else 0, f"{b[1]:.3f}" if b else "",
                f"{b[2]:.3f}" if b else "",
                a[0] if a else 0, f"{a[1]:.3f}" if a else "",
                f"{a[2]:.3f}" if a else "",
                f"{a[1] - b[1]:.3f}" if b and a else "",
            ]
        )
    return output.getvalue()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Compare native helper callback and manual audible-control latency"
    )
    parser.add_argument("before", type=Path, help="Baseline helper log or manual CSV")
    parser.add_argument("after", type=Path, help="Candidate helper log or manual CSV")
    args = parser.parse_args(argv)
    try:
        before = read_samples(args.before.read_text(encoding="utf-8-sig"))
        after = read_samples(args.after.read_text(encoding="utf-8-sig"))
    except (OSError, ValueError) as exc:
        parser.error(str(exc))
    print(comparison_csv(before, after), end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
