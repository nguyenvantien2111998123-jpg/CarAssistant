package com.carassistant.v10.hook;

import android.app.Activity;
import android.content.*;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import de.robv.android.xposed.*;

/** Draggable media panel drawn inside Gearhead's own window, not a phone system overlay. */
public final class FloatingMediaPopupHook {
    private static final String AA="com.google.android.projection.gearhead";
    private static final String UPDATE="com.carassistant.v10.FLOATING_MEDIA_UPDATE";
    private static final String CONTROL="com.carassistant.v10.FLOATING_MEDIA_CONTROL";
    private static final Handler H=new Handler(Looper.getMainLooper());
    private static final Map<ViewGroup,Panel> PANELS=Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile Context context;
    private static volatile boolean installed, receiverInstalled, playing;
    private static volatile String title="Đang phát media", artist="CarAsistant";
    private static volatile long duration, position, positionAt;
    private FloatingMediaPopupHook(){}

    public static void install(){
        if(installed)return; installed=true;
        try {
            XposedHelpers.findAndHookMethod(Activity.class,"onResume",new XC_MethodHook(){
                @Override protected void afterHookedMethod(MethodHookParam p){
                    Activity a=(Activity)p.thisObject;
                    if(AA.equals(a.getPackageName())) H.post(()->attach(a));
                }
            });
            XposedBridge.log("[AACast FloatingMedia] Activity.onResume hook installed");
        } catch(Throwable t){installed=false;XposedBridge.log("[AACast FloatingMedia] hook failed "+t);}
    }
    private static void attach(Activity a){
        try{
            context=a.getApplicationContext(); if(context==null)context=a;
            registerUpdates(context);
            View decor=a.getWindow().getDecorView();
            if(!(decor instanceof ViewGroup))return;
            ViewGroup root=(ViewGroup)decor;
            if(PANELS.containsKey(root)){PANELS.get(root).refresh();return;}
            Panel panel=new Panel(context,root); PANELS.put(root,panel);
            root.addView(panel.layer,new ViewGroup.LayoutParams(-1,-1));
            panel.refresh();
            XposedBridge.log("[AACast FloatingMedia] attached to "+a.getClass().getName());
        }catch(Throwable t){XposedBridge.log("[AACast FloatingMedia] attach failed "+t);}
    }
    private static void registerUpdates(Context c){
        if(receiverInstalled)return;
        synchronized(FloatingMediaPopupHook.class){
            if(receiverInstalled)return;
            try{
                BroadcastReceiver receiver=new BroadcastReceiver(){
                    @Override public void onReceive(Context c,Intent i){
                        if(i==null||!UPDATE.equals(i.getAction()))return;
                        title=val(i.getStringExtra("title"),"Đang phát media");
                        artist=val(i.getStringExtra("artist"),"");
                        playing=i.getBooleanExtra("playing",false);
                        duration=Math.max(0,i.getLongExtra("duration",0));
                        position=Math.max(0,i.getLongExtra("position",0));
                        positionAt=SystemClock.elapsedRealtime();
                        H.post(()->{synchronized(PANELS){for(Panel p:PANELS.values())if(p!=null)p.refresh();}});
                    }
                };
                IntentFilter f=new IntentFilter(UPDATE);
                if(Build.VERSION.SDK_INT>=33)c.registerReceiver(receiver,f,Context.RECEIVER_EXPORTED);
                else c.registerReceiver(receiver,f);
                receiverInstalled=true;
            }catch(Throwable t){XposedBridge.log("[AACast FloatingMedia] receiver failed "+t);}
        }
    }
    private static String val(String s,String fallback){return s==null||s.trim().isEmpty()?fallback:s;}
    private static void command(String cmd,long pos){
        Context c=context;if(c==null)return;
        Intent i=new Intent(CONTROL);
        i.setComponent(new ComponentName("com.carassistant.v10","com.carassistant.v10.V10ControlReceiver"));
        i.putExtra("command",cmd);if(pos>=0)i.putExtra("position",pos);
        c.sendBroadcast(i);
    }
    private static void openV10(){
        Context c=context;if(c==null)return;
        Intent i=new Intent("com.carassistant.v10.STEERING_MEDIA_OPEN");
        i.setComponent(new ComponentName("com.carassistant.v10","com.carassistant.v10.V10ControlReceiver"));
        c.sendBroadcast(i);
    }
    private static final class Panel{
        final Context c;final ViewGroup root;final FrameLayout layer;final LinearLayout card,header,controls;
        final TextView titleText,artistText,play,collapse,time;final SeekBar seek;
        final FrameLayout.LayoutParams cardLp;
        boolean collapsed,seeking;float downX,downY;int startX,startY;
        Panel(Context c,ViewGroup root){
            this.c=c;this.root=root;layer=new FrameLayout(c);layer.setClickable(false);
            card=new LinearLayout(c);card.setOrientation(1);card.setPadding(dp(12),dp(8),dp(12),dp(8));
            GradientDrawable bg=new GradientDrawable();bg.setColor(Color.rgb(20,25,31));bg.setCornerRadius(dp(16));bg.setStroke(dp(1),Color.rgb(77,145,190));card.setBackground(bg);card.setElevation(dp(12));card.setClickable(true);
            cardLp=new FrameLayout.LayoutParams(dp(360),-2,Gravity.TOP|Gravity.RIGHT);cardLp.topMargin=dp(42);cardLp.rightMargin=dp(18);layer.addView(card,cardLp);
            header=new LinearLayout(c);header.setGravity(Gravity.CENTER_VERTICAL);header.setOrientation(0);
            TextView grip=label("⠿",20);grip.setGravity(Gravity.CENTER);header.addView(grip,new LinearLayout.LayoutParams(dp(28),dp(38)));
            LinearLayout labels=new LinearLayout(c);labels.setOrientation(1);
            titleText=label(title,14);titleText.setMaxLines(1);artistText=label(artist,11);artistText.setMaxLines(1);labels.addView(titleText);labels.addView(artistText);
            header.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
            collapse=label("−",22);collapse.setGravity(Gravity.CENTER);header.addView(collapse,new LinearLayout.LayoutParams(dp(30),dp(38)));
            TextView close=label("×",20);close.setGravity(Gravity.CENTER);header.addView(close,new LinearLayout.LayoutParams(dp(28),dp(38)));
            card.addView(header);header.setOnTouchListener(this::drag);
            titleText.setOnClickListener(v->openV10());artistText.setOnClickListener(v->openV10());
            collapse.setOnClickListener(v->{collapsed=!collapsed;refresh();});close.setOnClickListener(v->layer.setVisibility(View.GONE));
            seek=new SeekBar(c);seek.setMax(1000);card.addView(seek,new LinearLayout.LayoutParams(-1,dp(25)));
            time=label("00:00 / 00:00",10);time.setGravity(Gravity.RIGHT);card.addView(time);
            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
                public void onProgressChanged(SeekBar b,int v,boolean user){if(user&&duration>0)time.setText(fmt(duration*v/1000)+" / "+fmt(duration));}
                public void onStartTrackingTouch(SeekBar b){seeking=true;}
                public void onStopTrackingTouch(SeekBar b){seeking=false;if(duration>0)command("seek_to",duration*b.getProgress()/1000);}
            });
            controls=new LinearLayout(c);controls.setGravity(Gravity.CENTER);card.addView(controls,new LinearLayout.LayoutParams(-1,dp(46)));
            add("⏮","previous",40);add("−15s","seek_back",48);
            play=button("▶");controls.addView(play,new LinearLayout.LayoutParams(dp(44),dp(40)));play.setOnClickListener(v->command("play_pause",-1));
            add("+15s","seek_forward",48);add("⏭","next",40);
            TextView hint=label("Kéo tiêu đề để di chuyển · Chạm tên bài để mở V10",9);hint.setGravity(Gravity.CENTER);card.addView(hint,new LinearLayout.LayoutParams(-1,dp(20)));
            H.post(tick);
        }
        void add(String s,String cmd,int w){TextView b=button(s);controls.addView(b,new LinearLayout.LayoutParams(dp(w),dp(40)));b.setOnClickListener(v->command(cmd,-1));}
        TextView button(String s){TextView v=label(s,12);v.setGravity(Gravity.CENTER);GradientDrawable g=new GradientDrawable();g.setColor(Color.rgb(39,48,58));g.setCornerRadius(dp(9));v.setBackground(g);return v;}
        TextView label(String s,float size){TextView v=new TextView(c);v.setText(s);v.setTextSize(size);v.setTextColor(Color.WHITE);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
        boolean drag(View v,MotionEvent e){
            switch(e.getActionMasked()){
                case MotionEvent.ACTION_DOWN:downX=e.getRawX();downY=e.getRawY();startX=card.getLeft();startY=card.getTop();return true;
                case MotionEvent.ACTION_MOVE:
                    cardLp.gravity=Gravity.TOP|Gravity.LEFT;
                    cardLp.leftMargin=Math.max(0,Math.min(root.getWidth()-card.getWidth(),startX+Math.round(e.getRawX()-downX)));
                    cardLp.topMargin=Math.max(0,Math.min(root.getHeight()-card.getHeight(),startY+Math.round(e.getRawY()-downY)));
                    card.setLayoutParams(cardLp);return true;
                case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:return true;
                default:return true;
            }
        }
        void refresh(){
            titleText.setText(title);artistText.setText(artist);play.setText(playing?"Ⅱ":"▶");collapse.setText(collapsed?"+":"−");
            for(int i=1;i<card.getChildCount();i++)card.getChildAt(i).setVisibility(collapsed?View.GONE:View.VISIBLE);
            layer.setVisibility(View.VISIBLE);updateProgress();
        }
        void updateProgress(){long p=position+(playing?Math.max(0,SystemClock.elapsedRealtime()-positionAt):0);if(duration>0)p=Math.min(p,duration);if(!seeking&&duration>0)seek.setProgress((int)Math.min(1000,p*1000/duration));time.setText(fmt(p)+" / "+fmt(duration));}
        Runnable tick=new Runnable(){public void run(){if(layer.getParent()!=null&&layer.getVisibility()==View.VISIBLE)updateProgress();H.postDelayed(this,1000);}};
        String fmt(long ms){long s=Math.max(0,ms/1000);return String.format(Locale.ROOT,"%02d:%02d",s/60,s%60);}
        int dp(float x){return Math.round(x*c.getResources().getDisplayMetrics().density);}
    }
}
