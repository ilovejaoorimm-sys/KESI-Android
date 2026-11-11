package com.kesi.widget;
import android.content.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;

public final class RemoteRepository {
    public static final String URL="https://raw.githubusercontent.com/ilovejaoorimm-sys/KESI-Android/main/data/feed.json";
    private static final String PREF="kesi_remote_v1";
    public static RemoteFeed load(Context c){try{SharedPreferences p=c.getSharedPreferences(PREF,0);String s=p.getString("json",null);if(s==null)return null;RemoteFeed f=RemoteFeed.parse(s,Instant.now());f.refreshFailed=p.getBoolean("failed",false);return f;}catch(Exception e){return null;}}
    public static String status(Context c){return c.getSharedPreferences(PREF,0).getString("status","원격 지표 연결 대기 · 기본 지표 사용");}
    @android.annotation.SuppressLint("ApplySharedPref")
    public static RemoteFeed refresh(Context c){
        SharedPreferences p=c.getSharedPreferences(PREF,0);HttpURLConnection con=null;
        try{
            con=(HttpURLConnection)new java.net.URL(URL).openConnection();con.setConnectTimeout(10000);con.setReadTimeout(10000);con.setInstanceFollowRedirects(false);con.setUseCaches(false);
            if(con.getResponseCode()!=200)throw new IOException("HTTP "+con.getResponseCode());
            String raw;
            try(InputStream in=con.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){out.write(b,0,n);if(out.size()>1_000_000)throw new IOException("size");}raw=out.toString(StandardCharsets.UTF_8.name());
            }
            RemoteFeed f=RemoteFeed.parse(raw,Instant.now()),old=load(c);
            if(!f.fresh())throw new IOException("stale");
            if(old!=null&&(f.revision<old.revision||OffsetDateTime.parse(f.generatedAt).toInstant().isBefore(OffsetDateTime.parse(old.generatedAt).toInstant())))throw new IOException("rollback");
            if(!p.edit().putString("json",raw).putBoolean("failed",false).putString("status","원격 지표 자동 반영 · 설정 v"+f.revision).commit())throw new IOException("storage");return f;
        }catch(Exception e){p.edit().putBoolean("failed",true).putString("status","원격 갱신 실패 · "+(p.contains("json")?"이전 자료 표시, 종합 판정 보류":"기본 지표 사용")).commit();return load(c);}
        finally{if(con!=null)con.disconnect();}
    }
}
