import copy
import datetime as dt
import json
import unittest
from collect import ROOT, months, series, collect_metric, validate

class CollectorTests(unittest.TestCase):
    def setUp(self):
        self.config=json.loads((ROOT/'config/indicators.json').read_text())
        self.m=self.config['indicators'][0]
    def row(self,time='202608',value='0.8'):
        return dict(STAT_CODE='901Y124',UNIT_NAME='%',ITEM_CODE1='MO3AA',ITEM_CODE2='AB',ITEM_CODE3=None,ITEM_CODE4=None,ITEM_NAME1='기업대출',TIME=time,DATA_VALUE=value)
    def body(self,rows):return dict(StatisticSearch=dict(list_total_count=len(rows),row=rows))
    def test_month_end(self):
        self.assertEqual(months(dt.date(2024,5,31),-3),dt.date(2024,2,29))
    def test_valid_config_and_new_monitor(self):
        new=copy.deepcopy(self.m);new.update(id='extra',weight=0);self.config['indicators'].append(new);validate(self.config)
    def test_invalid_total(self):
        self.m['weight']=.5
        with self.assertRaises(ValueError):validate(self.config)
    def test_duplicate_id(self):
        self.config['indicators'].append(copy.deepcopy(self.m))
        with self.assertRaises(ValueError):validate(self.config)
    def test_three_month_values_without_fabricated_release(self):
        r=collect_metric(self.m,dt.date(2026,11,11),lambda _:self.body([self.row('202605','0.7'),self.row()]))
        self.assertEqual(r['value'],.8);self.assertEqual(r['previous'],.7);self.assertIsNone(r['releaseDate'])
    def test_missing_previous_is_null(self):
        r=collect_metric(self.m,dt.date(2026,11,11),lambda _:self.body([self.row()]))
        self.assertEqual(r['status'],'ok');self.assertIsNone(r['previous'])
    def test_wrong_series_and_nonfinite_fail_closed(self):
        for change in ({'ITEM_CODE2':'DB'},{'DATA_VALUE':'NaN'},{'UNIT_NAME':'원'},{'TIME':'202901'}):
            row=self.row();row.update(change)
            r=collect_metric(self.m,dt.date(2026,11,11),lambda _:self.body([row]))
            self.assertEqual(r['status'],'missing');self.assertIsNone(r['value'])
    def test_duplicate_observation(self):
        r=collect_metric(self.m,dt.date(2026,11,11),lambda _:self.body([self.row(),self.row()]))
        self.assertEqual(r['status'],'missing')
    def test_pf_has_no_sample_fallback(self):
        def unavailable(_): raise OSError('official source unavailable')
        r=collect_metric(self.config['indicators'][-1],dt.date(2026,11,11),pf_get=unavailable)
        self.assertEqual(r['status'],'missing');self.assertIsNone(r['value'])
    def test_housing_yoy_uses_same_base_and_three_month_history(self):
        m=self.config['indicators'][2]
        def get(url):
            rows=[]
            for period,value in [('202506','100'),('202509','100'),('202606','98'),('202609','95')]:
                rows.append(dict(STAT_CODE='901Y149',UNIT_NAME='2026.06=100',ITEM_CODE1='00',ITEM_CODE2='a0',ITEM_NAME1='종합',TIME=period,DATA_VALUE=value))
            return self.body(rows)
        r=collect_metric(m,dt.date(2026,11,11),get)
        self.assertAlmostEqual(r['value'],-5);self.assertAlmostEqual(r['previous'],-2)
    def test_credit_intersects_dates_before_comparison(self):
        m=self.config['indicators'][3]
        def get(url):
            corporate='/010300000/' in url;old='202608' in url
            rows=[]
            dates=['20260807'] if old else ['20261109','20261110'] if corporate else ['20261109']
            for period in dates:
                rows.append(dict(STAT_CODE='817Y002',UNIT_NAME='연%',ITEM_CODE1='010300000' if corporate else '010200000',ITEM_NAME1='회사채(3년, AA-)' if corporate else '국고채(3년)',TIME=period,DATA_VALUE='4.0' if corporate else '3.3'))
            return self.body(rows)
        r=collect_metric(m,dt.date(2026,11,10),get)
        self.assertEqual(r['period'],'2026-11-09');self.assertEqual(r['previousPeriod'],'2026-08-07');self.assertAlmostEqual(r['value'],.7)
    def test_unknown_transform_rejected(self):
        self.m['source']['transform']='eval'
        with self.assertRaises(ValueError):validate(self.config)

if __name__=='__main__':unittest.main()
