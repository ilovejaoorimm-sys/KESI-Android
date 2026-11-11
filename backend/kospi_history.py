"""Official Bank of Korea daily KOSPI closes for research."""
import argparse
import calendar
import concurrent.futures
import datetime as dt
import json
import pathlib
import time
from urllib.error import URLError

from collect import series

SOURCE = dict(table="802Y001", cycle="D", items=["0001000"],
              expected="KOSPI지수", unit="1980.01.04=100 ")


def history(start, end, cache=None):
    if start > end or end > dt.date.today():
        raise ValueError("Invalid history dates")
    ranges = []
    while start <= end:
        month_end = start.replace(day=calendar.monthrange(start.year, start.month)[1])
        stop = min(month_end, end)
        ranges.append((start, stop))
        start = stop + dt.timedelta(days=1)

    def read_month(bounds):
        first, last = bounds
        checkpoint = cache / f"{first}_{last}.json" if cache else None
        if checkpoint and checkpoint.exists():
            return json.loads(checkpoint.read_text())
        for attempt in range(3):
            try:
                values, _ = series(SOURCE, first.strftime("%Y%m%d"), last.strftime("%Y%m%d"))
                break
            except (URLError, TimeoutError):
                if attempt == 2:
                    raise
                time.sleep(2 ** attempt)
        observations = []
        for stamp, close in sorted(values.items()):
            session = dt.datetime.strptime(stamp, "%Y%m%d").date()
            if close <= 0:
                raise ValueError("Invalid KOSPI close")
            observations.append({"session": session.isoformat(), "close": close})
        if checkpoint:
            temporary = checkpoint.with_suffix(".tmp")
            temporary.write_text(json.dumps(observations, allow_nan=False))
            temporary.replace(checkpoint)
        return observations

    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
        observations = [row for month in executor.map(read_month, ranges) for row in month]
    return {"source": "한국은행 ECOS 802Y001/0001000", "observations": observations}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("start", type=dt.date.fromisoformat)
    parser.add_argument("end", type=dt.date.fromisoformat)
    parser.add_argument("--cache", type=pathlib.Path)
    args = parser.parse_args()
    if args.cache:
        args.cache.mkdir(parents=True, exist_ok=True)
    print(json.dumps(history(args.start, args.end, args.cache), ensure_ascii=False, allow_nan=False))
