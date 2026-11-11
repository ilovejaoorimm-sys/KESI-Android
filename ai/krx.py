"""Download official KOSPI index observations using a server-side KRX key."""
import datetime as dt
import json
import math
import os
from urllib.request import Request, build_opener, HTTPRedirectHandler

URL = "https://data-dbg.krx.co.kr/svc/apis/idx/kospi_dd_trd"


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def observation(day: dt.date):
    key = os.environ.get("KRX_AUTH_KEY")
    if not key:
        raise RuntimeError("KRX_AUTH_KEY 미설정")
    stamp = day.strftime("%Y%m%d")
    request = Request(f"{URL}?basDd={stamp}", headers={"AUTH_KEY": key})
    with build_opener(NoRedirect()).open(request, timeout=20) as response:
        data = response.read(1_000_001)
    if len(data) > 1_000_000:
        raise ValueError("KRX 응답 크기 초과")
    rows = json.loads(data)["OutBlock_1"]
    if rows == []:
        return None
    matching = [row for row in rows if row.get("IDX_NM") == "코스피"]
    if len(matching) != 1 or matching[0].get("BAS_DD") != stamp:
        raise ValueError("KRX 코스피 계열 또는 날짜 불일치")
    row = matching[0]
    close, turnover = (float(row[field].replace(",", "")) for field in ("CLSPRC_IDX", "ACC_TRDVAL"))
    if not math.isfinite(close) or not math.isfinite(turnover) or close <= 0 or turnover < 0:
        raise ValueError("KRX 수치 검증 실패")
    return {"session": day.isoformat(), "close": close, "turnover": turnover}
