package com.carassistant.v10;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Live phone-side diagnostic screen for the silent MP4 fallback flow. */
public final class MediaFallbackTestActivity extends Activity {
    private static final int BG=Color.rgb(8,13,22), SURFACE=Color.rgb(19,28,42);
    private static final int TEXT=Color.rgb(242,247,255), MUTED=Color.rgb(155,172,192);
    private static final int CYAN=Color.rgb(48,205,220), GREEN=Color.rgb(55,210,151), ORANGE=Color.rgb(255,174,77);
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final SimpleDateFormat clock=new SimpleDateFormat("HH:mm:ss",Locale.getDefault());
    private TextView connection,source,playback,mp4,mp4Time,log;
    private final StringBuilder events=new StringBuilder();
    private final Runnable refresh=new Runnable(){@Override public void run(){updateLiveStatus();handler.postDelayed(this,800L);}};

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(18),dp(22),dp(18),dp(24));scroll.addView(root);setContentView(scroll);
        addText(root,"CARASSISTANT  •  LIVE DIAGNOSTICS",11,CYAN,true);
        addText(root,"Kiểm tra Media dự phòng",25,TEXT,true);
        addText(root,"Mở màn hình này trước khi thao tác với YouTube, Zing hoặc ứng dụng media khác. Trạng thái tự cập nhật mỗi 0,8 giây.",13,MUTED,false);
        connection=addCard(root,"ANDROID AUTO");source=addCard(root,"NGUỒN MEDIA BÊN NGOÀI");
        playback=addCard(root,"TRẠNG THÁI NGUỒN");mp4=addCard(root,"MP4 DỰ PHÒNG");
        mp4Time=addCard(root,"THỜI ĐIỂM MP4 BẮT ĐẦU");
        addText(root,"ĐÁNH DẤU CÁC BƯỚC TEST",11,CYAN,true);
        addButton(root,"1  •  ĐANG PHÁT MEDIA",()->mark("Media đang phát"));
        addButton(root,"2  •  ĐANG TẠM DỪNG MEDIA",()->mark("Media đang tạm dừng"));
        addButton(root,"3  •  ĐÃ TẮT NỘI DUNG / GIỮ APP",()->mark("Đã tắt nội dung nhưng giữ app media"));
        addButton(root,"4  •  ĐÃ LOAD LẠI TRANG",()->mark("Đã tải lại trang trong app media"));
        addButton(root,"5  •  ĐÃ ĐÓNG TASK APP MEDIA",()->mark("Đã đóng task app media"));
        addText(root,"NHẬT KÝ TEST",11,CYAN,true);log=addCard(root,"SỰ KIỆN");
        events.append("Mở màn hình: ").append(now()).append('\n');updateLiveStatus();
    }
    @Override protected void onResume(){super.onResume();handler.removeCallbacks(refresh);handler.post(refresh);}
    @Override protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}
    private void updateLiveStatus(){
        boolean aa=MediaSessionBridge.isAndroidAutoConnected(this);
        MediaSessionBridge.Snapshot s=MediaSessionBridge.getSnapshot();
        boolean has=s!=null&&s.packageName!=null&&!s.packageName.isEmpty();
        connection.setText("Android Auto: "+(aa?"ĐÃ KẾT NỐI":"CHƯA KẾT NỐI"));connection.setTextColor(aa?GREEN:ORANGE);
        source.setText(has?"Nguồn: "+s.packageName+"\nNội dung: "+safe(s.title):"Không thấy nguồn media ngoài đang được CarAsistant chọn.");
        source.setTextColor(has?GREEN:ORANGE);
        playback.setText(has?(s.playing?"PLAYING • nguồn đang phát":"PAUSED • session nguồn vẫn còn"):"NO SOURCE • snapshot hiện tại không có nguồn");
        playback.setTextColor(has?CYAN:ORANGE);
        boolean playing=MediaSessionBridge.isFallbackMp4Playing();
        mp4.setText(playing?"ĐANG PHÁT (cửa sổ dự kiến khoảng 350 ms)":MediaSessionBridge.getFallbackMp4LastEvent());
        mp4.setTextColor(playing?GREEN:MUTED);
        long started=MediaSessionBridge.getFallbackMp4LastStartedAt();
        mp4Time.setText(started>0?clock.format(new Date(started)):"Chưa ghi nhận MP4 bắt đầu phát");
    }
    private void mark(String message){
        MediaSessionBridge.Snapshot s=MediaSessionBridge.getSnapshot();
        events.append(now()).append("  ").append(message).append('\n').append("    Nguồn: ");
        events.append(s==null||s.packageName==null||s.packageName.isEmpty()?"NO SOURCE":s.packageName+(s.playing?" / PLAYING":" / PAUSED"));
        events.append("; MP4=").append(MediaSessionBridge.isFallbackMp4Playing()?"PLAYING":"PAUSED/STOPPED").append('\n');
        log.setText(events.toString());updateLiveStatus();
    }
    private String now(){return clock.format(new Date());}
    private String safe(String v){return v==null||v.isEmpty()?"(không có metadata)":v;}
    private TextView addCard(LinearLayout root,String heading){
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(14),dp(12),dp(14),dp(12));
        GradientDrawable bg=new GradientDrawable();bg.setColor(SURFACE);bg.setCornerRadius(dp(14));bg.setStroke(dp(1),Color.rgb(35,51,70));card.setBackground(bg);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(8),0,dp(8));root.addView(card,p);
        TextView label=new TextView(this);label.setText(heading);label.setTextColor(CYAN);label.setTextSize(10);label.setTypeface(Typeface.DEFAULT,Typeface.BOLD);card.addView(label);
        TextView value=new TextView(this);value.setTextColor(TEXT);value.setTextSize(14);value.setPadding(0,dp(6),0,0);card.addView(value);return value;
    }
    private TextView addText(LinearLayout root,String value,float size,int color,boolean bold){
        TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        t.setPadding(0,dp(5),0,dp(7));root.addView(t,new LinearLayout.LayoutParams(-1,-2));return t;
    }
    private void addButton(LinearLayout root,String label,Runnable action){
        TextView b=new TextView(this);b.setText(label);b.setTextColor(Color.WHITE);b.setTextSize(12);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setGravity(Gravity.CENTER);
        GradientDrawable bg=new GradientDrawable();bg.setColor(Color.rgb(25,85,110));bg.setCornerRadius(dp(12));b.setBackground(bg);b.setPadding(dp(10),dp(14),dp(10),dp(14));
        b.setOnClickListener(v->action.run());LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(4),0,dp(4));root.addView(b,p);
    }
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
}