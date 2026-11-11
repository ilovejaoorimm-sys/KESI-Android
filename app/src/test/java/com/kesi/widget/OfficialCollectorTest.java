package com.kesi.widget;
import org.junit.Test;
import static org.junit.Assert.*;
import org.json.*;
import java.time.*;

public class OfficialCollectorTest {
    // Synthetic values exist in tests only, never packaged in the app.
    private String row(String code,String name,String day,double v){return "{\"STAT_CODE\":\"731Y001\",\"ITEM_CODE1\":\""+code+"\",\"ITEM_NAME1\":\""+name+"\",\"ITEM_CODE2\":null,\"UNIT_NAME\":\"원\",\"TIME\":\""+day+"\",\"DATA_VALUE\":\""+v+"\"}";}
    private String body(String rows,int count){return "{\"StatisticSearch\":{\"list_total_count\":"+count+",\"row\":["+rows+"]}}";}
    @Test public void latestNotFirstAndExactMonthComparison() throws Exception {
        OfficialCollector c=new OfficialCollector(url->url.contains("202608")?body(row("0000001","원/미국달러(매매기준율)","20260810",1250),1):body(row("0000001","원/미국달러(매매기준율)","20261109",1300)+","+row("0000001","원/미국달러(매매기준율)","20261110",1350),2));
        KesiData.Metric m=c.collect(4,LocalDate.of(2026,11,10));assertEquals(1350.,m.value,0);assertEquals(1250.,m.previous,0);assertEquals("2026-08-10",m.previousPeriod);assertEquals("",m.release);
    }
    @Test public void failedHistoryKeepsCurrentButNoMomentum() throws Exception {
        OfficialCollector c=new OfficialCollector(url->{if(url.contains("202608"))throw new java.io.IOException("offline");return body(row("0000001","원/미국달러(매매기준율)","20261110",1350),1);});
        KesiData.Metric m=c.collect(4,LocalDate.of(2026,11,10));assertNotNull(m.value);assertNull(m.previous);
    }
    @Test(expected=java.io.IOException.class) public void wrongSeriesRejected() throws Exception {new OfficialCollector(url->body(row("0000002","원/일본엔","20261110",900),1)).collect(4,LocalDate.of(2026,11,10));}
    @Test(expected=java.io.IOException.class) public void futureObservationRejected() throws Exception {new OfficialCollector(url->body(row("0000001","원/미국달러","20270101",1400),1)).collect(4,LocalDate.of(2026,11,10));}
    @Test(expected=java.io.IOException.class) public void duplicatesRejected() throws Exception {String r=row("0000001","원/미국달러","20261110",1400);new OfficialCollector(url->body(r+","+r,2)).collect(4,LocalDate.of(2026,11,10));}
    @Test(expected=java.io.IOException.class) public void noDataFailsClosed() throws Exception {new OfficialCollector(url->"{\"RESULT\":{\"CODE\":\"INFO-200\"}}").collect(4,LocalDate.of(2026,11,10));}
    @Test(expected=java.io.IOException.class) public void pfNeverUsesFallbackNumber() throws Exception {new OfficialCollector(url->"{}").collect(5,LocalDate.of(2026,11,10));}
}
