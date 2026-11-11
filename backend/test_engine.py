import unittest,sys,pathlib,tempfile,csv
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[1]/'ai'))
from engine import read_rows, DataError, FEATURES,TARGETS,sigmoid, market_events, probability_logit
from audit_market import audit
from market_backtest import lagged_macro
class Tests(unittest.TestCase):
 def test_sigmoid(self):self.assertAlmostEqual(sigmoid(0),.5)
 def test_macro_uses_prior_common_date_and_rejects_stale_values(self):
  names=("usdkrw","government_yield","corporate_yield")
  macro={name:[{"session":"2026-01-02",name:value},{"session":"2026-01-05",name:99}]
         for name,value in zip(names,(1400,3,4))}
  result=lagged_macro([{"session":"2026-01-05"},{"session":"2026-01-15"}],macro)
  self.assertEqual(result,{__import__('datetime').date(2026,1,5):[1400,3,1]})
 def test_calibration_logit_clips_endpoints(self):
  self.assertAlmostEqual(probability_logit(.5),0)
  self.assertLess(probability_logit(0),-10)
  self.assertGreater(probability_logit(1),10)
 def test_drawdown_and_unmatured_window(self):
  closes=[100,110,98,86,93,99]
  self.assertEqual(market_events(closes,0,5),(1,0))
  self.assertIsNone(market_events(closes,1,5))
 def test_audit_excludes_unmatured_windows(self):
  rows=[dict(session=f'2026-01-{i:02d}',close=100) for i in range(1,9)]
  rows[4]['close']=85
  targets=audit(rows)['targets']
  self.assertEqual(targets[0]['mature_windows'],3)
  self.assertEqual(targets[0]['positive_windows'],3)
  self.assertEqual(targets[0]['overlapping_window_clusters'],1)
  self.assertEqual(targets[1]['positive_windows'],0)
  self.assertEqual(targets[2]['mature_windows'],0)
 def test_missing_data_rejected(self):
  with tempfile.NamedTemporaryFile(mode='w+',suffix='.csv') as f:
   f.write('asof,available_at\n2026-01-01,2026-01-01\n');f.flush()
   with self.assertRaises(DataError):read_rows(f.name)
 def test_future_data_rejected(self):
  with tempfile.NamedTemporaryFile(mode='w+',suffix='.csv') as f:
   w=csv.DictWriter(f,fieldnames=['asof','available_at',*FEATURES,*TARGETS]);w.writeheader()
   w.writerow(dict(asof='2026-01-01',available_at='2026-02-01',**{k:'1' for k in FEATURES},**{k:'0' for k in TARGETS}));f.flush()
   with self.assertRaises(DataError):read_rows(f.name)
if __name__=='__main__':unittest.main()
