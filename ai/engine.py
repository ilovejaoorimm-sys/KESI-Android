"""KESI 4.0 proof-of-concept. No fabricated forecasts. Standard library only."""
import csv, datetime as dt, json, math, pathlib, statistics

FEATURES = ('credit_spread','pf_delinquency','corporate_delinquency','unemployment','housing_yoy','usdkrw','kospi_return_20d','kospi_volatility_20d','foreign_flow_20d')
TARGETS = ('crisis_3m','crisis_6m','crisis_12m') + tuple(
    f'drawdown_{days}d_{threshold}' for days in (5,20,60) for threshold in (10,20)
)

def market_events(closes, index, horizon):
    """Drawdown from the running peak beginning at the forecast close."""
    if index + horizon >= len(closes):
        return None
    window = closes[index:index + horizon + 1]
    if any(not math.isfinite(price) or price <= 0 for price in window):
        raise ValueError('Invalid KOSPI close')
    peak = window[0]
    drawdown = 0
    for price in window[1:]:
        peak = max(peak, price)
        drawdown = max(drawdown, (peak - price) / peak)
    return tuple(int(drawdown >= threshold) for threshold in (.10, .20))

class DataError(ValueError): pass

def read_rows(path):
    with open(path, newline='',encoding='utf-8-sig') as f:
        rows=list(csv.DictReader(f))
    if not rows: raise DataError('No training data')
    if not {'asof','available_at',*FEATURES,*TARGETS}.issubset(rows[0]):
        raise DataError('Missing required columns')
    result=[]
    for r in rows:
        asof=dt.date.fromisoformat(r['asof']); available=dt.date.fromisoformat(r['available_at'])
        if available > asof: raise DataError('Look-ahead leakage: data unavailable as of prediction date')
        x=[]
        for k in FEATURES:
            try: v=float(r[k])
            except (ValueError,TypeError): raise DataError('Missing or invalid feature: '+k)
            if not math.isfinite(v): raise DataError('Non-finite feature: '+k)
            x.append(v)
        y={}
        for k in TARGETS:
            if r[k] not in ('0','1'): raise DataError('Target must be 0 or 1: '+k)
            y[k]=int(r[k])
        result.append((asof,x,y))
    result.sort(key=lambda r:r[0])
    if len({r[0] for r in result})!=len(result): raise DataError('Duplicate asof')
    return result

def sigmoid(z):
    z=max(-40,min(40,z)); return 1/(1+math.exp(-z))

def fit(train,target,iterations=400,rate=.08,features=FEATURES):
    if len(train)<80: raise DataError('At least 80 observations required')
    labels=[r[2][target] for r in train]
    if min(labels)==max(labels): raise DataError('Training data lacks both classes')
    means=[statistics.mean(r[1][j] for r in train) for j in range(len(features))]
    std=[statistics.pstdev(r[1][j] for r in train) or 1 for j in range(len(features))]
    w=[0.]*(len(features)+1)
    for _ in range(iterations):
        g=[0.]*len(w)
        for _,x,y in train:
            z=[1.]+[(v-m)/s for v,m,s in zip(x,means,std)]
            err=sigmoid(sum(a*b for a,b in zip(w,z)))-y[target]
            for j in range(len(g)):g[j]+=err*z[j]
        for j in range(len(w)):
            w[j]-=rate*(g[j]/len(train)+(0 if j==0 else .005*w[j]))
    return dict(target=target,features=list(features),means=means,std=std,weights=w)

def predict(model,x):
    z=[1.]+[(v-m)/s for v,m,s in zip(x,model['means'],model['std'])]
    return sigmoid(sum(a*b for a,b in zip(model['weights'],z)))

def evaluate(model,rows,base_rate):
    if not rows: raise DataError('Empty test window')
    probs=[predict(model,r[1]) for r in rows]; ys=[r[2][model['target']] for r in rows]
    brier=sum((p-y)**2 for p,y in zip(probs,ys))/len(ys)
    positives=sum(ys); negatives=len(ys)-positives
    return dict(brier=brier,samples=len(rows),events=positives,
                baseline_brier=sum((base_rate-y)**2 for y in ys)/len(ys),
                false_alarm_rate=sum(p>=.5 and not y for p,y in zip(probs,ys))/negatives if negatives else None,
                detection_rate=sum(p>=.5 and y for p,y in zip(probs,ys))/positives if positives else None)

def probability_logit(probability):
    clipped = min(1-1e-8, max(1e-8, probability))
    return math.log(clipped / (1-clipped))

def calibration_rows(model, rows):
    return [(day, [probability_logit(predict(model, values))], outcomes)
            for day, values, outcomes in rows]

def walk_forward(rows, target):
    """Use expanding training windows and disjoint chronological test blocks."""
    folds = []
    for start, end in ((.60, .70), (.70, .80), (.80, .90), (.90, 1)):
        first, last = int(len(rows)*start), int(len(rows)*end)
        test = rows[first:last]
        if not test:
            continue
        # A conservative common embargo covers every forecast horizon.
        boundary = test[0][0] - dt.timedelta(days=366)
        train = [row for row in rows[:first] if row[0] < boundary]
        if len(train) < 80 or len({row[2][target] for row in train}) < 2:
            continue
        model = fit(train,target)
        base_rate = statistics.mean(row[2][target] for row in train)
        folds.append(dict(test_start=test[0][0].isoformat(),test_end=test[-1][0].isoformat(),
                          train_end=train[-1][0].isoformat(),**evaluate(model,test,base_rate)))
    return folds

def train_and_validate(path):
    rows=read_rows(path)
    if len(rows)<120: raise DataError('At least 120 real dated observations required')
    # Temporal split with 12-month embargo to prevent overlapping long-horizon labels.
    split=int(len(rows)*.8); cutoff=rows[split][0]
    train=[r for r in rows[:split] if r[0] <= cutoff-dt.timedelta(days=366)]
    test=rows[split:]
    if len(train)<80: raise DataError('Not enough training data after embargo')
    reports={}; models={}
    for target in TARGETS:
        try:
            model=fit(train,target)
        except DataError as exc:
            reports[target]={'status':'waiting','reason':str(exc),'auto_deployed':False}
            continue
        score=evaluate(model,test,statistics.mean(r[2][target] for r in train))
        # Conservative: no deployment unless model beats constant-frequency test baseline.
        score['walk_forward'] = walk_forward(rows,target)
        # Overlapping positive labels are not evidence of independent crises.
        # Eligibility awaits independently documented event counts and calibration.
        score['eligible_for_review']=False
        score['auto_deployed']=False
        reports[target]=score;models[target]=model
    return {'status':'research_only','training_end':train[-1][0].isoformat(),'test_start':test[0][0].isoformat(),'reports':reports,'models':models}

if __name__=='__main__':
    import sys
    try:
        result=train_and_validate(sys.argv[1])
        pathlib.Path('model_report.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
        print('Research report saved; no model auto-deployed')
    except (IndexError,DataError,ValueError) as exc:
        print('NOT READY:',exc);sys.exit(2)
