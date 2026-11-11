import datetime as dt
import unittest

from pf_collector import LIST_URL, collect_pf, discover_releases, parse_release


def listing(*items):
    return '<html>' + ''.join(
        f'<a href="/no010101/{number}">부동산 프로젝트 금융(PF) 상황 점검회의 개최</a>'
        for number in items
    ) + '</html>'


def release(date, periods, values, row='PF대출', agency='금융위원회, 금융감독원'):
    headers = ''.join(f'<th>’{period[2:4]}.{int(period[5:])}말</th>' for period in periods)
    cells = ''.join(f'<td>{value}</td>' for value in values)
    series = ' → '.join(
        f'(’{period[2:4]}.{int(period[5:])}말) {value}'
        for period, value in zip(periods, values)
    )
    return (
        f'<html><h1>부동산 프로젝트 금융(PF) 상황 점검회의</h1>'
        f'<p>{date} 조회수 : 10</p><p>{agency}</p>'
        f'<p>PF대출 연체율(%): {series}</p>'
        f'<table><tr><th>구분</th>{headers}</tr><tr><td>{row}</td>{cells}</tr></table></html>'
    )


class PFCollectorTests(unittest.TestCase):
    def test_inline_series_stops_before_next_statistic(self):
        html = release('2026-10-13', ['2026-03', '2026-06'], [4.65, 4.56])
        html = html.replace('4.56</p>', '4.56 다른 통계 (’26.6말) 15.1</p>')
        result = parse_release(html, 'https://www.fsc.go.kr/no010101/87919', dt.date(2026, 11, 11))
        self.assertEqual(result['value'], 4.56)

    def test_discovers_only_official_pf_meeting_details(self):
        html = listing('87919') + '<a href="https://evil.example/no010101/1">PF 상황 점검회의</a>'
        self.assertEqual(
            discover_releases(lambda url: html),
            ['https://www.fsc.go.kr/no010101/87919'],
        )

    def test_known_joint_release_is_parsed(self):
        url = 'https://www.fsc.go.kr/no010101/87919'
        html = release('2026-10-13', ['2025-12', '2026-03', '2026-06'], [3.88, 4.65, 4.56])
        result = parse_release(html, url, dt.date(2026, 11, 11))
        self.assertEqual(result['value'], 4.56)
        self.assertEqual(result['previous'], 4.65)
        self.assertEqual(result['period'], '2026-06')
        self.assertEqual(result['releaseDate'], '2026-10-13')

    def test_new_quarter_is_selected_automatically(self):
        pages = {
            LIST_URL: listing('90002', '87919'),
            'https://www.fsc.go.kr/no010101/87919': release(
                '2026-10-13', ['2026-03', '2026-06'], [4.65, 4.56]
            ),
            'https://www.fsc.go.kr/no010101/90002': release(
                '2027-01-15', ['2026-06', '2026-09'], [4.56, 4.70]
            ),
        }
        result = collect_pf(today=dt.date(2027, 2, 1), fetch=pages.__getitem__)
        self.assertEqual((result['period'], result['value']), ('2026-09', 4.70))
        self.assertEqual((result['previousPeriod'], result['previous']), ('2026-06', 4.56))
        self.assertEqual(result['sourceUrl'], 'https://www.fsc.go.kr/no010101/90002')

    def test_wrong_row_or_missing_agency_is_rejected(self):
        url = 'https://www.fsc.go.kr/no010101/87919'
        wrong = release('2026-10-13', ['2026-03', '2026-06'], [31.88, 32.20], row='토담대')
        wrong = wrong.replace('PF대출 연체율', '토담대 연체율')
        with self.assertRaises(ValueError):
            parse_release(wrong, url, dt.date(2026, 11, 11))
        with self.assertRaises(ValueError):
            parse_release(
                release('2026-10-13', ['2026-03', '2026-06'], [4.65, 4.56], agency='금융위원회'),
                url,
                dt.date(2026, 11, 11),
            )

    def test_non_quarter_and_missing_previous_fail_closed(self):
        url = 'https://www.fsc.go.kr/no010101/87919'
        with self.assertRaises(ValueError):
            parse_release(
                release('2026-10-13', ['2026-06', '2026-08'], [4.56, 4.70]),
                url,
                dt.date(2026, 11, 11),
            )
        with self.assertRaises(ValueError):
            parse_release(
                release('2026-10-13', ['2025-12', '2026-06'], [3.88, 4.56]),
                url,
                dt.date(2026, 11, 11),
            )

    def test_list_failure_never_uses_embedded_fallback_number(self):
        def failed(_):
            raise OSError('network unavailable')
        with self.assertRaises(OSError):
            collect_pf(today=dt.date(2026, 11, 11), fetch=failed)


if __name__ == '__main__':
    unittest.main()
