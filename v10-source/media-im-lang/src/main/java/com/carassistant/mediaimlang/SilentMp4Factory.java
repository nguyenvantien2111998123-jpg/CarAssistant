package com.carassistant.mediaimlang;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds a 30-minute MP4 containing only AAC digital-zero samples.
 * The PCM input is all zeroes (no tone/frequency), and the player is also muted.
 */
final class SilentMp4Factory {
    private static final String TAG = "MediaImLangMp4";
    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNELS = 2;
    private static final int DURATION_SECONDS = 30 * 60;
    private static final int PLAYLIST_SIZE = 3;
    private static final String MASTER_NAME = "media-im-lang-silent-30m-v2.mp4";

    static List<File> getPlaylist(Context context) throws Exception {
        File master = new File(context.getFilesDir(), MASTER_NAME);
        if (!master.isFile() || master.length() == 0L) {
            File temporary = new File(context.getFilesDir(), MASTER_NAME + ".tmp");
            if (temporary.exists()) temporary.delete();
            encodeSilence(temporary);
            if (!temporary.renameTo(master)) {
                copyFile(temporary, master);
                temporary.delete();
            }
        }

        List<File> files = new ArrayList<>(PLAYLIST_SIZE);
        for (int i = 1; i <= PLAYLIST_SIZE; i++) {
            File track = new File(context.getFilesDir(),
                    String.format(java.util.Locale.US, "media-im-lang-track-%02d.mp4", i));
            if (!track.isFile() || track.length() != master.length()) {
                copyFile(master, track);
            }
            files.add(track);
        }
        Log.i(TAG, "Silent playlist ready: tracks=" + files.size()
                + ", durationEachMs=" + DURATION_SECONDS * 1000L
                + ", bytesEach=" + master.length());
        return Collections.unmodifiableList(files);
    }

    private static void encodeSilence(File output) throws Exception {
        MediaFormat format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 64000);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

        MediaCodec encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        MediaMuxer muxer = new MediaMuxer(output.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        boolean muxerStarted = false;
        int track = -1;
        boolean inputDone = false;
        boolean outputDone = false;
        long totalFrames = 0L;
        final long targetFrames = (long) SAMPLE_RATE * DURATION_SECONDS;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            while (!outputDone) {
                if (!inputDone) {
                    int inputIndex = encoder.dequeueInputBuffer(10000);
                    if (inputIndex >= 0) {
                        ByteBuffer input = encoder.getInputBuffer(inputIndex);
                        if (input != null) {
                            input.clear();
                            long remainingFrames = targetFrames - totalFrames;
                            int frames = (int) Math.min(remainingFrames,
                                    input.remaining() / (CHANNELS * 2));
                            int bytes = frames * CHANNELS * 2;
                            for (int i = 0; i < bytes; i++) input.put((byte) 0);
                            long ptsUs = totalFrames * 1000000L / SAMPLE_RATE;
                            totalFrames += frames;
                            boolean eos = totalFrames >= targetFrames;
                            encoder.queueInputBuffer(inputIndex, 0, bytes, ptsUs,
                                    eos ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                            inputDone = eos;
                        }
                    }
                }

                int outputIndex = encoder.dequeueOutputBuffer(info, 10000);
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();
                    muxerStarted = true;
                } else if (outputIndex >= 0) {
                    ByteBuffer encoded = encoder.getOutputBuffer(outputIndex);
                    if (encoded != null && info.size > 0 && muxerStarted
                            && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        encoded.position(info.offset);
                        encoded.limit(info.offset + info.size);
                        muxer.writeSampleData(track, encoded, info);
                    }
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    encoder.releaseOutputBuffer(outputIndex, false);
                    if (eos) outputDone = true;
                }
            }
        } finally {
            try { encoder.stop(); } catch (RuntimeException ignored) { }
            encoder.release();
            if (muxerStarted) {
                try { muxer.stop(); } catch (RuntimeException ignored) { }
            }
            muxer.release();
        }

        if (!output.isFile() || output.length() == 0L) {
            throw new IllegalStateException("30-minute silent MP4 was not created");
        }
        Log.i(TAG, "Generated 30-minute MP4 with zero-valued PCM/AAC samples: "
                + output.length() + " bytes");
    }

    private static void copyFile(File source, File destination) throws Exception {
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Cannot create media directory");
        }
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(destination, false)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) output.write(buffer, 0, count);
            }
            output.getFD().sync();
        }
    }

    private SilentMp4Factory() { }
}
