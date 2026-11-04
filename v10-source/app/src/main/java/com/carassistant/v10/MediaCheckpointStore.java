package com.carassistant.v10;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.AtomicFile;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Crash-safe, single-record media checkpoint storage.
 *
 * AtomicFile is the source of truth. SharedPreferences are mirrored only for
 * compatibility with existing dashboard widgets and older restore helpers.
 */
final class MediaCheckpointStore {
    private static final String TAG = "CarAssistantCheckpoint";
    private static final int MAGIC = 0x43415354; // "CAST"
    private static final int VERSION = 1;
    private static final int MAX_STRING_BYTES = 65536;
    private static final String PREFS = "carassistant_media_resume";

    private static final Object CACHE_LOCK = new Object();
    private static volatile Checkpoint cached;

    // A bounded queue coalesces bursts of MediaSession callbacks to the newest state.
    private static final ThreadPoolExecutor WRITER = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1),
            new ThreadFactory() {
                @Override public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "CarAsistant-CheckpointWriter");
                    thread.setDaemon(true);
                    return thread;
                }
            },
            new ThreadPoolExecutor.DiscardOldestPolicy());

    private MediaCheckpointStore() { }

    static final class Checkpoint {
        final String packageName;
        final String taskComponent;
        final String title;
        final String artist;
        final String mediaId;
        final String mediaUri;
        final long duration;
        final long position;
        final boolean playing;
        final float speed;
        final long elapsedRealtime;
        final long updatedAt;

        Checkpoint(String packageName, String taskComponent, String title, String artist,
                   String mediaId, String mediaUri, long duration, long position,
                   boolean playing, float speed, long elapsedRealtime, long updatedAt) {
            this.packageName = clean(packageName);
            this.taskComponent = clean(taskComponent);
            this.title = clean(title);
            this.artist = clean(artist);
            this.mediaId = clean(mediaId);
            this.mediaUri = clean(mediaUri);
            this.duration = Math.max(0L, duration);
            this.position = Math.max(0L, position);
            this.playing = playing;
            this.speed = Float.isFinite(speed) && speed > 0f ? speed : 1f;
            this.elapsedRealtime = Math.max(0L, elapsedRealtime);
            this.updatedAt = Math.max(0L, updatedAt);
        }

        boolean isValid() {
            return !TextUtils.isEmpty(packageName)
                    && (!TextUtils.isEmpty(mediaId)
                    || !TextUtils.isEmpty(mediaUri)
                    || !TextUtils.isEmpty(title));
        }
    }

    static Checkpoint load(Context context) {
        if (context == null) return null;
        Checkpoint value = cached;
        if (value != null) return value;

        synchronized (CACHE_LOCK) {
            if (cached != null) return cached;
            Context app = context.getApplicationContext();
            AtomicFile atomic = atomicFile(app);
            try {
                if (atomic.getBaseFile().exists()) {
                    Checkpoint disk = readAtomic(atomic);
                    if (disk != null && disk.isValid()) {
                        cached = disk;
                        return disk;
                    }
                }
            } catch (IOException | RuntimeException error) {
                Log.e(TAG, "Atomic checkpoint read failed; trying legacy migration", error);
            }

            Checkpoint legacy = readLegacy(app);
            if (legacy != null && legacy.isValid()) {
                cached = legacy;
                save(app, legacy);
                Log.i(TAG, "Migrating the existing SharedPreferences checkpoint to AtomicFile");
                return legacy;
            }
            return null;
        }
    }

    static void save(Context context, Checkpoint checkpoint) {
        if (context == null || checkpoint == null || !checkpoint.isValid()) return;
        Context app = context.getApplicationContext();
        synchronized (CACHE_LOCK) {
            Checkpoint previous = cached;
            if (previous != null && checkpoint.updatedAt < previous.updatedAt) return;
            cached = checkpoint;
        }
        try {
            WRITER.execute(() -> writeAtomic(app, checkpoint));
        } catch (RuntimeException error) {
            Log.e(TAG, "Could not queue durable media checkpoint", error);
        }
    }

    private static AtomicFile atomicFile(Context context) {
        return new AtomicFile(new File(context.getFilesDir(), "media-checkpoint.bin"));
    }

    private static Checkpoint readAtomic(AtomicFile atomic) throws IOException {
        try (FileInputStream raw = atomic.openRead();
             DataInputStream input = new DataInputStream(new BufferedInputStream(raw))) {
            int magic = input.readInt();
            int version = input.readInt();
            if (magic != MAGIC || version != VERSION) {
                throw new IOException("Unsupported checkpoint header: " + magic + "/" + version);
            }
            Checkpoint value = new Checkpoint(
                    readString(input), readString(input), readString(input), readString(input),
                    readString(input), readString(input),
                    input.readLong(), input.readLong(), input.readBoolean(),
                    input.readFloat(), input.readLong(), input.readLong());
            return value.isValid() ? value : null;
        }
    }

    private static void writeAtomic(Context context, Checkpoint value) {
        AtomicFile atomic = atomicFile(context);
        FileOutputStream raw = null;
        try {
            raw = atomic.startWrite();
            DataOutputStream output = new DataOutputStream(new BufferedOutputStream(raw));
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            writeString(output, value.packageName);
            writeString(output, value.taskComponent);
            writeString(output, value.title);
            writeString(output, value.artist);
            writeString(output, value.mediaId);
            writeString(output, value.mediaUri);
            output.writeLong(value.duration);
            output.writeLong(value.position);
            output.writeBoolean(value.playing);
            output.writeFloat(value.speed);
            output.writeLong(value.elapsedRealtime);
            output.writeLong(value.updatedAt);
            output.flush();
            atomic.finishWrite(raw);
            raw = null;
            mirrorLegacy(context, value);
        } catch (IOException | RuntimeException error) {
            if (raw != null) atomic.failWrite(raw);
            Log.e(TAG, "Atomic checkpoint write failed; previous checkpoint retained", error);
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = clean(value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            byte[] shortened = new byte[MAX_STRING_BYTES];
            System.arraycopy(bytes, 0, shortened, 0, MAX_STRING_BYTES);
            bytes = shortened;
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new IOException("Invalid checkpoint string length: " + length);
        }
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static Checkpoint readLegacy(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String packageName = prefs.getString("checkpoint_package", "");
        if (TextUtils.isEmpty(packageName)) return null;
        return new Checkpoint(
                packageName,
                prefs.getString("checkpoint_task_component", ""),
                prefs.getString("checkpoint_title", ""),
                prefs.getString("checkpoint_artist", ""),
                prefs.getString("checkpoint_media_id", ""),
                prefs.getString("checkpoint_media_uri", ""),
                prefs.getLong("checkpoint_duration", 0L),
                prefs.getLong("checkpoint_position", 0L),
                prefs.getBoolean("checkpoint_playing", false),
                prefs.getFloat("checkpoint_speed", 1f),
                prefs.getLong("checkpoint_elapsed_realtime", 0L),
                prefs.getLong("checkpoint_updated_at", 0L));
    }

    private static void mirrorLegacy(Context context, Checkpoint value) {
        boolean committed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("last_media_package", value.packageName)
                .putString("checkpoint_package", value.packageName)
                .putString("checkpoint_task_component", value.taskComponent)
                .putString("checkpoint_title", value.title)
                .putString("checkpoint_artist", value.artist)
                .putString("checkpoint_media_id", value.mediaId)
                .putString("checkpoint_media_uri", value.mediaUri)
                .putLong("checkpoint_duration", value.duration)
                .putLong("checkpoint_position", value.position)
                .putBoolean("checkpoint_playing", value.playing)
                .putFloat("checkpoint_speed", value.speed)
                .putLong("checkpoint_elapsed_realtime", value.elapsedRealtime)
                .putLong("checkpoint_updated_at", value.updatedAt)
                .commit();
        if (!committed) Log.w(TAG, "Legacy preference mirror was not committed");
    }

    private static String clean(String value) {
        return value == null ? "" : value;
    }
}
