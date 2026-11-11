package com.kesi.widget;

import android.content.Context;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.*;

public final class DataRepository {
    private static final String PREF="kesi_verified_v2";
    public static final ExecutorService EXECUTOR=Executors.newSingleThreadExecutor();
    public static KesiData load(Context c){
        try{String raw=c.getSharedPreferences(PREF,0).getString("json",null);return raw==null?new KesiData():KesiData.parse(raw);}
        catch(Exception e){return new KesiData();}
    }
    // Synchronous commit runs on the worker, before the widget is published.
    @android.annotation.SuppressLint("ApplySharedPref")
    public static synchronized KesiData refresh(Context c){
        if(RemoteRepository.refresh(c)!=null)return load(c);
        KesiData d=load(c);OfficialCollector collector=new OfficialCollector();
        ExecutorService pool=Executors.newFixedThreadPool(3);
        try{
            java.util.List<Callable<KesiData.Metric>> tasks=new java.util.ArrayList<>();
            for(int i=0;i<6;i++){final int index=i;tasks.add(()->collector.collect(index,KesiData.today()));}
            java.util.List<Future<KesiData.Metric>> futures=pool.invokeAll(tasks,100,TimeUnit.SECONDS);
            for(int i=0;i<6;i++)try{d.metrics[i]=futures.get(i).get();}catch(Exception e){
                KesiData.Metric m=d.metrics[i];m.state="missing";
                Throwable cause=e.getCause();m.note=cause!=null&&cause.getMessage()!=null?cause.getMessage():"연결 실패 또는 수집 시간 초과";
            }
        }catch(InterruptedException e){Thread.currentThread().interrupt();for(KesiData.Metric m:d.metrics){m.state="missing";m.note="수집 중단";}}
        finally{pool.shutdownNow();}
        d.checked=ZonedDateTime.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        try{c.getSharedPreferences(PREF,0).edit().putString("json",d.json().toString()).commit();}catch(Exception ignored){}
        return d;
    }
}
