"""Official-source feed builder. No synthetic data, eval, or third-party numbers."""
import calendar
import concurrent.futures
import datetime as dt
import json
import math
import pathlib
import re
import urllib.request
from zoneinfo import ZoneInfo

ROOT = pathlib.Path(__file__).resolve().parents[1]
BASE = 'https://ecos.bok.or.kr/api/StatisticSearch/sample/json/kr/'

def months(d, n):
    y, m = divmod(d.year * 12 + d.month - 1 + n, 12)
    return dt.date(y, m + 1, min(d.day, calendar.monthrange(y, m + 1)[1]))

def get_json(url):
    req = urllib.request.Request(url, headers={'User-Agent': 'KESI-feed/3.0'})
    with urllib.request.urlopen(req, timeout=20) as response:
        raw = response.read(2_000_001)
    if len(raw) > 2_000_000:
        raise ValueError('응답 크기 초과')
    return json.loads(raw)

def series(src, start, end, get=get_json):
    values = {}; unit = src['unit']; table = src['table']; codes = src['items']
    for first in range(1, 102, 10):
        url = BASE + f'{first}/{first+9}/{table}/{src["cycle"]}/{start}/{end}/' + '/'.join(codes) + '/'
        data = get(url)
        if 'RESULT' in data:
            if data['RESULT']['CODE'] == 'INFO-200': break
            raise ValueError('공식 API 오류: ' + data['RESULT']['CODE'])
        body = data['StatisticSearch']
        for row in body['row']:
            if row['STAT_CODE'] != table or row['UNIT_NAME'] != unit:
                raise ValueError('통계표 또는 단위 불일치')
            for i in range(4):
                if (row.get(f'ITEM_CODE{i+1}') or '') != (codes[i] if i < len(codes) else ''):
                    raise ValueError('계열 불일치')
            if src['expected'] not in row['ITEM_NAME1']:
                raise ValueError('항목명 불일치')
            t = row['TIME']
            if not start <= t <= end or t in values: raise ValueError('기간 또는 중복 관측 오류')
            if row['DATA_VALUE'] in (None, '', '-'): continue
            v = float(str(row['DATA_VALUE']).replace(',', ''))
            if not math.isfinite(v): raise ValueError('유효하지 않은 값')
            values[t] = v
        if first + 9 >= body['list_total_count']: break
    else: raise ValueError('과도한 응답 페이지')
    if not values: raise ValueError('공식 원자료 없음')
    return values, url

def validate(config):
    if not isinstance(config.get('method'), str) or not 1 <= len(config['method']) <= 200: raise ValueError('계산 기준명')
    if config['schema'] != 1 or type(config['revision']) is not int or config['revision'] < 1: raise ValueError('설정 버전')
    if not 1 <= len(config['indicators']) <= 40: raise ValueError('지표 개수')
    ids = set(); total = 0
    for m in config['indicators']:
        if not re.fullmatch('[a-z][a-z0-9_]{0,39}', m['id']) or m['id'] in ids: raise ValueError('지표 ID 중복/오류')
        ids.add(m['id'])
        for key in ('weight', 'low', 'high'):
            if not isinstance(m[key], (int, float)) or not math.isfinite(m[key]): raise ValueError('숫자 오류')
        if not 0 <= m['weight'] <= 1 or m['high'] <= m['low']: raise ValueError('점수 기준 오류')
        if m['direction'] not in ('up', 'down') or type(m['maxAgeDays']) is not int or not 1 <= m['maxAgeDays'] <= 400: raise ValueError('방향/유효기간 오류')
        if not m['name'] or len(m['name']) > 100: raise ValueError('이름 오류')
        total += m['weight']
        source = m['source']
        if source['adapter'] not in ('ecos', 'unavailable'): raise ValueError('미지원 수집기')
        if source['adapter'] == 'ecos':
            for s in [source] + ([source['subtract']] if 'subtract' in source else []):
                if s['cycle'] not in ('D','M') or not re.fullmatch('[0-9A-Z]+', s['table']): raise ValueError('계열 설정 오류')
                if not 1 <= len(s['items']) <= 4 or any(not re.fullmatch('[0-9A-Za-z_]+', x) for x in s['items']): raise ValueError('항목 설정 오류')
            if source.get('transform','raw') not in ('raw','yoy','spread'): raise ValueError('미지원 변환')
            if source.get('transform') == 'spread' and source['cycle'] != source['subtract']['cycle']: raise ValueError('주기 불일치')
            if source.get('transform') == 'yoy' and source['cycle'] != 'M': raise ValueError('전년비 주기')
    if abs(total - 1) > 1e-6: raise ValueError('종합 가중치 합은 1이어야 함')

def collect_metric(m, today, get=get_json):
    result = {k: m[k] for k in ('id','name','unit','weight','low','high','direction','maxAgeDays')}
    result.update(status='missing', value=None, previous=None, period=None, previousPeriod=None,
                  releaseDate=None, source=m['source']['name'], sourceUrl=None, reason='')
    src = m['source']
    try:
        if src['adapter'] == 'unavailable': raise ValueError(src['reason'])
        monthly = src['cycle'] == 'M'; fmt = '%Y%m' if monthly else '%Y%m%d'
        end = today.replace(day=1) - dt.timedelta(days=1) if monthly else today
        begin = months(end, -20) if src.get('transform') == 'yoy' else months(end, -8) if monthly else end-dt.timedelta(days=10)
        data, url = series(src, begin.strftime(fmt), end.strftime(fmt), get)
        if src.get('transform') == 'spread':
            second, _ = series(src['subtract'], begin.strftime(fmt), end.strftime(fmt), get)
            data = {k:v-second[k] for k,v in data.items() if k in second}
        if not data: raise ValueError('동일 날짜 비교자료 없음')
        latest = max(data); date = dt.datetime.strptime(latest, fmt).date(); previous_date = months(date, -3)
        pk = previous_date.strftime(fmt); value = data[latest]; previous = data.get(pk)
        if src.get('transform') == 'yoy':
            def yoy(k):
                prev = months(dt.datetime.strptime(k,fmt).date(),-12).strftime(fmt)
                return (data[k]/data[prev]-1)*100 if k in data and data[k]>0 and data.get(prev,0)>0 else None
            value, previous = yoy(latest), yoy(pk)
            if value is None: raise ValueError('동일 기준년 전년비 자료 부족')
        elif not monthly:
            try:
                past, _ = series(src,(previous_date-dt.timedelta(days=7)).strftime(fmt),pk,get)
                if src.get('transform') == 'spread':
                    second, _ = series(src['subtract'],(previous_date-dt.timedelta(days=7)).strftime(fmt),pk,get)
                    past = {k:v-second[k] for k,v in past.items() if k in second}
                pk = max(past); previous = past[pk]
            except Exception: previous = None
        def show(k):
            d = dt.datetime.strptime(k, fmt); return d.strftime('%Y-%m' if monthly else '%Y-%m-%d')
        result.update(status='ok',value=value,previous=previous,period=show(latest),previousPeriod=show(pk) if previous is not None else None,sourceUrl=url,
                      reason='공식 ECOS 원자료 · 발표일 미제공')
    except Exception as e:
        result['reason'] = str(e)[:200]
    return result

def build(config, today=None, get=get_json):
    validate(config)
    now = dt.datetime.now(ZoneInfo('Asia/Seoul')); today = today or now.date()
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        rows = list(pool.map(lambda m:collect_metric(m,today,get),config['indicators']))
    return dict(schema=1, revision=config['revision'], method=config['method'], generatedAt=now.isoformat(), indicators=rows)

if __name__ == '__main__':
    config = json.loads((ROOT/'config/indicators.json').read_text())
    feed = build(config)
    dest = ROOT/'data/feed.json'; dest.parent.mkdir(exist_ok=True)
    temp = dest.with_suffix('.tmp'); temp.write_text(json.dumps(feed,ensure_ascii=False,indent=2,allow_nan=False)+'\n'); temp.replace(dest)
    print('Collected',sum(m['status']=='ok' for m in feed['indicators']),'/',len(feed['indicators']))
