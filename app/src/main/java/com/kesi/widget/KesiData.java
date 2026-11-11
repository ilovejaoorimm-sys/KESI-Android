package com.kesi.widget;

import org.json.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** No synthetic observations. Missing and stale inputs never become zero scores. */
public final class KesiData {
    public static final String[] IDS={"delinquency","unemployment","housing","credit","fx","pf"};
    public static final String[] NAMES={"은행 기업대출 연체율","실업률 · 계절조정","주택가격 · 전국 종합","회사채 신용스프레드","원/달러 매매기준율","부동산 PF 연체율"};
    public static final double[] WEIGHTS={.25,.15,.15,.20,.10,.15};
    // Explicit heuristic anchors; not an official index or a calibrated crisis probability.
    public static final double[] LOW={.2,2,0,.3,1100,1}, HIGH={2,6,10,3,1600,10};
    public final Metric[] metrics=new Metric[6];
    public String checked="아직 수집하지 않음";
    public KesiData(){ for(int i=0;i<6;i++)metrics[i]=new Metric(); }
    public static final class Metric {
        public Double value, previous;
        public String period="", previousPeriod="", release="", source="", sourceUrl="", fetched="", note="수집 전", unit="";
        public String state="missing";
        public boolean usable(int i, LocalDate today){
            if(!"ok".equals(state)||value==null||!Double.isFinite(value))return false;
            try {
                LocalDate p=periodDate(period);
                int maxDays=(i==3||i==4)?10:i==5?200:120;
                return !p.isAfter(today)&&!p.isBefore(today.minusDays(maxDays));
            }catch(Exception e){return false;}
        }
        public String status(int i){
            if("ok".equals(state))return usable(i,today())?"수집 완료":"오래된 자료 · 산출 제외";
            return value==null?"미수집":"수집 실패 · 이전 자료";
        }
    }
    public static LocalDate today(){return LocalDate.now(ZoneId.of("Asia/Seoul"));}
    public static LocalDate periodDate(String s){return s.length()==7?YearMonth.parse(s).atEndOfMonth():LocalDate.parse(s);}
    public static Double component(int i,Double raw){
        if(raw==null||!Double.isFinite(raw))return null;
        double stress=i==2?-raw:raw;
        return Math.max(0,Math.min(100,100*(stress-LOW[i])/(HIGH[i]-LOW[i])));
    }
    public static Double aggregate(Double[] parts){
        if(parts.length!=6)return null;
        double sum=0;int high=0;
        for(int i=0;i<6;i++){
            if(parts[i]==null||!Double.isFinite(parts[i])||parts[i]<0||parts[i]>100)return null;
            sum+=WEIGHTS[i]*parts[i];if(parts[i]>=70)high++;
        }
        return Math.min(100,sum+(high>=4?10:high==3?6:high==2?3:0));
    }
    public int count(){int n=0;for(int i=0;i<6;i++)if(metrics[i].usable(i,today()))n++;return n;}
    public Double score(boolean previous){
        Double[] a=new Double[6];
        for(int i=0;i<6;i++)if(metrics[i].usable(i,today()))a[i]=component(i,previous?metrics[i].previous:metrics[i].value);
        return aggregate(a);
    }
    public Double change(){Double a=score(false),b=score(true);return a==null||b==null?null:a-b;}
    public static String level(Double s){
        if(s==null)return "판정 보류 · 자료 부족";
        if(s<30)return "정상";if(s<45)return "주의";if(s<60)return "경계";if(s<75)return "위기 전조";return "위기";
    }
    public String scoreText(){Double s=score(false);return s==null?"— / 100":String.format(Locale.KOREA,"%.1f / 100",s);}
    public String changeText(){Double v=change();return v==null?"3개월 변화: 산출 불가":String.format(Locale.KOREA,"3개월 변화: %+.1f점",v);}
    public String releaseSummary(){
        TreeSet<String> dates=new TreeSet<>();int unknown=0;
        for(Metric m:metrics)if(m.value!=null){if(m.release.isEmpty())unknown++;else dates.add(m.release);}
        return dates.isEmpty()?"발표일: 미확인":("발표일: "+dates.first()+(dates.size()>1?" ~ "+dates.last():"")+(unknown>0?" / 일부 미확인":""));
    }
    public JSONObject json() throws JSONException {
        JSONObject root=new JSONObject().put("schema",2).put("checked",checked);JSONArray list=new JSONArray();
        for(Metric m:metrics)list.put(new JSONObject().put("value",m.value==null?JSONObject.NULL:m.value).put("previous",m.previous==null?JSONObject.NULL:m.previous)
            .put("period",m.period).put("previousPeriod",m.previousPeriod).put("release",m.release).put("source",m.source).put("sourceUrl",m.sourceUrl).put("fetched",m.fetched).put("note",m.note).put("unit",m.unit).put("state",m.state));
        return root.put("metrics",list);
    }
    public static KesiData parse(String raw) throws JSONException {
        JSONObject root=new JSONObject(raw);if(root.getInt("schema")!=2)throw new JSONException("Unsupported schema");
        JSONArray a=root.getJSONArray("metrics");if(a.length()!=6)throw new JSONException("Invalid metric count");
        KesiData d=new KesiData();d.checked=root.getString("checked");
        for(int i=0;i<6;i++){
            JSONObject o=a.getJSONObject(i);Metric m=d.metrics[i];
            m.value=o.isNull("value")?null:o.getDouble("value");m.previous=o.isNull("previous")?null:o.getDouble("previous");
            if((m.value!=null&&!Double.isFinite(m.value))||(m.previous!=null&&!Double.isFinite(m.previous)))throw new JSONException("Nonfinite");
            m.period=o.getString("period");m.previousPeriod=o.getString("previousPeriod");m.release=o.getString("release");m.source=o.getString("source");m.sourceUrl=o.getString("sourceUrl");m.fetched=o.getString("fetched");m.note=o.getString("note");m.unit=o.getString("unit");m.state=o.getString("state");
        }return d;
    }
}
