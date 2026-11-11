"""Count mature market labels without treating overlapping windows as events."""
import argparse
import datetime as dt
import json
import pathlib

from engine import market_events


def audit(observations):
    sessions = [dt.date.fromisoformat(row["session"]) for row in observations]
    if not sessions or sessions != sorted(set(sessions)):
        raise ValueError("Expected unique, chronological market dates")
    closes = [row["close"] for row in observations]
    results = []
    for horizon in (5, 20, 60):
        labels = [market_events(closes, i, horizon) for i in range(len(closes) - horizon)]
        for index, threshold in enumerate((10, 20)):
            positives = [i for i, label in enumerate(labels) if label[index]]
            # Group positive windows with overlapping outcome intervals. This is
            # a coverage diagnostic, not proof of independent market events.
            clusters = sum(i == 0 or p - positives[i - 1] >= horizon
                           for i, p in enumerate(positives))
            results.append({"horizon": horizon, "threshold": threshold,
                            "mature_windows": len(labels), "positive_windows": len(positives),
                            "overlapping_window_clusters": clusters})
    return {"first_session": sessions[0].isoformat(), "last_session": sessions[-1].isoformat(),
            "observations": len(sessions), "targets": results,
            "status": "research_only_calendar_not_verified"}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("history", type=pathlib.Path)
    args = parser.parse_args()
    print(json.dumps(audit(json.loads(args.history.read_text())["observations"]), indent=2))
