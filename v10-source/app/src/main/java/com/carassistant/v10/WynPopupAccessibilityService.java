package com.carassistant.v10;
import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.*;
import java.util.List;
public final class WynPopupAccessibilityService extends AccessibilityService {
 private static final String P="com.wynauto.wyn"; private boolean busy; private long last;
 public void onAccessibilityEvent(AccessibilityEvent e){if(e==null||e.getPackageName()==null||!P.contentEquals(e.getPackageName())||Build.VERSION.SDK_INT<30||busy||System.currentTimeMillis()-last<500)return;Rect b=find();if(b==null)return;busy=true;last=System.currentTimeMillis();try{takeScreenshot(Display.DEFAULT_DISPLAY,getMainExecutor(),new TakeScreenshotCallback(){public void onSuccess(ScreenshotResult s){HardwareBuffer h=null;try{h=s.getHardwareBuffer();Bitmap a=Bitmap.wrapHardwareBuffer(h,s.getColorSpace());if(a==null)return;Rect q=new Rect(b);if(!q.intersect(0,0,a.getWidth(),a.getHeight()))return;WynWarningStore.setWarning(Bitmap.createBitmap(a,q.left,q.top,q.width(),q.height()));Log.i("CarAsistantWYN","Popup captured "+q);}catch(Throwable t){Log.w("CarAsistantWYN","capture failed",t);}finally{if(h!=null)h.close();busy=false;}}public void onFailure(int e){busy=false;Log.w("CarAsistantWYN","screenshot unavailable="+e);}});}catch(Throwable t){busy=false;Log.w("CarAsistantWYN","request failed",t);}}
 private Rect find(){try{int screen=getResources().getDisplayMetrics().widthPixels*getResources().getDisplayMetrics().heightPixels,best=Integer.MAX_VALUE;Rect out=null;List<AccessibilityWindowInfo> ws=getWindows();for(AccessibilityWindowInfo w:ws){AccessibilityNodeInfo n=null;try{n=w.getRoot();if(n==null||n.getPackageName()==null||!P.contentEquals(n.getPackageName()))continue;Rect r=new Rect();w.getBoundsInScreen(r);int a=Math.max(0,r.width())*Math.max(0,r.height());if(a>=10000&&a<=screen/2&&a<best){best=a;out=new Rect(r);}}finally{if(n!=null)n.recycle();}}return out;}catch(Throwable t){return null;}}
 public void onInterrupt(){}
}
