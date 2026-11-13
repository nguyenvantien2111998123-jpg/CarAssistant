package com.carassistant.v10;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;

import com.carassistant.v10.voice.VoiceCommandParser;
import com.carassistant.v10.voice.VoiceCommandRouter;

import java.lang.ref.WeakReference;

/**
 * Process-wide command-to-projection coordinator.
 *
 * When the Android Auto CarActivity is alive, commands are delegated to its
 * existing UI/session implementation. When it is absent, the coordinator can
 * prepare the already-owned headless Display 2 and launch the requested app
 * there, then persist the command so the next V10 Activity attaches to the
 * same task and completes any app-specific search action.
 */
public final class V10ProjectionCoordinator {
    private static final String PREFS = "v10_projection_pending";
    private static final String TYPE = "type";
    private static final String ARGUMENT = "argument";
    private static final String CATEGORY = "category";
    private static final String PROVIDER = "provider";
    private static final String COMPONENT = "component";

    private static WeakReference<VoiceCommandRouter.Host> currentHost =
            new WeakReference<>(null);

    private V10ProjectionCoordinator() { }

    public static synchronized void attach(VoiceCommandRouter.Host host) {
        currentHost = new WeakReference<>(host);
    }

    public static synchronized void detach(VoiceCommandRouter.Host host) {
        if (currentHost.get() == host) currentHost.clear();
    }

    private static synchronized VoiceCommandRouter.Host host() {
        return currentHost.get();
    }

    public static String openApp(Context context, String spokenName) {
        VoiceCommandRouter.Host host = host();
        if (host != null) return host.openApp(spokenName);
        V10AppManager.App app = new V10AppManager(context).findByVoice(spokenName);
        if (app == null) return null;
        queue(context, "app", spokenName, null, null, app.component);
        prepareHeadlessProjection(context, app.component);
        return app.label;
    }

    public static String openVoiceTask(Context context, String category,
                                       String query, String provider) {
        VoiceCommandRouter.Host host = host();
        if (host != null) return host.openVoiceTask(category, query, provider);

        ComponentName component = resolveVoiceTarget(context, category, provider);
        if (component == null) return null;
        queue(context, "task", query, category, provider, component);
        prepareHeadlessProjection(context, component);
        return new V10AppManager(context).labelFor(component);
    }

    public static void showAppPicker(Context context) {
        VoiceCommandRouter.Host host = host();
        if (host != null) {
            host.showAppPicker();
        } else {
            queue(context, "apps", null, null, null, null);
        }
    }

    public static void goHome(Context context) {
        VoiceCommandRouter.Host host = host();
        if (host != null) {
            host.goHome();
        } else {
            queue(context, "home", null, null, null, null);
            V10SessionStore.clearTarget(context);
        }
    }

    /** Called once by V10CarActivity after attaching its real projection surface. */
    public static void dispatchPending(Context context, VoiceCommandRouter.Host host) {
        if (context == null || host == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String type = prefs.getString(TYPE, null);
        if (type == null) return;

        String argument = prefs.getString(ARGUMENT, null);
        String category = prefs.getString(CATEGORY, null);
        String provider = prefs.getString(PROVIDER, null);
        String flat = prefs.getString(COMPONENT, null);
        prefs.edit().clear().apply();

        if ("app".equals(type)) {
            host.openApp(argument);
        } else if ("task".equals(type)) {
            host.openVoiceTask(category, argument, provider);
        } else if ("apps".equals(type)) {
            host.showAppPicker();
        } else if ("home".equals(type)) {
            host.goHome();
        }

        // Preserve the prepared headless task as the selected target. The
        // Activity's V10ProjectionSession detects and reuses this exact task.
        if (flat != null && ("app".equals(type) || "task".equals(type))) {
            ComponentName prepared = ComponentName.unflattenFromString(flat);
            if (prepared != null) V10SessionStore.setTarget(context, prepared);
        }
    }

    private static void queue(Context context, String type, String argument,
                              String category, String provider, ComponentName component) {
        if (context == null) return;
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().clear().putString(TYPE, type);
        if (argument != null) editor.putString(ARGUMENT, argument);
        if (category != null) editor.putString(CATEGORY, category);
        if (provider != null) editor.putString(PROVIDER, provider);
        if (component != null) editor.putString(COMPONENT, component.flattenToString());
        editor.apply();
    }

    private static void prepareHeadlessProjection(Context context, ComponentName component) {
        if (context == null || component == null) return;
        Context app = context.getApplicationContext();
        boolean connected = app.getSharedPreferences(
                "carassistant_media_resume", Context.MODE_PRIVATE)
                .getBoolean("aa_connected", false);
        if (!connected) return;
        if (!HeadlessProjectionHost.start(app)) return;
        V10SessionStore.setTarget(app, component);
        V10DisplayController.get(app, HeadlessProjectionHost.DISPLAY_INDEX)
                .launchNewTask(component);
    }

    private static ComponentName resolveVoiceTarget(Context context, String category,
                                                     String explicitProvider) {
        V10AppManager manager = new V10AppManager(context);
        if (explicitProvider != null && !explicitProvider.trim().isEmpty()) {
            String normalized = VoiceCommandParser.normalize(explicitProvider);
            if ("youtube".equals(normalized)
                    && ("music".equals(category) || "video".equals(category))) {
                ComponentName configured = V10SessionStore.getDefaultVoiceApp(context, "video");
                if (configured == null) configured = V10SessionStore.getDefaultVoiceApp(context, category);
                if (configured != null) return configured;
            }
            V10AppManager.App explicit = manager.findByVoice(explicitProvider);
            return explicit == null ? null : explicit.component;
        }

        String configuredCategory = ("navigation".equals(category) || "nearby".equals(category))
                ? "navigation" : category;
        ComponentName configured = V10SessionStore.getDefaultVoiceApp(context, configuredCategory);
        if (configured != null) return configured;

        String[] candidates;
        if ("navigation".equals(category) || "nearby".equals(category)) {
            candidates = new String[]{"Google Maps", "Vietmap", "Navitel", "Maps"};
        } else if ("music".equals(category)) {
            candidates = new String[]{"Zing MP3", "Spotify", "YouTube Music", "NhacCuaTui"};
        } else if ("video".equals(category)) {
            candidates = new String[]{"YouTube"};
        } else if ("tv".equals(category)) {
            candidates = new String[]{"VTV Go", "FPT Play", "TV"};
        } else {
            candidates = new String[0];
        }
        for (String candidate : candidates) {
            V10AppManager.App app = manager.findByVoice(candidate);
            if (app != null) return app.component;
        }
        return null;
    }
}
