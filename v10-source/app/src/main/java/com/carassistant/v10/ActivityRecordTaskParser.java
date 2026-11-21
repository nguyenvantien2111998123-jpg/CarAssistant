package com.carassistant.v10;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the task/activity portion of "dumpsys activity activities".
 *
 * A Task is identified by its own Task header and display section. ActivityRecord
 * matching is exact on package + activity class; package-only matches are tracked
 * separately so callers can refuse to create a duplicate task.
 */
final class ActivityRecordTaskParser {
    private static final Pattern DISPLAY_HEADER = Pattern.compile("^\\s*Display #([0-9]+)\\b");
    private static final Pattern ROOT_TASK_ID = Pattern.compile("rootTaskId=([0-9]+)");
    private static final Pattern HASH_TASK_ID = Pattern.compile("#([0-9]+)\\b");

    private ActivityRecordTaskParser() { }

    static Snapshot parse(String dump, String packageName, String className) {
        if (dump == null || packageName == null || packageName.isEmpty()
                || className == null || className.isEmpty()) {
            return new Snapshot(new ArrayList<>(), false);
        }

        String shortClassName = className.startsWith(packageName)
                ? "." + className.substring(packageName.length()) : className;
        Map<String, TaskRecord> tasks = new LinkedHashMap<>();
        int currentDisplay = -1;
        TaskRecord currentTask = null;
        boolean packageTaskExists = false;

        String[] lines = dump.split("\\r?\\n");
        for (String line : lines) {
            Matcher displayMatcher = DISPLAY_HEADER.matcher(line);
            if (displayMatcher.find()) {
                currentDisplay = parseInt(displayMatcher.group(1), -1);
                currentTask = null;
                continue;
            }

            int taskHeader = line.indexOf("Task{");
            if (taskHeader >= 0) {
                int taskId = extractTaskId(line.substring(taskHeader));
                currentTask = taskId < 0 ? null : tasks.computeIfAbsent(
                        currentDisplay + ":" + taskId,
                        ignored -> new TaskRecord(currentDisplay, taskId));
                continue;
            }

            if (line.indexOf("ActivityRecord") < 0) continue;
            if (containsActivityToken(line, packageName, null)) {
                packageTaskExists = true;
            }
            if (currentTask != null
                    && (containsActivityToken(line, packageName, className)
                    || containsActivityToken(line, packageName, shortClassName))) {
                currentTask.matchesSelectedActivity = true;
            }
        }

        return new Snapshot(new ArrayList<>(tasks.values()), packageTaskExists);
    }

    private static boolean containsActivityToken(String line, String packageName, String className) {
        String prefix = packageName + "/";
        int from = 0;
        while (true) {
            int index = line.indexOf(prefix, from);
            if (index < 0) return false;
            int end = index + prefix.length();
            while (end < line.length()) {
                char c = line.charAt(end);
                if (Character.isWhitespace(c) || c == '}' || c == ']' || c == ',') break;
                end++;
            }
            String actualClass = line.substring(index + prefix.length(), end);
            if (className == null || className.equals(actualClass)) return true;
            from = end;
        }
    }

    private static int extractTaskId(String taskLine) {
        Matcher root = ROOT_TASK_ID.matcher(taskLine);
        if (root.find()) return parseInt(root.group(1), -1);
        Matcher hash = HASH_TASK_ID.matcher(taskLine);
        return hash.find() ? parseInt(hash.group(1), -1) : -1;
    }

    private static int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    static final class TaskRecord {
        final int displayId;
        final int taskId;
        boolean matchesSelectedActivity;

        TaskRecord(int displayId, int taskId) {
            this.displayId = displayId;
            this.taskId = taskId;
        }
    }

    static final class Snapshot {
        private final List<TaskRecord> tasks;
        private final boolean packageTaskExists;

        Snapshot(List<TaskRecord> tasks, boolean packageTaskExists) {
            this.tasks = tasks;
            this.packageTaskExists = packageTaskExists;
        }

        List<Integer> matchingTaskIds(int displayId) {
            ArrayList<Integer> result = new ArrayList<>();
            for (TaskRecord task : tasks) {
                if (task.matchesSelectedActivity
                        && (displayId < 0 || task.displayId == displayId)
                        && !result.contains(task.taskId)) {
                    result.add(task.taskId);
                }
            }
            return result;
        }

        List<Integer> allMatchingTaskIds() {
            return matchingTaskIds(-1);
        }

        boolean hasPackageTask() {
            return packageTaskExists;
        }
    }
}
