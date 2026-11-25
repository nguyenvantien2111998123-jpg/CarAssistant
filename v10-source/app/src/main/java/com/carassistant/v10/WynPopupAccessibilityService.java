package com.carassistant.v10;
import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.*;
import java.util.List;
/**
 * Tracks WYN's movable overlay popup by Accessibility window bounds, captures its pixels,
 * and backgrounds the WYN Activity after an automatic AA-triggered startup has settled.
 * It never sends media transport commands or requests audio focus.
 */
public final class WynPopupAccessibilityService extends AccessibilityService {
 private static final String P="com.wynauto.wyn",TAG="CarAsistantWYN";
 private static final long CAPTURE_GAP_MS=500L,HOME_WAIT_MS=12000L,HOME_MAX_WAIT_MS=45000L;
 private final Handler main=new Handler(Looper.getMainLooper());
 private boolean busy,homeCheckScheduled;
 private long last,wynFirstSeenAt,lastEventLog;
 private int contentEvents;
 private final Runnable backgroundCheck=new Runnable(){@Override public void run(){
  if(!isAutoBackgroundPending()){homeCheckScheduled=false;return;}
  AccessibilityNodeInfo root=null;
  try{
   root=getRootInActiveWindow();
   boolean wynForeground=root!=null&&root.getPackageName()!=null&&P.contentEquals(root.getPackageName());
   long elapsed=System.currentTimeMillis()-wynFirstSeenAt;
   if(wynForeground&&contentEvents>=2&&elapsed>=HOME_WAIT_MS){
    boolean ok=performGlobalAction(GLOBAL_ACTION_HOME);
    Log.i(TAG,"WYN Home stabilized; sent HOME to keep WYN task alive in background; accepted="+ok);
    clearAutoBackgroundPending();homeCheckScheduled=false;return;
   }
   if(!wynForeground){
    // The user or system already switched away. Do not issue a global HOME action.
    Log.i(TAG,"WYN left foreground before auto-background; keeping current foreground app untouched");
    clearAutoBackgroundPending();homeCheckScheduled=false;return;
   }
   if(elapsed>=HOME_MAX_WAIT_MS){
    Log.w(TAG,"WYN did not reach stable Accessibility content in time; leaving it open rather than forcing background");
    clearAutoBackgroundPending();homeCheckScheduled=false;return;
   }
   main.postDelayed(this,2000L);
  }catch(Throwable t){Log.w(TAG,"WYN auto-background check failed",t);main.postDelayed(this,2000L);}
  finally{if(root!=null)root.recycle();}
 }};
 @Override public void onAccessibilityEvent(AccessibilityEvent e){
  if(e==null||e.getPackageName()==null)return;
  boolean wyn=P.contentEquals(e.getPackageName());
  if(wyn&&System.currentTimeMillis()-lastEventLog>=1000L){
   lastEventLog=System.currentTimeMillis();
   Log.i(TAG,"DIAG_ACCESSIBILITY_EVENT type="+AccessibilityEvent.eventTypeToString(e.getEventType())
       +" windowId="+e.getWindowId()
       +" class="+(e.getClassName()==null?"":e.getClassName())
       +" contentEvents="+contentEvents
       +" autoBackgroundPending="+isAutoBackgroundPending());
  }
  if(wyn&&isAutoBackgroundPending()){
   if(e.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED){
    if(wynFirstSeenAt==0L){wynFirstSeenAt=System.currentTimeMillis();contentEvents=0;}
    scheduleBackgroundCheck();
   }else if(e.getEventType()==AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
       ||e.getEventType()==AccessibilityEvent.TYPE_WINDOWS_CHANGED){
    if(wynFirstSeenAt==0L)wynFirstSeenAt=System.currentTimeMillis();
    contentEvents++;
    scheduleBackgroundCheck();
   }
  }
  if(!wyn||Build.VERSION.SDK_INT<30||busy||System.currentTimeMillis()-last<CAPTURE_GAP_MS)return;
  Rect b=findPopup();if(b==null)return;busy=true;last=System.currentTimeMillis();
  try{takeScreenshot(Display.DEFAULT_DISPLAY,getMainExecutor(),new TakeScreenshotCallback(){
   @Override public void onSuccess(ScreenshotResult s){HardwareBuffer h=null;try{
    h=s.getHardwareBuffer();Bitmap a=Bitmap.wrapHardwareBuffer(h,s.getColorSpace());if(a==null)return;
    Rect q=new Rect(b);if(!q.intersect(0,0,a.getWidth(),a.getHeight()))return;
    Bitmap popup=Bitmap.createBitmap(a,q.left,q.top,q.width(),q.height());
    GeminiCommandLogCapture.saveWynPopupSnapshot(popup);
    WynWarningStore.setWarning(popup);
    popup.recycle();
    Log.i(TAG,"DIAG_POPUP_SCREENSHOT_CAPTURED bounds="+q+" bitmap="+a.getWidth()+"x"+a.getHeight());
   }catch(Throwable t){Log.w(TAG,"WYN capture failed",t);}finally{if(h!=null)h.close();busy=false;}}
   @Override public void onFailure(int code){busy=false;Log.w(TAG,"WYN screenshot unavailable="+code);}
  });}catch(Throwable t){busy=false;Log.w(TAG,"WYN screenshot request failed",t);}
 }
 private void scheduleBackgroundCheck(){
  if(homeCheckScheduled)return;homeCheckScheduled=true;
  main.postDelayed(backgroundCheck,HOME_WAIT_MS);
 }
 private boolean isAutoBackgroundPending(){
  return getSharedPreferences("wyn_auto_start",MODE_PRIVATE).getBoolean("background_after_load",false);
 }
 private void clearAutoBackgroundPending(){
  getSharedPreferences("wyn_auto_start",MODE_PRIVATE).edit().putBoolean("background_after_load",false).remove("requested_at").commit();
  wynFirstSeenAt=0L;contentEvents=0;
 }
 private Rect findPopup(){try{
  int screen=getResources().getDisplayMetrics().widthPixels*getResources().getDisplayMetrics().heightPixels,best=Integer.MAX_VALUE;
  Rect out=null;List<AccessibilityWindowInfo> ws=getWindows();
  for(AccessibilityWindowInfo w:ws){AccessibilityNodeInfo n=null;try{
   n=w.getRoot();if(n==null||n.getPackageName()==null||!P.contentEquals(n.getPackageName()))continue;
   Rect r=new Rect();w.getBoundsInScreen(r);int a=Math.max(0,r.width())*Math.max(0,r.height());
   if(a>=10000&&a<=screen/2){
    Log.i(TAG,"DIAG_POPUP_CANDIDATE bounds="+r+" area="+a+" windowType="+w.getType()
        +" rootClass="+n.getClassName());
   }
   if(a>=10000&&a<=screen/2&&a<best){best=a;out=new Rect(r);}
  }finally{if(n!=null)n.recycle();}}
  return out;
 }catch(Throwable t){Log.d(TAG,"WYN window scan failed",t);return null;}}
 @Override public void onInterrupt(){}
}
