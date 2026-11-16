package com.carassistant.v10;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import com.carassistant.v10.voice.VoiceCommandParser;

import java.lang.ref.WeakReference;
import java.util.List;

/**
 * Core-owned voice command execution and projection dispatch.
 *
 * App resolution, provider/default selection, URI construction and pending-command
 * persistence do not depend on V10CarActivity. The optional weak UI bridge supplies
 * only the final operations that require the live TextureView projection surface.
 */
public final class V10ProjectionCoordinator {
    private static final String PREFS = "v10_projection_pending";
    private static final String TYPE = "type";
    private static final String COMPONENT = "component";
    private static final String ACTION = "intent_action";
    private static final String URI = "intent_uri";
    private static final String QUERY = "intent_query";

    private static WeakReference<V10ProjectionCommandHost> currentHost =
            new WeakReference<>(null);

    private V10ProjectionCoordinator() { }

    public static synchronized void attach(V10ProjectionCommandHost host) {
        currentHost = new WeakReference<>(host);
    }

    public static synchronized void detach(V10ProjectionCommandHost host) {
        if (currentHost.get() == host) currentHost.clear();
    }

    private static synchronized V10ProjectionCommandHost host() {
        return currentHost.get();
    }

    public static String openApp(Context context, String spokenName) {
        if (context == null) return null;
        V10AppManager manager = new V10AppManager(context);
        V10AppManager.App app = manager.findByVoice(spokenName);
        if (app == null) return null;
        dispatchOrQueue(context, "app", app.component, null, null, null);
        return app.label;
    }

    public static String openVoiceTask(Context context, String category,
                                       String query, String explicitProvider) {
        if (context == null || category == null) return null;
        V10AppManager manager = new V10AppManager(context);
        ComponentName component = resolveVoiceTarget(context, manager, category, explicitProvider);
        if (component == null) return null;

        String cleanQuery = query == null ? "" : query.trim();
        String action = null;
        String uri = null;
        if (!cleanQuery.isEmpty()) {
            if ("navigation".equals(category) || "nearby".equals(category)) {
                action = "view";
                uri = buildMapUri(component, cleanQuery, "nearby".equals(category));
            } else if ("music".equals(category) || "video".equals(category)) {
                action = "media";
                uri = VoiceBackgroundTaskManager.buildSearchUri(component, cleanQuery, category);
            } else if ("tv".equals(category)) {
                action = "search";
            }
            if (("view".equals(action) || "media".equals(action)) && uri == null) return null;
        }

        dispatchOrQueue(context, "task", component, action, uri, cleanQuery);
        return manager.labelFor(component);
    }

    public static void showAppPicker(Context context) {
        V10ProjectionCommandHost host = host();
        if (host != null) host.showAppPicker();
        else queue(context, "apps", null, null, null, null);
    }

    public static void goHome(Context context) {
        V10ProjectionCommandHost host = host();
        if (host != null) {
            host.goHome();
        } else {
            queue(context, "home", null, null, null, null);
            V10SessionStore.clearTarget(context);
        }
    }

    private static void dispatchOrQueue(Context context, String type, ComponentName component,
                                        String action, String uri, String query) {
        V10ProjectionCommandHost host = host();
        if (host != null) {
            host.launchApp(component);
            dispatchIntent(host, component, action, uri, query);
            return;
        }
        queue(context, type, component, action, uri, query);
        prepareHeadlessProjection(context, component);
    }

    /** Called by V10CarActivity after its real projection surface is attached. */
    public static void dispatchPending(Context context, V10ProjectionCommandHost host) {
        if (context == null || host == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String type = prefs.getString(TYPE, null);
        if (type == null) return;

        ComponentName component = ComponentName.unflattenFromString(
                prefs.getString(COMPONENT, null));
        String action = prefs.getString(ACTION, null);
        String uri = prefs.getString(URI, null);
        String query = prefs.getString(QUERY, null);
        prefs.edit().clear().apply();

        if ("app".equals(type) || "task".equals(type)) {
            if (component == null) return;
            host.launchApp(component);
            dispatchIntent(host, component, action, uri, query);
            V10SessionStore.setTarget(context, component);
        } else if ("apps".equals(type)) {
            host.showAppPicker();
        } else if ("home".equals(type)) {
            host.goHome();
        }
    }

    private static void dispatchIntent(V10ProjectionCommandHost host, ComponentName component,
                                       String action, String uri, String query) {
        if (host == null || component == null || action == null) return;
        if ("view".equals(action) && uri != null) {
            host.launchViewIntent(component, uri);
        } else if ("search".equals(action) && query != null && !query.isEmpty()) {
            host.launchSearchIntent(component, query);
        } else if ("media".equals(action) && uri != null && query != null) {
            host.launchMediaSearch(component, uri, query);
        }
    }

    private static void queue(Context context, String type, ComponentName component,
                              String action, String uri, String query) {
        if (context == null) return;
        SharedPreferences.Editor editor = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().clear().putString(TYPE, type);
        if (component != null) editor.putString(COMPONENT, component.flattenToString());
        if (action != null) editor.putString(ACTION, action);
        if (uri != null) editor.putString(URI, uri);
        if (query != null) editor.putString(QUERY, query);
        editor.apply();
    }

    private static void prepareHeadlessProjection(Context context, ComponentName component) {
        if (context == null || component == null) return;
        Context app = context.getApplicationContext();
        boolean connected = app.getSharedPreferences(
                "carassistant_media_resume", Context.MODE_PRIVATE)
                .getBoolean("aa_connected", false);
        if (!connected || !HeadlessProjectionHost.start(app)) return;
        V10SessionStore.setTarget(app, component);
        V10DisplayController.get(app, HeadlessProjectionHost.DISPLAY_INDEX)
                .launchNewTask(component);
    }

    private static ComponentName resolveVoiceTarget(Context context, V10AppManager manager,
                                                     String category, String explicitProvider) {
        String normalizedCategory = ("nearby".equals(category) ? "navigation" : category);
        if (explicitProvider != null && !explicitProvider.trim().isEmpty()) {
            String normalized = VoiceCommandParser.normalize(explicitProvider);
            if ("youtube".equals(normalized)
                    && ("music".equals(category) || "video".equals(category))) {
                ComponentName configured = installedConfigured(context, manager, "video");
                if (configured == null) configured = installedConfigured(context, manager, category);
                if (configured != null) return configured;
            }
            V10AppManager.App explicit = manager.findByVoice(explicitProvider);
            return explicit == null ? null : explicit.component;
        }

        ComponentName configured = installedConfigured(context, manager, normalizedCategory);
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

    private static ComponentName installedConfigured(Context context, V10AppManager manager,
                                                       String category) {
        ComponentName configured = V10SessionStore.getDefaultVoiceApp(context, category);
        if (configured == null) return null;
        List<V10AppManager.App> installed = manager.getLauncherApps();
        for (V10AppManager.App app : installed) {
            if (configured.getPackageName().equals(app.component.getPackageName())) {
                return app.component;
            }
        }
        return null;
    }

    private static String buildMapUri(ComponentName component, String query, boolean nearby) {
        if (component == null || query == null || query.trim().isEmpty()) return null;
        String encoded = Uri.encode(query.trim());
        if ("com.google.android.apps.maps".equals(component.getPackageName())) {
            return nearby ? "geo:0,0?q=" + encoded : "google.navigation:q=" + encoded;
        }
        return "geo:0,0?q=" + encoded;
    }
}
