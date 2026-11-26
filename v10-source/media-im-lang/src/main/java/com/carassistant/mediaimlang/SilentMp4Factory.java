package com.carassistant.mediaimlang;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.File;
import java.nio.ByteBuffer;

/** Creates a short MP4 containing AAC-encoded digital zeroes (no tone or audible frequency). */
final class SilentMp4Factory {
    private static final String TAG = "MediaImLangMp4";
    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNELS = 2;
    private static final int DURATION_SECONDS = 3;

    static File getOrCreate(Context context) throws Exception {
        File output = new File(context.getFilesDir(), "media-im-lang-silent.mp4");
        if (output.isFile() && output.length() > 0) return output;

        MediaFormat format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 96000);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

        MediaCodec encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        MediaMuxer muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        boolean muxerStarted = false;
        int track = -1;
        boolean inputDone = false;
        boolean outputDone = false;
        long totalFrames = 0;
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
                            int frames = (int) Math.min(remainingFrames, input.remaining() / (CHANNELS * 2));
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

        if (!output.isFile() || output.length() == 0) {
            throw new IllegalStateException("Silent MP4 was not created");
        }
        Log.i(TAG, "Generated three-second MP4 with digital-zero AAC samples: " + output.length() + " bytes");
        return output;
    }

    private SilentMp4Factory() { }
}
