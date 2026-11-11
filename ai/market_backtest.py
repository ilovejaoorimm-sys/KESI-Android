"""Run a research-only, close-based baseline on actual ECOS observations."""
import argparse
import datetime as dt
import json
import math
import pathlib
import statistics

from engine import calibration_rows, evaluate, fit, market_events

FEATURES = ("return_5", "return_20", "volatility_20", "drawdown_60")


def backtest(observations):
    dates = [dt.date.fromisoformat(row["session"]) for row in observations]
    closes = [row["close"] for row in observations]
    if len(dates) < 240 or dates != sorted(set(dates)):
        raise ValueError("At least 240 unique, sorted sessions are required")
    if any(not math.isfinite(x) or x <= 0 for x in closes):
        raise ValueError("Invalid close")
    results = {}
    for horizon in (5, 20, 60):
        rows = []
        for i in range(59, len(closes) - horizon):
            change = [math.log(closes[j] / closes[j - 1]) for j in range(i - 19, i + 1)]
            features = [closes[i] / closes[i-5] - 1, closes[i] / closes[i-20] - 1,
                        statistics.pstdev(change), closes[i] / max(closes[i-59:i+1]) - 1]
            events = market_events(closes, i, horizon)
            rows.append((dates[i], features, dict(zip(("10", "20"), events))))
        for threshold in ("10", "20"):
            folds = []
            for lo, hi in ((.60,.70),(.70,.80),(.80,.90),(.90,1)):
                first, last = int(len(rows)*lo), int(len(rows)*hi)
                # A training label must have finished before the first test close.
                train, test = rows[:max(0, first-horizon)], rows[first:last]
                if len(train) < 80 or not test:
                    continue
                labels = [row[2][threshold] for row in train]
                if min(labels) == max(labels):
                    continue
                model = fit(train, threshold, iterations=200, features=FEATURES)
                result = dict(start=test[0][0].isoformat(), end=test[-1][0].isoformat(),
                              **evaluate(model, test, statistics.mean(labels)))
                split = int(.75 * len(train))
                fitting, calibrating = train[:max(0, split-horizon)], train[split:]
                has_both = lambda block: len({row[2][threshold] for row in block}) == 2
                if min(len(fitting), len(calibrating)) >= 80 and has_both(fitting) and has_both(calibrating):
                    core = fit(fitting, threshold, iterations=200, features=FEATURES)
                    calibration = fit(calibration_rows(core, calibrating), threshold,
                                      iterations=200, features=("logit",))
                    result["calibrated"] = evaluate(calibration, calibration_rows(core, test),
                                                     statistics.mean(labels))
                    result["same_fit_uncalibrated"] = evaluate(core, test, statistics.mean(labels))
                else:
                    result["calibrated"] = None
                folds.append(result)
            results[f"{horizon}d_{threshold}pct"] = folds
    return {"status":"research_only", "observations":len(dates),
            "first_session":dates[0].isoformat(), "last_session":dates[-1].isoformat(),
            "limitations":["ECOS close only","Official trading calendar not cross-checked",
                           "Research calibration only when both classes are available",
                           "No deployment approval"],
            "folds":results}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("history", type=pathlib.Path, nargs="+")
    args = parser.parse_args()
    rows = [row for path in args.history for row in json.loads(path.read_text())["observations"]]
    print(json.dumps(backtest(rows), indent=2, allow_nan=False))
