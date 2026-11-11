package com.kesi.widget;

import android.app.*;
import android.appwidget.AppWidgetManager;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.util.Locale;

public final class MainActivity extends Activity {
    private LinearLayout body;private boolean refreshing;
    private static final int INK=Color.rgb(230,237,247),MUTED=Color.rgb(159,176,198),TEAL=Color.rgb(69,217,186);
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    @Override public void onCreate(Bundle b){super.onCreate(b);getWindow().setStatusBarColor(Color.rgb(11,18,32));getWindow().setNavigationBarColor(Color.rgb(11,18,32));RefreshJobService.schedule(this);render(DataRepository.load(this));refresh();}
    private TextView text(LinearLayout parent,String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(null,Typeface.BOLD);t.setPadding(0,dp(5),0,dp(5));parent.addView(t);return t;}
    private LinearLayout card(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(18),dp(14),dp(18),dp(14));GradientDrawable bg=new GradientDrawable();bg.setColor(Color.rgb(21,33,51));bg.setCornerRadius(dp(18));l.setBackground(bg);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(12),0,0);body.addView(l,p);return l;}
    private String number(Double v,String unit){return v==null?"미수집":String.format(Locale.KOREA,"%,.2f %s",v,unit);}
    private void refresh(){
        if(refreshing)return;refreshing=true;render(DataRepository.load(this));
        Context app=getApplicationContext();DataRepository.EXECUTOR.execute(()->{KesiData d=DataRepository.refresh(app);KesiWidgetProvider.updateAll(app,d);runOnUiThread(()->{refreshing=false;if(!isFinishing()&&!isDestroyed())render(d);});});
    }
    private void render(KesiData d){
        RemoteFeed remote=RemoteRepository.load(this);
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(Color.rgb(11,18,32));scroll.setFillViewport(true);
        body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(22),dp(22),dp(22),dp(28));scroll.addView(body);setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});scroll.requestApplyInsets();
        text(body,"KESI 4.0 AI",15,TEAL,true);text(body,"경제 스트레스 모니터",26,INK,true);text(body,"공식 원자료 · 사용자 정의 점수",13,MUTED,false);
        text(body,remote!=null&&!remote.fresh()?"원격 자료 갱신 확인 필요 · 이전 자료":RemoteRepository.status(this),12,MUTED,false);
        LinearLayout top=card();text(top,remote==null?d.scoreText():remote.scoreText(),40,INK,true);
        text(top,KesiData.level(remote==null?d.score(false):remote.score(false)),18,TEAL,true);
        text(top,"유효 지표 "+(remote==null?d.count()+" / 6":remote.count()+" / "+remote.entries.size())+" · 결측값 대체 없음",13,MUTED,false);
        text(top,remote==null?d.changeText():remote.changeText(),16,INK,false);
        text(top,remote==null?d.releaseSummary()+"\n수집 확인: "+d.checked+" KST":remote.publication()+"\n서버 갱신: "+remote.generatedAt+"\n설정 버전: "+remote.revision,12,MUTED,false);
        Button refresh=new Button(this);refresh.setText(refreshing?"공식 통계 확인 중…":"지금 새로고침");refresh.setEnabled(!refreshing);refresh.setOnClickListener(v->refresh());body.addView(refresh);
        Button pin=new Button(this);pin.setText("홈 화면에 위젯 추가");pin.setOnClickListener(v->{AppWidgetManager mgr=AppWidgetManager.getInstance(this);if(mgr.isRequestPinAppWidgetSupported())mgr.requestPinAppWidget(new ComponentName(this,KesiWidgetProvider.class),null,null);else Toast.makeText(this,"홈 화면을 길게 눌러 위젯 → KESI를 선택하세요",Toast.LENGTH_LONG).show();});body.addView(pin);
        renderForecastStatus(remote);
        if(remote!=null)renderRemote(remote);
        else for(int i=0;i<6;i++){
            KesiData.Metric m=d.metrics[i];LinearLayout c=card();text(c,String.format(Locale.KOREA,"%02d  %s  ·  %.0f%%",i+1,KesiData.NAMES[i],100*KesiData.WEIGHTS[i]),16,INK,true);
            text(c,m.status(i),12,m.usable(i,KesiData.today())?TEAL:Color.rgb(255,188,107),true);
            text(c,number(m.value,m.unit)+(m.usable(i,KesiData.today())?String.format(Locale.KOREA,"   →   %.1f점",KesiData.component(i,m.value)):""),22,INK,true);
            text(c,"기준기간: "+(m.period.isEmpty()?"미확인":m.period)+"\n발표일: "+(m.release.isEmpty()?"미확인 (확인일과 구분)":m.release),12,MUTED,false);
            String change=m.value!=null&&m.previous!=null?String.format(Locale.KOREA,"3개월 원자료 변화: %+.2f %s\n비교기간: %s → %s",m.value-m.previous,(i==2?"%p (전년비 차이)":i==0||i==1||i==5?"%p":m.unit),m.previousPeriod,m.period):"3개월 원자료 변화: 산출 불가";
            text(c,change,13,INK,false);text(c,m.note,12,MUTED,false);
            if(!m.source.isEmpty())text(c,m.source+"\n수집: "+m.fetched+" KST",11,MUTED,false);
            if(!m.sourceUrl.isEmpty()){
                TextView link=text(c,"공식 원자료 열기 ↗",13,TEAL,true);String url=m.sourceUrl;
                link.setOnClickListener(v->{try{startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse(url)));}catch(ActivityNotFoundException e){Toast.makeText(this,"브라우저가 필요합니다",Toast.LENGTH_SHORT).show();}});
            }
        }
        if(remote!=null)return;
        LinearLayout explain=card();text(explain,"점수 산정 기준",17,INK,true);text(explain,"공식 통계기관의 지수가 아닌 KESI 자체 규칙입니다. 금융위기 발생확률을 뜻하지 않습니다.\n\n6개 점수가 모두 유효할 때만 가중합과 위험등급을 계산합니다. 70점 이상 지표가 2개면 +3, 3개면 +6, 4개 이상이면 +10점, 최종 100점 상한입니다.\n\n0→100점 기준: 은행 기업대출 연체율 0.2→2%, 계절조정 실업률 2→6%, 전국 주택가격 전년비 하락 0→10%, 신용스프레드 0.3→3%p, 원/달러 1,100→1,600원, PF 연체율 1→10%. 범위 밖 값은 0 또는 100점입니다.\n\n등급: 정상 <30, 주의 <45, 경계 <60, 위기 전조 <75, 위기 ≥75.\n\n3개월 변화는 각 지표의 최신 관측기간과 3개월 전 관측기간을 비교한 수정계열 기준입니다. 당시 공개되어 있던 값으로 재현한 실시간 지수는 아닙니다. 일별 자료는 비교일 이전 7일 이내의 최근 영업일을 사용합니다.\n\n매 6시간 자동 갱신을 요청합니다. Android 절전·네트워크 상태에 따라 늦어질 수 있습니다. 일별 10일, 월별 120일, PF 200일을 초과한 기준기간은 종합 산출에서 제외합니다.\n\n미수집: 값이나 연결을 확인하지 못함. 발표일 미확인: API가 발표일을 제공하지 않음. 통계 기준기간·수집일을 발표일로 대체하지 않습니다.",13,MUTED,false);
    }
    private void renderForecastStatus(RemoteFeed remote){
        LinearLayout crisis=card();
        text(crisis,"AI-A · 경제·금융위기",17,INK,true);
        text(crisis,"3개월 · 6개월 · 12개월\n검증 대기 · 배포된 모델 없음",14,MUTED,false);
        LinearLayout market=card();
        text(market,"AI-B · KOSPI 급락",17,INK,true);
        text(market,"예측일 종가 대비 최대 하락률",14,MUTED,false);
        for(int days:new int[]{5,20,60})for(int percent:new int[]{10,20}){
            Double probability=remote==null?null:remote.marketForecast(days,percent);
            String status=probability==null?"검증 대기":String.format(Locale.KOREA,"%.1f%%",100*probability);
            text(market,days+"거래일 · "+percent+"% 하락  |  "+status,14,MUTED,false);
        }
    }
    private void renderRemote(RemoteFeed feed){
        for(RemoteFeed.Entry m:feed.entries){
            LinearLayout c=card();text(c,m.name+(m.weight==0?" · 참고용":String.format(Locale.KOREA," · %.0f%%",m.weight*100)),17,INK,true);
            boolean good=m.usable(KesiData.today());text(c,good?"수집 완료":m.value==null?"미수집":m.retained?"갱신 실패 · 마지막 확인값 · 산출 제외":"오래된 자료 · 산출 제외",12,good?TEAL:Color.rgb(255,188,107),true);
            text(c,number(m.value,m.unit)+(good?String.format(Locale.KOREA," → %.1f점",m.component(false)):""),22,INK,true);
            text(c,"기준기간: "+(m.period.isEmpty()?"미확인":m.period)+"\n발표일: "+(m.release.isEmpty()?"미확인":m.release),12,MUTED,false);
            text(c,m.previous==null?"3개월 비교: 산출 불가":"3개월 전: "+number(m.previous,m.unit)+" ("+m.previousPeriod+")\n점수 변화: "+String.format(Locale.KOREA,"%+.1f점",m.component(false)-m.component(true)),13,INK,false);
            text(c,m.source+"\n"+m.reason+"\n마지막 확인: "+(m.observedAt.isEmpty()?"개별 시각 미제공":m.observedAt),12,MUTED,false);
            if(good && feed.fresh() && m.weight>0)text(c,String.format(Locale.KOREA,"종합점수 기여 %.1f점 · 가산점 제외",m.component(false)*m.weight),12,MUTED,false);
            text(c,String.format(Locale.KOREA,"점수 구간 %.2f ~ %.2f %s · %s 위험 증가",m.low,m.high,m.unit,m.direction.equals("up")?"상승 시":"하락 시"),12,MUTED,false);
            if(!m.sourceUrl.isEmpty()){String url=m.sourceUrl;TextView link=text(c,"공식 원자료 열기 ↗",13,TEAL,true);link.setOnClickListener(v->{try{startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse(url)));}catch(ActivityNotFoundException ignored){}});}
        }
        LinearLayout note=card();text(note,"원격 지표 업데이트",17,INK,true);
        text(note,"계산 기준: "+feed.method+"\n\n지표 목록·원자료·가중치·점수 구간은 서버 설정에 따라 자동 반영됩니다. 참고용 지표(가중치 0)는 종합점수에 포함하지 않습니다. 종합에 포함된 지표 중 하나라도 미수집이면 판정을 보류합니다.\n\n현재 및 3개월 전 자료에 같은 설정을 적용합니다. 과거 발표 당시의 지수를 복원한 값이 아닙니다.\n\n70점 이상 지표 2/3/4개 이상 시 +3/+6/+10점, 100점 상한. 등급: 정상 <30, 주의 <45, 경계 <60, 위기 전조 <75, 위기 ≥75. 점수는 자체 규칙이며 공식 위기확률이 아닙니다.\n\n서버 자료가 48시간 이상 갱신되지 않거나 연결 확인에 실패하면 종합 판정을 보류합니다. 앱과 서버는 각각 약 6시간 주기로 확인하므로 설정 반영에는 시간이 걸릴 수 있습니다.",13,MUTED,false);
    }

}
