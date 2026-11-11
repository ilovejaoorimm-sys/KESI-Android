"""Run a research-only, close-based baseline on actual ECOS observations."""
import argparse
import bisect
import datetime as dt
import json
import math
import pathlib
import statistics

from engine import calibration_rows, evaluate, fit, market_events, predict

FEATURES = ("return_5", "return_20", "volatility_20", "drawdown_60")
MACRO_FEATURES = ("usdkrw", "government_yield", "credit_spread")

def event_metrics(test, probabilities, threshold, horizon, closes, cutoff=.5):
    """Conservatively group overlapping positive outcome windows."""
    groups = []
    for (_, _, outcomes, _, index), probability in zip(test, probabilities):
        if not outcomes[threshold]:
            continue
        crossing = next(j for j in range(index+1, index+horizon+1)
                        if (closes[index]-closes[j])/closes[index] >= int(threshold)/100)
        if not groups or index >= groups[-1][0]:
            groups.append([index+horizon, []])
        groups[-1][0] = index+horizon
        if probability >= cutoff:
            groups[-1][1].append(crossing-index)
    lead = [max(days) for _, days in groups if days]
    return dict(overlapping_event_groups=len(groups), detected_groups=len(lead),
                group_detection_rate=len(lead)/len(groups) if groups else None,
                median_lead_sessions=statistics.median(lead) if lead else None)


def lagged_macro(observations, macro):
    """Use the latest common ECOS date strictly before the prediction close."""
    names = ("usdkrw", "government_yield", "corporate_yield")
    series = {}
    for name in names:
        values = {}
        for row in macro[name]:
            date = dt.date.fromisoformat(row["session"])
            value = row[name]
            if date in values or not math.isfinite(value):
                raise ValueError("Invalid or duplicate macro observation")
            values[date] = value
        series[name] = values
    common = sorted(set.intersection(*(set(series[name]) for name in names)))
    result = {}
    for row in observations:
        date = dt.date.fromisoformat(row["session"])
        index = bisect.bisect_left(common, date)-1
        if index < 0 or (date-common[index]).days > 7:
            continue
        fx, govt, corp = (series[name][common[index]] for name in names)
        result[date] = [fx, govt, corp-govt]
    return result


def backtest(observations, macro=None, include_macro=True):
    dates = [dt.date.fromisoformat(row["session"]) for row in observations]
    closes = [row["close"] for row in observations]
    if len(dates) < 240 or dates != sorted(set(dates)):
        raise ValueError("At least 240 unique, sorted sessions are required")
    if any(not math.isfinite(x) or x <= 0 for x in closes):
        raise ValueError("Invalid close")
    extra = lagged_macro(observations, macro) if macro is not None else None
    use_macro = extra is not None and include_macro
    feature_names = FEATURES + MACRO_FEATURES if use_macro else FEATURES
    results = {}
    for horizon in (5, 20, 60):
        rows = []
        for i in range(59, len(closes) - horizon):
            if extra is not None and dates[i] not in extra:
                continue
            change = [math.log(closes[j] / closes[j - 1]) for j in range(i - 19, i + 1)]
            features = [closes[i] / closes[i-5] - 1, closes[i] / closes[i-20] - 1,
                        statistics.pstdev(change), closes[i] / max(closes[i-59:i+1]) - 1]
            events = market_events(closes, i, horizon)
            if use_macro:
                features += extra[dates[i]]
            rows.append((dates[i], features, dict(zip(("10", "20"), events)), dates[i+horizon], i))
        for threshold in ("10", "20"):
            folds = []
            for lo, hi in ((.60,.70),(.70,.80),(.80,.90),(.90,1)):
                first, last = int(len(rows)*lo), int(len(rows)*hi)
                # A training label must have finished before the first test close.
                test = rows[first:last]
                if not test:
                    continue
                train = [row for row in rows[:first] if row[3] < test[0][0]]
                if len(train) < 80 or not test:
                    continue
                labels = [row[2][threshold] for row in train]
                if min(labels) == max(labels):
                    continue
                model = fit([row[:3] for row in train], threshold, iterations=200, features=feature_names)
                timed_test = test
                test = [row[:3] for row in timed_test]
                result = dict(start=test[0][0].isoformat(), end=test[-1][0].isoformat(),
                              **evaluate(model, test, statistics.mean(labels)))
                result.update(event_metrics(timed_test,[predict(model,row[1]) for row in test],
                                            threshold,horizon,closes))
                split = int(.75 * len(train))
                calibrating = [row[:3] for row in train[split:]]
                fitting = [row[:3] for row in train[:split] if row[3] < calibrating[0][0]]
                has_both = lambda block: len({row[2][threshold] for row in block}) == 2
                if min(len(fitting), len(calibrating)) >= 80 and has_both(fitting) and has_both(calibrating):
                    core = fit(fitting, threshold, iterations=200, features=feature_names)
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
            "features":feature_names,
            "limitations":["Official trading calendar not cross-checked",
                           "Research calibration only when both classes are available",
                           "No deployment approval"],
            "folds":results}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("history", type=pathlib.Path, nargs="+")
    parser.add_argument("--macro", type=pathlib.Path, nargs=3, metavar=("FX", "GOVT", "CORP"))
    parser.add_argument("--matched-close-only", action="store_true")
    args = parser.parse_args()
    rows = [row for path in args.history for row in json.loads(path.read_text())["observations"]]
    macro = None if args.macro is None else {
        name: json.loads(path.read_text())["observations"]
        for name, path in zip(("usdkrw","government_yield","corporate_yield"), args.macro)
    }
    if args.matched_close_only and macro is None:
        parser.error("--matched-close-only requires --macro")
    print(json.dumps(backtest(rows, macro, not args.matched_close_only), indent=2, allow_nan=False))
