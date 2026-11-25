package com.carassistant.v10.hook;
import android.app.Application;
import android.content.*;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import java.lang.reflect.*;
import de.robv.android.xposed.*;
/** Detects Gearhead's GhFacetBar connection edge and requests CarAsistant Car Activity. */
public final class GearheadAutoOpen {
 private static final String TAG="AACastAutoOpen";
 private static final Handler H=new Handler(Looper.getMainLooper());
 private static Context app; private static DisplayManager dm;
 private static boolean connected,requested,installed,wynRequested; private static int retries,wynRetries;
 private static final Runnable wynRetry=new Runnable(){public void run(){
  if(!connected||wynRequested||app==null)return;
  if(startWyn()){wynRequested=true;Log.i(TAG,"WYN startup requested independently of media restore");}
  else if(++wynRetries<8)H.postDelayed(this,2500);
  else Log.w(TAG,"WYN startup retries exhausted for this AA connection");
 }};
 private static final Runnable retry=new Runnable(){public void run(){
  if(!connected||requested||app==null)return;
  if(launch()){requested=true;Log.i(TAG,"CarAsistant launch request sent to Gearhead");H.removeCallbacks(wynRetry);H.postDelayed(wynRetry,25000);}
  else if(++retries<8)H.postDelayed(this,1500);
  else Log.w(TAG,"Gearhead launch retries exhausted for this connection");
 }};
 public static synchronized void install(){if(installed)return;installed=true;
  try{XposedHelpers.findAndHookMethod(Application.class,"attach",Context.class,new XC_MethodHook(){
   protected void afterHookedMethod(MethodHookParam p){Context c=(Context)p.args[0];
    if(c!=null&&"com.google.android.projection.gearhead".equals(c.getPackageName()))
     H.post(()->attach(c.getApplicationContext()!=null?c.getApplicationContext():c));
   }});}catch(Throwable t){Log.e(TAG,"Install failed",t);}
 }
 private static void attach(Context c){if(app!=null)return;app=c;dm=(DisplayManager)c.getSystemService(Context.DISPLAY_SERVICE);
  if(dm==null)return;dm.registerDisplayListener(new DisplayManager.DisplayListener(){
   public void onDisplayAdded(int id){scan();} public void onDisplayChanged(int id){scan();}
   public void onDisplayRemoved(int id){scan();}
  },H);scan();
 }
 private static void scan(){if(dm==null)return;boolean found=false;
  for(Display d:dm.getDisplays())if(d!=null&&d.getName()!=null&&d.getName().contains("GhFacetBar")&&d.getState()==Display.STATE_ON){found=true;break;}
  if(found&&!connected){connected=true;requested=false;wynRequested=false;retries=0;wynRetries=0;H.removeCallbacks(retry);H.removeCallbacks(wynRetry);H.postDelayed(retry,1800);}
  else if(!found&&connected){connected=false;requested=false;wynRequested=false;retries=0;wynRetries=0;H.removeCallbacks(retry);H.removeCallbacks(wynRetry);Log.i(TAG,"Android Auto disconnected");}
 }

 private static boolean startWyn(){
  try{
   Intent mark=new Intent("com.carassistant.v10.ACTION_AUTO_BACKGROUND_WYN");
   mark.setComponent(new ComponentName("com.carassistant.v10","com.carassistant.v10.WynAutoStartReceiver"));
   mark.setPackage("com.carassistant.v10");
   app.sendBroadcast(mark);
   Intent launch=app.getPackageManager().getLaunchIntentForPackage("com.wynauto.wyn");
   if(launch==null){Log.w(TAG,"WYN launch activity unavailable; is com.wynauto.wyn installed?");return false;}
   launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
   launch.putExtra("com.carassistant.v10.AUTO_BACKGROUND_AFTER_LOAD",true);
   app.startActivity(launch);
   Log.i(TAG,"Started WYN; Accessibility will background it after its home UI stabilizes");
   return true;
  }catch(Throwable t){Log.w(TAG,"WYN could not be started from Gearhead yet",t);return false;}
 }
 private static boolean launch(){try{
  Class<?> c=XposedHelpers.findClassIfExists("com.google.android.projection.gearhead.service.CarSystemUiControllerService",app.getClassLoader());
  if(c==null)return false;Method target=null;
  for(Class<?> t=c;t!=null&&target==null;t=t.getSuperclass())for(Method m:t.getDeclaredMethods())
   if(Modifier.isStatic(m.getModifiers())&&"a".equals(m.getName())&&m.getParameterTypes().length==1&&m.getParameterTypes()[0]==Intent.class){target=m;break;}
  if(target==null)return false;Intent i=new Intent();i.setComponent(new ComponentName("com.carassistant.v10","com.carassistant.v10.AACastCarService"));
  i.putExtra("android.intent.extra.PACKAGE_NAME","com.carassistant.v10");i.putExtra("com.carassistant.v10.AUTO_OPEN_ON_AA",true);
  target.setAccessible(true);target.invoke(null,i);return true;
 }catch(Throwable t){Log.w(TAG,"Gearhead not ready",t);return false;}}
}
