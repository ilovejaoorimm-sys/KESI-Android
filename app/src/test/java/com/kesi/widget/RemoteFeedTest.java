package com.kesi.widget;
import org.junit.Test;
import org.json.*;
import java.time.*;
import static org.junit.Assert.*;

public class RemoteFeedTest {
    // Test-only fixture. Never packaged as app data.
    JSONObject row(String id,double weight) throws Exception {
        LocalDate now=KesiData.today();return new JSONObject().put("id",id).put("name",id).put("unit","%").put("source","ECOS")
            .put("sourceUrl","https://ecos.bok.or.kr/api/test").put("reason","").put("weight",weight).put("low",0).put("high",10).put("direction","up").put("maxAgeDays",10)
            .put("status","ok").put("value",5).put("previous",4).put("period",now.toString()).put("previousPeriod",now.minusMonths(3).toString()).put("releaseDate",JSONObject.NULL);
    }
    JSONObject base() throws Exception {return new JSONObject().put("schema",1).put("revision",1).put("method","test rules").put("generatedAt",OffsetDateTime.now(ZoneOffset.UTC).toString()).put("indicators",new JSONArray().put(row("base",1)));}
    RemoteFeed parse(JSONObject o) throws Exception {return RemoteFeed.parse(o.toString(),Instant.now());}
    @Test public void eachMarketTargetRequiresItsOwnFreshValidatedForecast() throws Exception {
        JSONObject good=new JSONObject().put("status","validated").put("definition","forecast_close")
                .put("modelVersion","fixture").put("asOf",KesiData.today().toString()).put("probability",.2);
        JSONObject waiting=new JSONObject(good.toString()).put("status","waiting");
        JSONObject stale=new JSONObject(good.toString()).put("asOf",KesiData.today().minusDays(8).toString());
        RemoteFeed feed=parse(base().put("marketForecasts",new JSONObject()
                .put("5d_10pct",good).put("60d_20pct",waiting).put("20d_10pct",stale)));
        assertEquals(.2,feed.marketForecast(5,10),.0001);
        assertNull(feed.marketForecast(60,20));
        assertNull(feed.marketForecast(20,10));
        assertNull(feed.marketForecast(5,20));
    }
    @Test public void seventhMetricRequiresNoRecompile() throws Exception {JSONObject o=base();JSONArray a=o.getJSONArray("indicators");for(int i=0;i<6;i++)a.put(row("extra"+i,0));RemoteFeed f=parse(o);assertEquals(7,f.entries.size());assertEquals(50.,f.score(false),.0001);assertEquals(40.,f.score(true),.0001);}
    @Test public void newScoredMetricUsesConfiguredWeights() throws Exception {JSONObject o=base();o.getJSONArray("indicators").getJSONObject(0).put("weight",.5);o.getJSONArray("indicators").put(row("extra",.5).put("value",1));assertEquals(30.,parse(o).score(false),.0001);}
    @Test public void missingMonitorDoesNotBlockIndex() throws Exception {JSONObject o=base();o.getJSONArray("indicators").put(row("extra",0).put("status","missing").put("value",JSONObject.NULL).put("previous",JSONObject.NULL));assertEquals(50.,parse(o).score(false),.0001);}
    @Test public void missingWeightedValueBlocksGrade() throws Exception {JSONObject o=base();o.getJSONArray("indicators").getJSONObject(0).put("status","missing").put("value",JSONObject.NULL).put("previous",JSONObject.NULL);assertNull(parse(o).score(false));}
    @Test public void staleFeedAndFailedRefreshBlockIndex() throws Exception {JSONObject o=base();o.put("generatedAt",OffsetDateTime.now(ZoneOffset.UTC).minusDays(3).toString());assertNull(parse(o).score(false));RemoteFeed f=parse(base());f.refreshFailed=true;assertNull(f.score(false));}
    @Test public void inverseDirectionWorks() throws Exception {JSONObject o=base();o.getJSONArray("indicators").getJSONObject(0).put("value",2).put("direction","down");assertEquals(80.,parse(o).score(false),.0001);}
    @Test(expected=Exception.class) public void badWeightSumRejected() throws Exception {JSONObject o=base();o.getJSONArray("indicators").getJSONObject(0).put("weight",.3);parse(o);}
    @Test(expected=Exception.class) public void duplicateIdsRejected() throws Exception {JSONObject o=base();o.getJSONArray("indicators").put(row("base",0));parse(o);}
    @Test(expected=Exception.class) public void futureFeedRejected() throws Exception {JSONObject o=base();o.put("generatedAt",OffsetDateTime.now(ZoneOffset.UTC).plusDays(1).toString());parse(o);}
    @Test(expected=Exception.class) public void unsupportedSchemaRejected() throws Exception {parse(base().put("schema",2));}
    @Test(expected=Exception.class) public void wrongComparisonPeriodRejected() throws Exception {JSONObject o=base();o.getJSONArray("indicators").getJSONObject(0).put("previousPeriod",KesiData.today().minusYears(1).toString());parse(o);}
    @Test(expected=Exception.class) public void unknownRuleRejected() throws Exception {JSONObject o=base();o.getJSONArray("indicators").getJSONObject(0).put("direction","execute");parse(o);}
}
