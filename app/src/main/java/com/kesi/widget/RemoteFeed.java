package com.kesi.widget;
import org.json.*;
import java.time.*;
import java.util.*;

/** Declarative data only: no downloaded code or executable expressions. */
public final class RemoteFeed {
    public final List<Entry> entries=new ArrayList<>();
    public int revision;public String method,generatedAt;public boolean refreshFailed;
    public static final class Entry {
        public String id,name,unit,status,period,previousPeriod,release,source,sourceUrl,reason,direction;
        public double weight,low,high;public int maxAgeDays;public Double value,previous;
        public boolean usable(LocalDate today){
            if(!"ok".equals(status)||value==null||period.isEmpty())return false;
            LocalDate p=KesiData.periodDate(period);return !p.isAfter(today)&&!p.isBefore(today.minusDays(maxAgeDays));
        }
        public Double component(boolean past){Double v=past?previous:value;if(v==null)return null;double f=(v-low)/(high-low);return 100*Math.max(0,Math.min(1,"down".equals(direction)?1-f:f));}
    }
    private static String str(JSONObject o,String k,int limit) throws JSONException {String s=o.getString(k);if(s.isEmpty()||s.length()>limit)throw new JSONException("Invalid "+k);return s;}
    private static String nullable(JSONObject o,String k){return o.isNull(k)?"":o.optString(k,"");}
    private static Double num(JSONObject o,String k) throws JSONException {if(o.isNull(k))return null;double v=o.getDouble(k);if(!Double.isFinite(v))throw new JSONException("Invalid number");return v;}
    public static RemoteFeed parse(String raw,Instant now) throws Exception {
        JSONObject o=new JSONObject(raw);if(o.getInt("schema")!=1)throw new JSONException("Unsupported feed schema");
        RemoteFeed f=new RemoteFeed();f.revision=o.getInt("revision");if(f.revision<1)throw new JSONException("Revision");
        f.method=str(o,"method",200);f.generatedAt=str(o,"generatedAt",80);
        Instant generated=OffsetDateTime.parse(f.generatedAt).toInstant();if(generated.isAfter(now.plusSeconds(300)))throw new JSONException("Future feed");
        LocalDate today=now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
        JSONArray a=o.getJSONArray("indicators");if(a.length()<1||a.length()>40)throw new JSONException("Metric count");
        Set<String> ids=new HashSet<>();double weights=0;
        for(int i=0;i<a.length();i++){
            JSONObject m=a.getJSONObject(i);Entry e=new Entry();e.id=str(m,"id",40);
            if(!e.id.matches("[a-z][a-z0-9_]*")||!ids.add(e.id))throw new JSONException("Duplicate/invalid ID");
            e.name=str(m,"name",100);e.unit=str(m,"unit",40);e.source=str(m,"source",300);
            e.weight=m.getDouble("weight");e.low=m.getDouble("low");e.high=m.getDouble("high");e.maxAgeDays=m.getInt("maxAgeDays");
            if(!Double.isFinite(e.weight)||!Double.isFinite(e.low)||!Double.isFinite(e.high)||e.weight<0||e.weight>1||e.low>=e.high||e.maxAgeDays<1||e.maxAgeDays>400)throw new JSONException("Scoring rule");
            e.direction=m.getString("direction");if(!e.direction.equals("up")&&!e.direction.equals("down"))throw new JSONException("Unknown scoring rule");
            e.status=m.getString("status");if(!e.status.equals("ok")&&!e.status.equals("missing"))throw new JSONException("State");
            e.value=num(m,"value");e.previous=num(m,"previous");e.period=nullable(m,"period");e.previousPeriod=nullable(m,"previousPeriod");e.release=nullable(m,"releaseDate");e.sourceUrl=nullable(m,"sourceUrl");e.reason=nullable(m,"reason");
            if(e.reason.length()>500)throw new JSONException("Reason length");
            if(!e.sourceUrl.isEmpty()){
                java.net.URI uri=new java.net.URI(e.sourceUrl);
                if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||(uri.getPort()!=-1&&uri.getPort()!=443))throw new JSONException("Invalid source URL");
            }
            if(e.status.equals("ok")&&(e.value==null||e.period.isEmpty()||e.sourceUrl.isEmpty()))throw new JSONException("Missing provenance");
            if(e.status.equals("missing")&&(e.value!=null||e.previous!=null))throw new JSONException("Missing must not contain fallback values");
            if(!e.period.isEmpty()&&KesiData.periodDate(e.period).isAfter(today))throw new JSONException("Future observation");
            if(!e.release.isEmpty()&&LocalDate.parse(e.release).isAfter(today))throw new JSONException("Future publication");
            if(e.previous!=null){
                if(e.previousPeriod.isEmpty()||e.period.isEmpty())throw new JSONException("Missing comparison period");
                if(e.period.length()==7){if(!YearMonth.parse(e.previousPeriod).equals(YearMonth.parse(e.period).minusMonths(3)))throw new JSONException("Not three months");}
                else {LocalDate target=LocalDate.parse(e.period).minusMonths(3),p=LocalDate.parse(e.previousPeriod);if(p.isAfter(target)||p.isBefore(target.minusDays(7)))throw new JSONException("Not three months");}
            }
            weights+=e.weight;f.entries.add(e);
        }
        if(Math.abs(weights-1)>1e-6)throw new JSONException("Weights must sum to one");return f;
    }
    public boolean fresh(){Instant t=OffsetDateTime.parse(generatedAt).toInstant();return !refreshFailed&&t.isAfter(Instant.now().minusSeconds(48*3600));}
    public int count(){int n=0;for(Entry e:entries)if(e.usable(KesiData.today()))n++;return n;}
    public Double score(boolean past){
        if(!fresh())return null;double sum=0;int high=0;
        for(Entry e:entries)if(e.weight>0){if(!e.usable(KesiData.today()))return null;Double s=e.component(past);if(s==null)return null;sum+=e.weight*s;if(s>=70)high++;}
        return Math.min(100,sum+(high>=4?10:high==3?6:high==2?3:0));
    }
    public String scoreText(){Double s=score(false);return s==null?"— / 100":String.format(Locale.KOREA,"%.1f / 100",s);}
    public String changeText(){Double a=score(false),b=score(true);return a==null||b==null?"3개월 변화: 산출 불가":String.format(Locale.KOREA,"3개월 변화: %+.1f점",a-b);}
    public String publication(){TreeSet<String> d=new TreeSet<>();boolean unknown=false;for(Entry e:entries)if(e.value!=null){if(e.release.isEmpty())unknown=true;else d.add(e.release);}return d.isEmpty()?"발표일: 미확인":"발표일: "+d.first()+(d.size()>1?" ~ "+d.last():"")+(unknown?" / 일부 미확인":"");}
}
