"""금융위원회·금융감독원 공동 발표에서 전 금융권 PF대출 연체율을 수집한다.

목록과 상세 페이지를 모두 금융위원회 공식 HTTPS 주소에서 확인한다. 제목, 발표일,
분기, 단위와 PF대출 행을 검증하지 못하면 값을 반환하지 않고 예외를 발생시킨다.
"""
import datetime as dt
import calendar
import math
import re
import urllib.parse
import urllib.request
from html.parser import HTMLParser

LIST_URL = (
    'https://www.fsc.go.kr/no010101?srchKey=sj&srchText='
    + urllib.parse.quote('부동산')
)
ALLOWED_HOSTS = {'fsc.go.kr', 'www.fsc.go.kr'}
MAX_BYTES = 2_000_000


def _official_url(url, detail=False):
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme != 'https' or parsed.hostname not in ALLOWED_HOSTS:
        return False
    if parsed.username or parsed.password or parsed.port not in (None, 443):
        return False
    pattern = r'/no010101/\d+' if detail else r'/no010101/?'
    return re.fullmatch(pattern, parsed.path.rstrip('/') if detail else parsed.path) is not None


def get_html(url):
    if not _official_url(url, detail=bool(re.fullmatch(r'/no010101/\d+', urllib.parse.urlparse(url).path))):
        raise ValueError('금융위원회 공식 보도자료 URL이 아님')
    request = urllib.request.Request(
        url, headers={'User-Agent': 'KESI-feed/3.1 (+official PF statistics)'}
    )
    with urllib.request.urlopen(request, timeout=25) as response:
        if not _official_url(response.geturl(), detail=bool(re.fullmatch(r'/no010101/\d+', urllib.parse.urlparse(response.geturl()).path))):
            raise ValueError('비공식 사이트로 리다이렉트')
        raw = response.read(MAX_BYTES + 1)
    if len(raw) > MAX_BYTES:
        raise ValueError('공식 자료 크기 제한 초과')
    return raw.decode('utf-8')


class OfficialPage(HTMLParser):
    def __init__(self):
        super().__init__()
        self.parts = []
        self.links = []
        self._href = None
        self._link_parts = []
        self.rows = []
        self._row = None
        self._cell = None

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == 'a':
            self._href = attrs.get('href')
            self._link_parts = []
        elif tag == 'tr':
            self._row = []
        elif tag in ('td', 'th') and self._row is not None:
            self._cell = []

    def handle_data(self, data):
        self.parts.append(data)
        if self._href is not None:
            self._link_parts.append(data)
        if self._cell is not None:
            self._cell.append(data)

    def handle_endtag(self, tag):
        if tag == 'a' and self._href is not None:
            self.links.append((self._href, _space(' '.join(self._link_parts))))
            self._href = None
            self._link_parts = []
        elif tag in ('td', 'th') and self._cell is not None:
            self._row.append(_space(' '.join(self._cell)))
            self._cell = None
        elif tag == 'tr' and self._row is not None:
            if self._row:
                self.rows.append(self._row)
            self._row = None

    @property
    def text(self):
        return _space(' '.join(self.parts))


def _space(value):
    return re.sub(r'\s+', ' ', value).strip()


def _parse_page(html):
    if not isinstance(html, str) or len(html.encode('utf-8')) > MAX_BYTES:
        raise ValueError('공식 자료 크기 제한 초과')
    parser = OfficialPage()
    parser.feed(html)
    return parser


def discover_releases(fetch=get_html):
    page = _parse_page(fetch(LIST_URL))
    urls = []
    for href, title in page.links:
        compact = re.sub(r'\s+', '', title).upper()
        if 'PF' not in compact or '상황점검회의' not in compact:
            continue
        url = urllib.parse.urljoin(LIST_URL, href)
        parsed = urllib.parse.urlparse(url)
        if parsed.scheme != 'https' or parsed.hostname not in ALLOWED_HOSTS:
            continue
        canonical = f'https://www.fsc.go.kr{parsed.path}'
        if _official_url(canonical, detail=True) and canonical not in urls:
            urls.append(canonical)
        if len(urls) >= 12:
            break
    if not urls:
        raise ValueError('금융위원회 PF 공식 발표 목록 확인 실패')
    return urls


_PERIOD = re.compile(
    r"(?:\(|\[)?[’‘'`]?(\d{2,4})[.\s년]*(\d{1,2})(?:월)?\s*말(?:\)|\])?"
)
_NUMBER = re.compile(r'^[+△▲▼-]?\s*(\d+(?:\.\d+)?)$')


def _period(cell):
    match = _PERIOD.search(cell)
    if not match:
        return None
    year = int(match.group(1))
    if year < 100:
        year += 2000
    month = int(match.group(2))
    if year < 2000 or year > 2100 or month not in (3, 6, 9, 12):
        return None
    return f'{year:04d}-{month:02d}'


def _value(cell):
    match = _NUMBER.fullmatch(_space(cell).replace(',', ''))
    if not match:
        return None
    value = float(match.group(1))
    return value if math.isfinite(value) and 0 <= value <= 100 else None


def _table_series(rows):
    found = []
    for index, header in enumerate(rows):
        periods = {i: _period(cell) for i, cell in enumerate(header)}
        periods = {i: period for i, period in periods.items() if period}
        if len(periods) < 2:
            continue
        for row in rows[index + 1:index + 5]:
            if not row or re.sub(r'\s+', '', row[0]).upper() != 'PF대출':
                continue
            values = {}
            for cell_index, period in periods.items():
                if cell_index < len(row):
                    value = _value(row[cell_index])
                    if value is not None:
                        values[period] = value
            if len(values) >= 2:
                found.append(values)
    return found


def _inline_series(text):
    found = []
    anchor = re.compile(r'PF\s*대출\s*연체율\s*\(\s*%\s*\)\s*:', re.I)
    pair = re.compile(_PERIOD.pattern + r'\s*(\d+(?:\.\d+)?)')
    for match in anchor.finditer(text):
        values = {}
        for item in pair.finditer(text[match.end():match.end() + 1400]):
            period = _period(item.group(0))
            value = float(item.group(3))
            if period and math.isfinite(value) and 0 <= value <= 100:
                if period in values and values[period] != value:
                    raise ValueError('동일 분기 PF 연체율 충돌')
                values[period] = value
        if len(values) >= 2:
            found.append(values)
    return found


def parse_release(html, url, today=None):
    if not _official_url(url, detail=True):
        raise ValueError('금융위원회 공식 보도자료 URL이 아님')
    today = today or dt.date.today()
    page = _parse_page(html)
    text = page.text
    if ('PF' not in text or '연체율' not in text or '금융감독원' not in text
            or '부동산' not in text or '상황 점검회의' not in text):
        raise ValueError('금융위·금감원 PF 공동 공식자료 검증 실패')
    date_match = re.search(r'\b(20\d{2}-\d{2}-\d{2})\s*조회수', text)
    if not date_match:
        raise ValueError('공식 발표일 확인 실패')
    release_date = dt.date.fromisoformat(date_match.group(1))
    if release_date > today:
        raise ValueError('미래 발표자료')

    series = _table_series(page.rows) + _inline_series(text)
    if not series:
        raise ValueError('PF대출 공식 연체율 계열 검증 실패')
    observations = {}
    for candidate in series:
        for period, value in candidate.items():
            if period in observations and observations[period] != value:
                raise ValueError('동일 분기 PF 연체율 충돌')
            observations[period] = value
    latest = max(observations)
    latest_month = dt.date.fromisoformat(latest + '-01')
    latest_year, latest_number = map(int, latest.split('-'))
    period_end = dt.date(latest_year, latest_number, calendar.monthrange(latest_year, latest_number)[1])
    if latest_month > today.replace(day=1) or release_date < period_end:
        raise ValueError('기준분기와 발표일 불일치')
    year, month = map(int, latest.split('-'))
    previous_month = month - 3
    if previous_month <= 0:
        year -= 1
        previous_month += 12
    previous = f'{year:04d}-{previous_month:02d}'
    if previous not in observations:
        raise ValueError('정확히 3개월 전 PF 자료 없음')
    return {
        'period': latest,
        'value': observations[latest],
        'previousPeriod': previous,
        'previous': observations[previous],
        'releaseDate': release_date.isoformat(),
        'sourceUrl': url,
    }


def collect_pf(today=None, fetch=get_html):
    today = today or dt.date.today()
    valid = []
    errors = []
    for url in discover_releases(fetch):
        try:
            valid.append(parse_release(fetch(url), url, today))
        except Exception as exc:
            errors.append(str(exc))
    if not valid:
        raise ValueError('공식 PF 통계 미수집: ' + ('; '.join(errors[:2]) or '검증 자료 없음'))
    latest_period = max(item['period'] for item in valid)
    latest = [item for item in valid if item['period'] == latest_period]
    signatures = {(item['value'], item['previous'], item['previousPeriod']) for item in latest}
    if len(signatures) != 1:
        raise ValueError('최신 공식 PF 통계 간 값 충돌')
    chosen = max(latest, key=lambda item: item['releaseDate'])
    chosen.update(
        status='ok',
        source='금융위원회·금융감독원 / 전 금융권 PF대출 연체율',
        reason='금융위원회 보도자료 목록에서 최신 공동 발표를 자동 확인',
    )
    return chosen
