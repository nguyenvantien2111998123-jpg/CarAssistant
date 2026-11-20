package com.carassistant.v10;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;
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

/** Standalone phone-only test. MediaSessionBridge keeps monitoring while this screen is backgrounded. */
public final class MediaFallbackTestActivity extends Activity {
    private static final int BG=Color.rgb(8,13,22), SURFACE=Color.rgb(19,28,42);
    private static final int TEXT=Color.rgb(242,247,255), MUTED=Color.rgb(155,172,192);
    private static final int CYAN=Color.rgb(48,205,220), GREEN=Color.rgb(55,210,151), ORANGE=Color.rgb(255,174,77);
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final SimpleDateFormat clock=new SimpleDateFormat("HH:mm:ss",Locale.getDefault());
    private TextView permission,mode,source,playback,selectedVideo,mp4,mp4Time,log;
    private static final int REQUEST_TEST_VIDEO = 7104;
    private final StringBuilder events=new StringBuilder();
    private final Runnable refresh=new Runnable(){@Override public void run(){updateLiveStatus();handler.postDelayed(this,800L);}};

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(18),dp(22),dp(18),dp(24));scroll.addView(root);setContentView(scroll);
        addText(root,"CARASSISTANT  •  PHONE-ONLY TEST",11,CYAN,true);
        addText(root,"Kiểm tra Media dự phòng",25,TEXT,true);
        addText(root,"Không cần Android Auto. Bật test rồi chuyển sang YouTube; MediaSessionBridge tiếp tục giám sát khi màn hình này ở nền.",13,MUTED,false);
        permission=addCard(root,"QUYỀN TRUY CẬP MEDIA");
        mode=addCard(root,"CHẾ ĐỘ TEST ĐỘC LẬP");
        source=addCard(root,"DANH SÁCH MEDIASESSION NGOÀI");
        playback=addCard(root,"KẾT LUẬN NGUỒN HỢP LỆ");
        selectedVideo=addCard(root,"VIDEO TEST ĐƯỢC CHỌN");
        addButton(root,"CHỌN VIDEO TEST (1000003919.mp4)",this::chooseTestVideo);
        mp4=addCard(root,"MP4 DỰ PHÒNG");
        mp4Time=addCard(root,"THỜI ĐIỂM MP4 BẮT ĐẦU");
        addButton(root,"BẬT TEST / BẮT ĐẦU GIÁM SÁT",()->{
            if(!MediaSessionBridge.hasAccess(this)){MediaSessionBridge.requestAccess(this);return;}
            if(MediaSessionBridge.getPhoneTestVideoUri(this)==null){Toast.makeText(this,"Chọn video test MP4 trước",Toast.LENGTH_LONG).show();chooseTestVideo();return;}
            MediaSessionBridge.setPhoneTestMode(this,true);
            mark("Đã bật chế độ test độc lập");
        });
        addButton(root,"TẮT TEST / DỪNG MP4",()->{
            MediaSessionBridge.setPhoneTestMode(this,false);
            mark("Đã tắt chế độ test độc lập");
        });
        addText(root,"QUY TRÌNH TRÊN POCO F1",11,CYAN,true);
        addText(root,"1. Mở YouTube và phát video: MP4 không chạy.\n2. Tạm dừng: MP4 vẫn không chạy.\n3. Đóng nội dung nhưng giữ YouTube: kiểm tra session còn tồn tại.\n4. Tải lại trang: MP4 chỉ chạy nếu không còn nguồn hợp lệ.\n5. Đóng hẳn YouTube: nếu session biến mất, video test chạy sau 3,5 giây, phát lặp và có tiếng báo; khi session nguồn xuất hiện lại, video dừng.",13,TEXT,false);
        addText(root,"NHẬT KÝ TEST",11,CYAN,true);log=addCard(root,"SỰ KIỆN");
        events.append("Mở màn hình: ").append(now()).append('\n');updateLiveStatus();
    }
    @Override protected void onResume(){super.onResume();handler.removeCallbacks(refresh);handler.post(refresh);}
    @Override protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}
    @Override protected void onDestroy(){handler.removeCallbacks(refresh);super.onDestroy();}
    private void updateLiveStatus(){
        boolean access=MediaSessionBridge.hasAccess(this);
        permission.setText(access?"Notification Access: ĐÃ CẤP":"Notification Access: CHƯA CẤP — nhấn BẬT TEST để mở cài đặt");
        permission.setTextColor(access?GREEN:ORANGE);
        boolean enabled=MediaSessionBridge.isPhoneTestModeEnabled(this);
        mode.setText(enabled?"ĐANG BẬT • Không phụ thuộc Android Auto":"ĐANG TẮT • Không ảnh hưởng chế độ trên xe");
        mode.setTextColor(enabled?GREEN:MUTED);
        source.setText(MediaSessionBridge.getActiveSessionsDiagnostic());
        source.setTextColor(MediaSessionBridge.getUsableExternalSessionCount()>0?GREEN:ORANGE);
        int count=MediaSessionBridge.getUsableExternalSessionCount();
        playback.setText(count>0?"CÒN "+count+" SESSION NGUỒN HỢP LỆ — MP4 phải dừng":"KHÔNG CÒN SESSION NGUỒN HỢP LỆ");
        playback.setTextColor(count>0?GREEN:ORANGE);
        Uri video=MediaSessionBridge.getPhoneTestVideoUri(this);
        selectedVideo.setText(video==null?"CHƯA CHỌN — hãy chọn tệp 1000003919.mp4":video.getLastPathSegment()+"\\n"+video);
        selectedVideo.setTextColor(video==null?ORANGE:GREEN);
        boolean playing=MediaSessionBridge.isFallbackMp4Playing();
        mp4.setText(playing?"ĐANG PHÁT • có tiếng báo test":MediaSessionBridge.getFallbackMp4LastEvent());
        mp4.setTextColor(playing?GREEN:MUTED);
        long started=MediaSessionBridge.getFallbackMp4LastStartedAt();
        mp4Time.setText(started>0?clock.format(new Date(started)):"Chưa ghi nhận MP4 bắt đầu phát");
    }
    private void mark(String message){
        events.append(now()).append("  ").append(message).append('\n')
                .append("    session hợp lệ=").append(MediaSessionBridge.getUsableExternalSessionCount())
                .append("; MP4=").append(MediaSessionBridge.isFallbackMp4Playing()?"PLAYING":"STOPPED/PAUSED").append('\n');
        log.setText(events.toString());updateLiveStatus();
    }
    private void chooseTestVideo(){
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        try{startActivityForResult(intent,REQUEST_TEST_VIDEO);}catch(RuntimeException error){
            Toast.makeText(this,"Không mở được trình chọn video: "+error.getMessage(),Toast.LENGTH_LONG).show();
        }
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode!=REQUEST_TEST_VIDEO||resultCode!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();
        try{
            int flags=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            getContentResolver().takePersistableUriPermission(uri,flags&Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }catch(RuntimeException ignored){}
        MediaSessionBridge.setPhoneTestVideoUri(this,uri);
        mark("Đã chọn video test: "+uri.getLastPathSegment());
    }
    private String now(){return clock.format(new Date());}
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