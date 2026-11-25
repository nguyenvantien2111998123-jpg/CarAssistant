package com.carassistant.v10;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
public final class WynAutoStartReceiver extends BroadcastReceiver {
 @Override public void onReceive(Context context, Intent intent) {
  if(intent==null||!"com.carassistant.v10.ACTION_AUTO_BACKGROUND_WYN".equals(intent.getAction()))return;
  context.getSharedPreferences("wyn_auto_start",Context.MODE_PRIVATE).edit().putBoolean("background_after_load",true).putLong("requested_at",System.currentTimeMillis()).commit();
  Log.i("CarAsistantWYN","Automatic WYN startup armed; waiting for stable Home UI");
 }
}
