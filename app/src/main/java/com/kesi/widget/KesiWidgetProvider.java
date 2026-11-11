package com.kesi.widget;
import android.app.PendingIntent;
import android.appwidget.*;
import android.content.*;
import android.widget.RemoteViews;

public final class KesiWidgetProvider extends AppWidgetProvider {
    @Override public void onEnabled(Context c){RefreshJobService.schedule(c);RefreshJobService.requestNow(c);}
    @Override public void onUpdate(Context c,AppWidgetManager manager,int[] ids){
        RefreshJobService.schedule(c);KesiData d=DataRepository.load(c);
        for(int id:ids)update(c,manager,id,d);
    }
    @Override public void onAppWidgetOptionsChanged(Context c,AppWidgetManager m,int id,android.os.Bundle b){update(c,m,id,DataRepository.load(c));}
    public static void updateAll(Context c,KesiData d){AppWidgetManager m=AppWidgetManager.getInstance(c);for(int id:m.getAppWidgetIds(new ComponentName(c,KesiWidgetProvider.class)))update(c,m,id,d);}
    private static void update(Context c,AppWidgetManager m,int id,KesiData d){
        RemoteViews v=new RemoteViews(c.getPackageName(),R.layout.widget_kesi);
        v.setTextViewText(R.id.score,d.scoreText());v.setTextViewText(R.id.status,KesiData.level(d.score(false))+"  ·  "+d.count()+"/6");
        v.setTextViewText(R.id.momentum,d.changeText());v.setTextViewText(R.id.updated,d.releaseSummary()+"\n확인 "+d.checked+" KST");
        RemoteFeed remote=RemoteRepository.load(c);
        if(remote!=null){
            v.setTextViewText(R.id.score,remote.scoreText());v.setTextViewText(R.id.status,KesiData.level(remote.score(false))+" · "+remote.count()+"/"+remote.entries.size());
            v.setTextViewText(R.id.momentum,remote.changeText());v.setTextViewText(R.id.updated,remote.publication()+"\n원격 v"+remote.revision+(remote.fresh()?"":" · 갱신 확인 필요"));
        }
        Intent i=new Intent(c,MainActivity.class);PendingIntent pi=PendingIntent.getActivity(c,0,i,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);v.setOnClickPendingIntent(R.id.widgetRoot,pi);m.updateAppWidget(id,v);
    }
}
