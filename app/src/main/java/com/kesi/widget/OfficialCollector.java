package com.kesi.widget;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Official ECOS HTTPS observations only. Public access limits each page to 10 rows. */
public final class OfficialCollector {
    private static final String BASE="https://ecos.bok.or.kr/api/";
    public interface Transport { String get(String url) throws Exception; }
    private final Transport transport;
    public OfficialCollector(){this(OfficialCollector::http);}
    public OfficialCollector(Transport t){transport=t;}
    static String http(String address) throws Exception {
        URL url=new URL(address);
        if(!"https".equals(url.getProtocol())||!"ecos.bok.or.kr".equals(url.getHost()))throw new IOException("허용되지 않은 출처");
        HttpURLConnection c=(HttpURLConnection)url.openConnection();
        c.setConnectTimeout(12000);c.setReadTimeout(12000);c.setInstanceFollowRedirects(false);
        c.setRequestProperty("User-Agent","KESI-Android/2.0");
        try{
            if(c.getResponseCode()!=200)throw new IOException("공식 API 응답 "+c.getResponseCode());
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){out.write(b,0,n);if(out.size()>2000000)throw new IOException("응답 크기 초과");}
                return out.toString(StandardCharsets.UTF_8.name());
            }
        }finally{c.disconnect();}
    }
    static final class Series {
        final TreeMap<String,Double> values=new TreeMap<>();String url="",unit="";
    }
    Series series(String table,String cycle,String start,String end,String code,String code2,String expected) throws Exception {
        Series s=new Series();
        for(int page=1;page<=101;page+=10){
            String url=BASE+"StatisticSearch/sample/json/kr/"+page+"/"+(page+9)+"/"+table+"/"+cycle+"/"+start+"/"+end+"/"+code+"/"+(code2.isEmpty()?"":code2+"/");
            JSONObject r=new JSONObject(transport.get(url));s.url=url;
            if(r.has("RESULT")){
                String err=r.getJSONObject("RESULT").optString("CODE");
                if("INFO-200".equals(err))break;
                throw new IOException("공식 API 오류 "+err);
            }
            JSONObject body=r.getJSONObject("StatisticSearch");JSONArray rows=body.getJSONArray("row");
            for(int j=0;j<rows.length();j++){
                JSONObject row=rows.getJSONObject(j);
                if(!table.equals(row.getString("STAT_CODE"))||!code.equals(row.getString("ITEM_CODE1"))||!row.getString("ITEM_NAME1").contains(expected)||(!code2.isEmpty()&&!code2.equals(row.getString("ITEM_CODE2"))))throw new IOException("통계 항목 검증 실패");
                if(code2.isEmpty()&&!row.isNull("ITEM_CODE2")&&!row.optString("ITEM_CODE2").isEmpty())throw new IOException("복수 계열 거부");
                String time=row.getString("TIME");if(time.compareTo(start)<0||time.compareTo(end)>0)throw new IOException("기간 검증 실패");
                String raw=row.getString("DATA_VALUE").replace(",","");
                if(raw.isEmpty()||raw.equals("-"))continue;
                double v=Double.parseDouble(raw);if(!Double.isFinite(v)||s.values.put(time,v)!=null)throw new IOException("중복 또는 잘못된 관측값");
                String unit=row.getString("UNIT_NAME");
                if(!s.unit.isEmpty()&&!s.unit.equals(unit))throw new IOException("혼합 단위 거부");
                s.unit=unit;
                if((table.equals("731Y001")&&v<=0)||((table.equals("901Y027")||table.equals("901Y124"))&&(v<0||v>100)))throw new IOException("원자료 범위 오류");
                if(!row.isNull("ITEM_CODE3")&&!row.optString("ITEM_CODE3").isEmpty())throw new IOException("예상하지 못한 하위 계열");
                if(!row.isNull("ITEM_CODE4")&&!row.optString("ITEM_CODE4").isEmpty())throw new IOException("예상하지 못한 하위 계열");
            }
            if(page+9>=body.getInt("list_total_count"))break;
            if(page==101)throw new IOException("응답 페이지 초과");
        }
        if(s.values.isEmpty())throw new IOException("공식 원자료 없음");
        return s;
    }
    private static String compact(LocalDate d){return d.format(DateTimeFormatter.BASIC_ISO_DATE);}
    private static String month(YearMonth m){return m.toString().replace("-","");}
    private static String display(String key){return key.length()==6?key.substring(0,4)+"-"+key.substring(4):LocalDate.parse(key,DateTimeFormatter.BASIC_ISO_DATE).toString();}
    private static KesiData.Metric make(Series s,String key,Double previous,String pk,String source,String unit){
        KesiData.Metric m=new KesiData.Metric();m.value=s.values.get(key);m.previous=previous;m.period=display(key);m.previousPeriod=pk==null?"":display(pk);m.source=source;m.sourceUrl=s.url;m.unit=unit;m.state="ok";
        m.fetched=ZonedDateTime.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        m.note="공식 ECOS 원자료 · 발표일은 API 미제공";return m;
    }
    public KesiData.Metric collect(int i,LocalDate now) throws Exception {
        YearMonth ym=YearMonth.from(now);String end=month(ym.minusMonths(1));
        if(i==0){
            Series s=series("901Y124","M",month(ym.minusMonths(9)),end,"MO3AA","AB","기업대출");
            if(!"%".equals(s.unit))throw new IOException("단위 불일치");
            String k=s.values.lastKey(),p=month(YearMonth.parse(display(k)).minusMonths(3));
            KesiData.Metric m=make(s,k,s.values.get(p),s.values.containsKey(p)?p:null,"은행연합회 / ECOS · 은행전체 기업대출, 1개월 이상 연체","%");
            m.note+=" · 전체 원화대출 총연체율과 다른 계열";return m;
        }
        if(i==2){
            Series s=series("901Y149","M",month(ym.minusMonths(21)),end,"00","a0","종합");
            if(!"2026.06=100".equals(s.unit))throw new IOException("주택가격 기준년 변경 확인 필요");
            String k=s.values.lastKey();YearMonth latest=YearMonth.parse(display(k));
            String prev=month(latest.minusMonths(3)),year=month(latest.minusMonths(12)),prevYear=month(latest.minusMonths(15));
            Double current=yoy(s,k,year),previous=yoy(s,prev,prevYear);
            if(current==null)throw new IOException("동일 기준년 전년비 산출자료 부족");
            KesiData.Metric m=make(s,k,previous,previous==null?null:prev,"한국부동산원 / ECOS · 전국 종합 매매가격, 2026.06=100","% 전년비");
            m.value=current;m.note+=" · 동일 기준년 지수 전년비로 계산";return m;
        }
        if(i==1){
            Series s=series("901Y027","M",month(ym.minusMonths(9)),end,"I61BC","I28B","실업률");
            if(!"%".equals(s.unit))throw new IOException("단위 불일치");
            String k=s.values.lastKey(),p=month(YearMonth.parse(display(k)).minusMonths(3));
            return make(s,k,s.values.get(p),s.values.containsKey(p)?p:null,"국가데이터처 / 한국은행 ECOS · 실업률(계절조정)","%");
        }
        if(i==3||i==4){
            String table=i==4?"731Y001":"817Y002",code=i==4?"0000001":"010300000",expected=i==4?"원/미국달러":"회사채(3년, AA-)";
            Series a=series(table,"D",compact(now.minusDays(10)),compact(now),code,"",expected);
            if(!(i==4?"원":"연%").equals(a.unit))throw new IOException("단위 불일치");
            if(i==3){Series b=series(table,"D",compact(now.minusDays(10)),compact(now),"010200000","","국고채(3년)");spread(a,b);}
            String k=a.values.lastKey();LocalDate target=LocalDate.parse(k,DateTimeFormatter.BASIC_ISO_DATE).minusMonths(3);
            Double pv=null;String pk=null;
            try{
                Series p=series(table,"D",compact(target.minusDays(7)),compact(target),code,"",expected);
                if(i==3){Series b=series(table,"D",compact(target.minusDays(7)),compact(target),"010200000","","국고채(3년)");spread(p,b);}
                pk=p.values.lastKey();pv=p.values.get(pk);
            }catch(Exception ignored){ /* Current observation remains valid; missing historical data is not zero. */ }
            return make(a,k,pv,pk,i==4?"한국은행 ECOS · 원/미국달러 매매기준율":"한국은행 ECOS · 회사채 AA− 3년 − 국고채 3년",i==4?"원":"%p");
        }
        throw new IOException(i==0?"국내은행 원화대출 총연체율의 자동수집 계열 미확인":i==2?"한국부동산원 현행 기준년 계열 연결 대기":"금융위원회 PF 공식자료 자동 파싱 미확인");
    }
    private static Double yoy(Series s,String current,String old){
        Double a=s.values.get(current),b=s.values.get(old);return a==null||b==null||a<=0||b<=0?null:(a/b-1)*100;
    }
    private static void spread(Series a,Series b) throws IOException {
        if(!"연%".equals(b.unit))throw new IOException("국고채 단위 불일치");
        a.values.keySet().retainAll(b.values.keySet());
        if(a.values.isEmpty())throw new IOException("동일 날짜 금리 없음");
        for(String k:a.values.keySet())a.values.put(k,a.values.get(k)-b.values.get(k));
    }
}
