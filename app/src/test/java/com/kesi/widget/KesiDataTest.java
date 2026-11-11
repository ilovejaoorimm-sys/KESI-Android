package com.kesi.widget;
import org.junit.Test;
import static org.junit.Assert.*;
import java.time.*;

public class KesiDataTest {
    @Test public void emptyDataDoesNotBecomeNormal(){KesiData d=new KesiData();assertNull(d.score(false));assertNull(d.change());assertEquals(0,d.count());assertTrue(KesiData.level(null).contains("보류"));}
    @Test public void oneMissingInputPreventsFullScore(){assertNull(KesiData.aggregate(new Double[]{10.,20.,30.,40.,50.,null}));}
    @Test public void anchorsClampAndHousingRunsDownward(){assertEquals(0.,KesiData.component(4,900.),0);assertEquals(100.,KesiData.component(4,1900.),0);assertEquals(50.,KesiData.component(2,-5.),0);assertEquals(0.,KesiData.component(2,5.),0);assertNull(KesiData.component(0,Double.NaN));}
    @Test public void weightsAndBonuses(){assertEquals(32.0,KesiData.aggregate(new Double[]{10.,20.,30.,40.,50.,60.}),.00001);assertEquals(31.,KesiData.aggregate(new Double[]{70.,70.,0.,0.,0.,0.}),.00001);assertEquals(100.,KesiData.aggregate(new Double[]{100.,100.,100.,100.,100.,100.}),0);}
    @Test public void riskBoundaries(){assertEquals("정상",KesiData.level(29.99));assertEquals("주의",KesiData.level(30.));assertEquals("경계",KesiData.level(45.));assertEquals("위기 전조",KesiData.level(60.));assertEquals("위기",KesiData.level(75.));}
    @Test public void missingPreviousIsNotZeroChange(){KesiData d=complete();assertNotNull(d.score(false));assertNull(d.change());}
    @Test public void realThreeMonthChange(){KesiData d=complete();for(int i=0;i<6;i++)d.metrics[i].previous=d.metrics[i].value;assertEquals(0.,d.change(),0);d.metrics[4].previous=1100.;assertTrue(d.change()>0);}
    @Test public void staleAndFailedInputsExcluded(){KesiData d=complete();d.metrics[4].period=KesiData.today().minusDays(11).toString();assertNull(d.score(false));d=complete();d.metrics[0].state="missing";assertNull(d.score(false));}
    @Test public void roundTripPreservesNullsAndPublicationUnknown() throws Exception {KesiData d=KesiData.parse(new KesiData().json().toString());assertNull(d.metrics[0].value);assertEquals("",d.metrics[0].release);assertNull(d.score(false));}
    @Test(expected=org.json.JSONException.class) public void oldSampleCacheRejected() throws Exception {KesiData.parse("{\"delinquency\":62,\"unemployment\":31,\"housing\":48,\"credit\":41,\"fx\":58,\"pf\":67}");}
    private KesiData complete(){KesiData d=new KesiData();for(int i=0;i<6;i++){d.metrics[i].state="ok";d.metrics[i].period=KesiData.today().toString();d.metrics[i].value=i==4?1350.:i==2?-2.:2.;}return d;}
}
