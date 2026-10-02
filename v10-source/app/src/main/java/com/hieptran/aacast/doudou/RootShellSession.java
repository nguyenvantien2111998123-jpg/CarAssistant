package com.carassistant.v10;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Một phiên `su` duy nhất, giữ nguyên giữa các lệnh.
 *
 * Cơ chế: ghi lệnh vào stdin của `su`, sau đó ghi một lệnh printf in ra marker
 * kèm exit code; đọc stdout qua thread riêng vào hàng đợi. Lệnh coi là THẤT BẠI
 * nếu exit code != 0 hoặc output chứa error:/exception/permission denied/not found.
 */
public final class RootShellSession {

    /** Executor đơn luồng: mọi lệnh shell đi qua đây. */
    public static final ExecutorService EXEC = Executors.newSingleThreadExecutor();

    private static final String MARKER_PREFIX = "__AACAST_";

    private Process process;
    private BufferedWriter stdin;
    private LinkedBlockingQueue<String> lines;

    /** Đóng phiên su. */
    public synchronized void destroy() {
        if (process != null) {
            process.destroy();
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
        process = null;
        stdin = null;
        lines = null;
    }

    /**
     * Chạy 1 lệnh trong phiên su, trả về stdout đã trim.
     * Ném IllegalStateException khi timeout / phiên đóng / lệnh lỗi.
     */
    public synchronized String run(int timeoutSeconds, String cmd) {
        if (process == null || !process.isAlive()) {
            destroy();
            try {
                process = new ProcessBuilder("su").redirectErrorStream(true).start();
            } catch (Exception e) {
                throw new IllegalStateException("Cannot start su: " + e);
            }
            stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            lines = new LinkedBlockingQueue<>();
            Thread reader = new Thread(new StreamReader(process, lines), "aacast-root-output");
            reader.setDaemon(true);
            reader.start();
        }

        String marker = MARKER_PREFIX + UUID.randomUUID().toString().replace("-", "") + ":";
        try {
            stdin.write(cmd);
            stdin.write("\n");
            stdin.write("printf '\\n" + marker + "%s\\n' \"$?\"\n");
            stdin.flush();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            StringBuilder out = new StringBuilder();
            while (true) {
                long left = deadline - System.nanoTime();
                String line = left <= 0 ? null : lines.poll(left, TimeUnit.NANOSECONDS);
                if (line == null) {
                    throw new IllegalStateException("Root command timed out");
                }
                if ("\u0000".equals(line)) {
                    throw new IllegalStateException("Root session closed");
                }
                if (line.startsWith(marker)) {
                    int code = Integer.parseInt(line.substring(marker.length()).trim());
                    String text = out.toString().trim();
                    String lower = text.toLowerCase(Locale.ROOT);
                    boolean looksFailed = lower.contains("error:")
                            || lower.contains("exception")
                            || lower.contains("permission denied")
                            || lower.contains("not found");
                    if (code == 0 && !looksFailed) {
                        return text;
                    }
                    throw new IllegalStateException(text.isEmpty() ? "Root command failed" : text);
                }
                if (out.length() < 16384) {
                    out.append(line).append('\n');
                }
            }
        } catch (Exception e) {
            destroy();
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new IllegalStateException(e);
        }
    }

    /** Đọc stdout của `su` thành từng dòng; kết thúc thì đẩy "\0". */
    private static final class StreamReader implements Runnable {

        private final Process process;
        private final LinkedBlockingQueue<String> queue;

        StreamReader(Process process, LinkedBlockingQueue<String> queue) {
            this.process = process;
            this.queue = queue;
        }

        @Override
        public void run() {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    queue.offer(line);
                }
                queue.offer("\u0000");
            } catch (Exception ignored) {
                queue.offer("\u0000");
            }
        }
    }
}
