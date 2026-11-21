package com.carassistant.v10;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class ActivityRecordTaskParserTest {
    private static final String PKG = "com.example.player";
    private static final String ACTIVITY = "com.example.player.MainActivity";

    @Test public void selectsExactActivityAmongSeveralTasksInSamePackage() {
        String dump = "Display #0 (activities from top to bottom):\n"
                + "  * Task{abc #101 type=standard}\n"
                + "    * Hist #0: ActivityRecord{1 u0 com.example.player/.SettingsActivity t101}\n"
                + "  * Task{def #102 type=standard}\n"
                + "    * Hist #0: ActivityRecord{2 u0 com.example.player/.MainActivity t102}\n"
                + "Display #2 (activities from top to bottom):\n"
                + "  * Task{ghi #201 type=standard}\n"
                + "    * Hist #0: ActivityRecord{3 u0 com.example.player/com.example.player.MainActivity t201}\n";

        ActivityRecordTaskParser.Snapshot snapshot =
                ActivityRecordTaskParser.parse(dump, PKG, ACTIVITY);

        assertEquals(List.of(102), snapshot.matchingTaskIds(0));
        assertEquals(List.of(201), snapshot.matchingTaskIds(2));
        assertTrue(snapshot.hasPackageTask());
    }

    @Test public void reportsMultipleExactTasksAsAmbiguous() {
        String dump = "Display #0 (activities from top to bottom):\n"
                + "  * Task{abc #101 type=standard}\n"
                + "    * Hist #0: ActivityRecord{1 u0 com.example.player/.MainActivity t101}\n"
                + "  * Task{def #102 type=standard}\n"
                + "    * Hist #0: ActivityRecord{2 u0 com.example.player/.MainActivity t102}\n";

        ActivityRecordTaskParser.Snapshot snapshot =
                ActivityRecordTaskParser.parse(dump, PKG, ACTIVITY);

        assertEquals(List.of(101, 102), snapshot.matchingTaskIds(0));
    }

    @Test public void doesNotCountTwoActivityRecordsInOneTaskAsTwoTasks() {
        String dump = "Display #0 (activities from top to bottom):\n"
                + "  * Task{abc rootTaskId=301 type=standard}\n"
                + "    * Hist #0: ActivityRecord{1 u0 com.example.player/.MainActivity t301}\n"
                + "    * Hist #1: ActivityRecord{2 u0 com.example.player/.DetailActivity t301}\n";

        ActivityRecordTaskParser.Snapshot snapshot =
                ActivityRecordTaskParser.parse(dump, PKG, ACTIVITY);

        assertEquals(List.of(301), snapshot.matchingTaskIds(0));
    }

    @Test public void distinguishesPackagePresenceFromExactActivityMatch() {
        String dump = "Display #0 (activities from top to bottom):\n"
                + "  * Task{abc #401 type=standard}\n"
                + "    * Hist #0: ActivityRecord{1 u0 com.example.player/.SettingsActivity t401}\n";

        ActivityRecordTaskParser.Snapshot snapshot =
                ActivityRecordTaskParser.parse(dump, PKG, ACTIVITY);

        assertTrue(snapshot.hasPackageTask());
        assertTrue(snapshot.allMatchingTaskIds().isEmpty());
    }

    @Test public void doesNotMatchClassNamePrefixes() {
        String dump = "Display #0 (activities from top to bottom):\n"
                + "  * Task{abc #501 type=standard}\n"
                + "    * Hist #0: ActivityRecord{1 u0 com.example.player/.MainActivityAlias t501}\n";

        ActivityRecordTaskParser.Snapshot snapshot =
                ActivityRecordTaskParser.parse(dump, PKG, ACTIVITY);

        assertTrue(snapshot.allMatchingTaskIds().isEmpty());
    }
}
