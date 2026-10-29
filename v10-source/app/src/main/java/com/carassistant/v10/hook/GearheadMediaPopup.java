package com.carassistant.v10.hook;

import android.app.Activity;
import android.content.Context;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Draggable media controller hosted in Gearhead's own decor view.
 *
 * This intentionally does not use TYPE_APPLICATION_OVERLAY: that would overlay
 * the phone display, not the Android Auto projection UI. The provider is queried
 * off the UI thread and controls are sent to the app's explicit receiver.
 */
public final class GearheadMediaPopup {
    private static final String TAG = "[AACast MediaPopup]";
    private static final String GEARHEAD = "com.google.android.projection.gearhead";
    private static final String PROVIDER_URI = "content://com.carassistant.v10.launcher.dock/state";
    private static final String ACTION_CONTROL = "com.carassistant.v10.MEDIA_POPUP_CONTROL";
    private static final String EXTRA_COMMAND = "command";
    private static final String PREFS = "carassistant_media_popup";
    private static final String PREF_X = "x";
    private static final String PREF_Y = "y";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService QUERY_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Map<View, PopupController> CONTROLLERS = new WeakHashMap<>();
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private GearheadMediaPopup() {}

    public static void install() {
        if (!INSTALLED.compareAndSet(false, true)) return;
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Activity activity = (Activity) param.thisObject;
                        if (!GEARHEAD.equals(activity.getPackageName())) return;
                        MAIN.postDelayed(() -> attach(activity), 180L);
                    } catch (Throwable error) {
                        log("onResume attach failed safely: " + error);
                    }
                }
            });
            XposedHelpers.findAndHookMethod(Activity.class, "onPause", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Activity activity = (Activity) param.thisObject;
                        if (!GEARHEAD.equals(activity.getPackageName())) return;
                        Window window = activity.getWindow();
                        if (window == null) return;
                        View decor = window.getDecorView();
                        synchronized (CONTROLLERS) {
                            PopupController controller = CONTROLLERS.get(decor);
                            if (controller != null) controller.pause();
                        }
                    } catch (Throwable error) {
                        log("onPause cleanup failed safely: " + error);
                    }
                }
            });
            try {
                Class<?> windowManagerGlobal = XposedHelpers.findClass(
                        "android.view.WindowManagerGlobal", null);
                XposedBridge.hookAllMethods(windowManagerGlobal, "addView", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args.length > 0 && param.args[0] instanceof View) {
                            View root = (View) param.args[0];
                            MAIN.post(() -> attachRoot(root, null));
                        }
                    }
                });
                log("Installed WindowManagerGlobal.addView hook for car UI roots");
            } catch (Throwable error) {
                log("Window root hook unavailable; Activity fallback remains: " + error);
            }
            log("Installed Gearhead media popup hooks");
        } catch (Throwable error) {
            INSTALLED.set(false);
            log("Unable to install Activity lifecycle hooks: " + error);
        }
    }

    private static void attach(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        try {
            Window window = activity.getWindow();
            if (window == null) return;
            View decor = window.getDecorView();
            if (!(decor instanceof ViewGroup)) {
                log("Skip popup: Gearhead decor is not a ViewGroup");
                return;
            }
            attachRoot(decor, activity);
        } catch (Throwable error) {
            log("Popup attach failed safely: " + error);
        }
    }

    /** Attach to the actual WindowManager root, including roots not owned by an Activity. */
    private static void attachRoot(View root, Activity owner) {
        if (!(root instanceof ViewGroup)) return;
        Context context = root.getContext();
        try {
            if (!GEARHEAD.equals(context.getPackageName())) return;
            PopupController controller;
            synchronized (CONTROLLERS) {
                controller = CONTROLLERS.get(root);
                if (controller == null) {
                    controller = new PopupController(context, (ViewGroup) root, owner);
                    CONTROLLERS.put(root, controller);
                    log("Popup attached to root=" + root.getClass().getName()
                            + " context=" + context.getClass().getName()
                            + " owner=" + (owner == null ? "<window-root>" : owner.getClass().getName()));
                }
            }
            controller.resume();
        } catch (Throwable error) {
            log("Window root popup attach failed safely: " + error);
        }
    }

    private static final class PopupController {
        private final Activity activity;
        private final Context context;
        private final ViewGroup decor;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final AtomicBoolean queryInFlight = new AtomicBoolean(false);
        private final FrameLayout overlay;
        private final LinearLayout card;
        private final TextView artwork;
        private final TextView title;
        private final TextView artist;
        private final TextView playPause;
        private final Runnable pollRunnable = this::poll;
        private boolean resumed;
        private int x;
        private int y;
        private String lastPackage = "";

        PopupController(Context context, ViewGroup decor, Activity owner) {
            this.activity = owner;
            this.context = context;
            this.decor = decor;
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            x = prefs.getInt(PREF_X, dp(32));
            y = prefs.getInt(PREF_Y, dp(72));

            overlay = new FrameLayout(context);
            overlay.setClipChildren(false);
            overlay.setClipToPadding(false);
            overlay.setClickable(false);
            overlay.setFocusable(false);
            overlay.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

            card = new LinearLayout(context);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(9), dp(7), dp(7), dp(7));
            card.setElevation(dp(18));
            GradientDrawable background = new GradientDrawable();
            background.setColor(Color.argb(238, 18, 23, 30));
            background.setCornerRadius(dp(14));
            background.setStroke(dp(1), Color.argb(210, 90, 176, 190));
            card.setBackground(background);
            card.setClickable(true);
            card.setFocusable(false);

            artwork = new TextView(context);
            artwork.setText("♫");
            artwork.setTextSize(23);
            artwork.setTextColor(Color.WHITE);
            artwork.setGravity(Gravity.CENTER);
            GradientDrawable artBackground = new GradientDrawable();
            artBackground.setColor(Color.rgb(42, 53, 64));
            artBackground.setCornerRadius(dp(9));
            artwork.setBackground(artBackground);
            card.addView(artwork, new LinearLayout.LayoutParams(dp(42), dp(42)));

            LinearLayout metadata = new LinearLayout(context);
            metadata.setOrientation(LinearLayout.VERTICAL);
            metadata.setGravity(Gravity.CENTER_VERTICAL);
            metadata.setPadding(dp(9), 0, dp(4), 0);
            LinearLayout.LayoutParams metadataParams = new LinearLayout.LayoutParams(dp(122), -1);
            card.addView(metadata, metadataParams);

            title = new TextView(context);
            title.setTextColor(Color.WHITE);
            title.setTextSize(12);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            metadata.addView(title, new LinearLayout.LayoutParams(-1, dp(22)));

            artist = new TextView(context);
            artist.setTextColor(Color.LTGRAY);
            artist.setTextSize(10);
            artist.setSingleLine(true);
            artist.setEllipsize(android.text.TextUtils.TruncateAt.END);
            metadata.addView(artist, new LinearLayout.LayoutParams(-1, dp(18)));

            metadata.setOnTouchListener((view, event) -> handleDrag(event));
            metadata.setContentDescription("Giữ và kéo để di chuyển popup media; chạm để mở CarAsistant V10");

            addControl("⏮", "Bài trước", "previous", dp(32));
            addControl("↶15", "Tua lùi 15 giây", "rewind", dp(38));
            playPause = addControl("▶", "Phát hoặc tạm dừng", "toggle", dp(34));
            addControl("15↷", "Tua tới 15 giây", "forward", dp(38));
            addControl("⏭", "Bài tiếp", "next", dp(32));

            FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(58), Gravity.TOP | Gravity.LEFT);
            cardParams.leftMargin = x;
            cardParams.topMargin = y;
            overlay.addView(card, cardParams);

            if (decor.indexOfChild(overlay) < 0) {
                decor.addView(overlay, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
        }

        private TextView addControl(String label, String description, String command, int width) {
            TextView control = new TextView(context);
            control.setText(label);
            control.setTextColor(Color.WHITE);
            control.setTextSize(label.length() > 3 ? 10 : 15);
            control.setGravity(Gravity.CENTER);
            control.setContentDescription(description);
            control.setPadding(0, 0, 0, 0);
            control.setBackgroundColor(Color.TRANSPARENT);
            control.setClickable(true);
            control.setFocusable(false);
            card.addView(control, new LinearLayout.LayoutParams(width, dp(44)));
            control.setOnClickListener(view -> sendControl(command));
            return control;
        }

        private boolean handleDrag(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                dragDownX = event.getRawX();
                dragDownY = event.getRawY();
                dragStartX = x;
                dragStartY = y;
                dragged = false;
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                int dx = Math.round(event.getRawX() - dragDownX);
                int dy = Math.round(event.getRawY() - dragDownY);
                if (Math.abs(dx) > dp(7) || Math.abs(dy) > dp(7)) dragged = true;
                x = Math.max(0, Math.min(Math.max(0, overlay.getWidth() - card.getWidth()), dragStartX + dx));
                y = Math.max(0, Math.min(Math.max(0, overlay.getHeight() - card.getHeight()), dragStartY + dy));
                updatePosition();
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putInt(PREF_X, x).putInt(PREF_Y, y).apply();
                if (event.getActionMasked() == MotionEvent.ACTION_UP && !dragged) {
                    openCarApp();
                }
                return true;
            }
            return true;
        }

        private float dragDownX;
        private float dragDownY;
        private boolean dragged;
        private int dragStartX;
        private int dragStartY;

        private void updatePosition() {
            ViewGroup.LayoutParams raw = card.getLayoutParams();
            if (!(raw instanceof FrameLayout.LayoutParams)) return;
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) raw;
            params.leftMargin = x;
            params.topMargin = y;
            params.gravity = Gravity.TOP | Gravity.LEFT;
            card.setLayoutParams(params);
        }

        private void openCarApp() {
            try {
                ClassLoader loader = context.getClassLoader();
                Class<?> controller = XposedHelpers.findClass(
                        "com.google.android.projection.gearhead.service.CarSystemUiControllerService",
                        loader);
                java.lang.reflect.Method launcher = null;
                for (Class<?> type = controller; type != null && launcher == null;
                        type = type.getSuperclass()) {
                    for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                        if (java.lang.reflect.Modifier.isStatic(method.getModifiers())
                                && "a".equals(method.getName())
                                && method.getParameterTypes().length == 1
                                && Intent.class.equals(method.getParameterTypes()[0])) {
                            launcher = method;
                            break;
                        }
                    }
                }
                if (launcher == null) throw new NoSuchMethodException("Car App launcher a(Intent)");
                launcher.setAccessible(true);
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(
                        "com.carassistant.v10", "com.carassistant.v10.AACastCarService"));
                intent.putExtra("android.intent.extra.PACKAGE_NAME", "com.carassistant.v10");
                launcher.invoke(null, intent);
                log("Popup metadata tapped; Gearhead Car App launcher invoked for V10");
            } catch (Throwable error) {
                log("Popup could not open V10 Car App through Gearhead: " + error);
            }
        }

        private void sendControl(String command) {
            try {
                Intent intent = new Intent(ACTION_CONTROL);
                intent.setComponent(new ComponentName(
                        "com.carassistant.v10", "com.carassistant.v10.V10ControlReceiver"));
                intent.putExtra(EXTRA_COMMAND, command);
                context.sendBroadcast(intent);
            } catch (Throwable error) {
                log("Control broadcast failed (" + command + "): " + error);
            }
        }

        void resume() {
            resumed = true;
            handler.removeCallbacks(pollRunnable);
            poll();
        }

        void pause() {
            resumed = false;
            handler.removeCallbacks(pollRunnable);
        }

        private void poll() {
            if (!resumed || (activity != null && activity.isFinishing())) return;
            if (queryInFlight.compareAndSet(false, true)) {
                QUERY_EXECUTOR.execute(() -> {
                    MediaState state = readState();
                    queryInFlight.set(false);
                    MAIN.post(() -> applyState(state));
                });
            }
            handler.postDelayed(pollRunnable, 1100L);
        }

        private MediaState readState() {
            Cursor cursor = null;
            try {
                ContentResolver resolver = context.getContentResolver();
                cursor = resolver.query(Uri.parse(PROVIDER_URI), null, null, null, null);
                if (cursor == null || !cursor.moveToFirst()) return MediaState.empty();
                return new MediaState(
                        value(cursor, "media_title"),
                        value(cursor, "media_artist"),
                        value(cursor, "media_package"),
                        cursor.getInt(cursor.getColumnIndexOrThrow("media_playing")) != 0);
            } catch (Throwable error) {
                log("Media state query failed: " + error.getClass().getSimpleName() + ": " + error.getMessage());
                return MediaState.empty();
            } finally {
                if (cursor != null) try { cursor.close(); } catch (Throwable ignored) { }
            }
        }

        private String value(Cursor cursor, String column) {
            int index = cursor.getColumnIndex(column);
            if (index < 0 || cursor.isNull(index)) return "";
            String value = cursor.getString(index);
            return value == null ? "" : value;
        }

        private void applyState(MediaState state) {
            if (!resumed || overlay.getParent() == null) return;
            if (state == null || state.packageName.isEmpty()) {
                card.setVisibility(View.GONE);
                return;
            }
            card.setVisibility(View.VISIBLE);
            title.setText(state.title.isEmpty() ? "Đang phát media" : state.title);
            artist.setText(state.artist.isEmpty() ? state.packageName : state.artist);
            playPause.setText(state.playing ? "⏸" : "▶");
            if (!state.packageName.equals(lastPackage)) {
                lastPackage = state.packageName;
                // Keep the user-selected position when the media item changes.
                updatePosition();
            }
        }

        private int dp(float value) {
            return Math.round(value * context.getResources().getDisplayMetrics().density);
        }
    }

    private static final class MediaState {
        final String title;
        final String artist;
        final String packageName;
        final boolean playing;

        MediaState(String title, String artist, String packageName, boolean playing) {
            this.title = title == null ? "" : title;
            this.artist = artist == null ? "" : artist;
            this.packageName = packageName == null ? "" : packageName;
            this.playing = playing;
        }

        static MediaState empty() {
            return new MediaState("", "", "", false);
        }
    }

    private static void log(String message) {
        XposedBridge.log(TAG + " " + message);
    }
}
