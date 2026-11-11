package com.kesi.widget;
import android.app.job.*;
import android.content.*;
import java.util.concurrent.Future;

public final class RefreshJobService extends JobService {
    private Future<?> work;
    public static void schedule(Context c){
        JobScheduler js=(JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if(js.getPendingJob(6201)==null)js.schedule(new JobInfo.Builder(6201,new ComponentName(c,RefreshJobService.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setPeriodic(6*60*60*1000L,60*60*1000L).build());
    }
    public static void requestNow(Context c){
        JobScheduler js=(JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        js.schedule(new JobInfo.Builder(6202,new ComponentName(c,RefreshJobService.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(0).build());
    }
    @Override public boolean onStartJob(JobParameters p){
        work=DataRepository.EXECUTOR.submit(()->{KesiData d=DataRepository.refresh(getApplicationContext());if(!Thread.currentThread().isInterrupted()){KesiWidgetProvider.updateAll(this,d);jobFinished(p,false);}});return true;
    }
    @Override public boolean onStopJob(JobParameters p){if(work!=null)work.cancel(true);return true;}
}
