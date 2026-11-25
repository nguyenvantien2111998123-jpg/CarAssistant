package com.carassistant.v10;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
public final class WynWarningStore {
 private static volatile Bitmap warning;
 private WynWarningStore() {}
 public static Bitmap getWarning(){Bitmap b=warning;return b!=null&&!b.isRecycled()?b:null;}
 public static synchronized void setWarning(Bitmap b){if(b==null||b.isRecycled())return;warning=b.copy(Bitmap.Config.ARGB_8888,false);MediaSessionBridge.onWynWarningChanged();V10CarActivity.notifyWynWarningChanged();}
 public static Bitmap composeMediaArtwork(Bitmap media){Bitmap a=getWarning();if(a==null)return media;int w=512,h=512;Bitmap o=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(o);c.drawColor(Color.DKGRAY);Paint p=new Paint(3);draw(c,a,new Rect(0,0,w,h/3),p);if(media!=null&&!media.isRecycled())draw(c,media,new Rect(0,h/3,w,h),p);return o;}
 public static Bitmap makeTouchIcon(){Bitmap a=getWarning();if(a==null)return null;int s=128;Bitmap o=Bitmap.createBitmap(s,s,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(o);Paint p=new Paint(3);android.graphics.Path path=new android.graphics.Path();path.addCircle(s/2f,s/2f,s/2f,android.graphics.Path.Direction.CW);c.save();c.clipPath(path);draw(c,a,new Rect(0,0,s,s),p);c.restore();return o;}
 private static void draw(Canvas c,Bitmap b,Rect d,Paint p){float z=Math.max(d.width()/(float)b.getWidth(),d.height()/(float)b.getHeight());int cw=Math.max(1,(int)(d.width()/z)),ch=Math.max(1,(int)(d.height()/z)),x=(b.getWidth()-cw)/2,y=(b.getHeight()-ch)/2;c.drawBitmap(b,new Rect(x,y,x+cw,y+ch),d,p);}
}
