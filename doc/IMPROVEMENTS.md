# Retrace improvement plan

This document is the result of a review of the code at version 26.11.2. It lists the faults and the weak points that the review found, and it gives one task for each. A person or an AI model with less experience can do each task from its text alone.

This document uses ASD-STE100 Simplified Technical English, the same as `CLAUDE.md`.

---

## 1. How to use this plan

1. Read `CLAUDE.md` and `doc/TERMINOLOGY.md` first. Each rule there applies to each task here.
2. Do the tasks in the sequence of section 3.1. Do one task in one patch.
3. The maintainer approved the tasks of section 5 on 2026-11-14. Do not ask again.
4. Do not change code that the task does not name. If a step is not clear, stop and ask the maintainer.
5. After each task, remove its section from this document in the same patch. Thus this document always shows the work that stays.

### 1.1 The checks of each patch

Run these checks before you give a patch:

1. Run `test/run.sh`. Each test class must print "every check passed".
2. Read each new comment and each new document line. Count the words of each sentence. See section 8 of `CLAUDE.md`.
3. Bump `appVersion` in `app/build.gradle` by one.
4. Write the commit message file in the format of section 5 of `CLAUDE.md`.
5. Tell the maintainer that the Android build did not run, if you cannot run it.

`test/run.sh` compiles only the package `core`. It does not compile the Android classes. Thus read each changed Android class again with care: each name, each import and each bracket.

### 1.2 The rule for the tests

Use only invented tracks from `TestTracks`. Do not add a real track. Check a change of the matcher or of the selection with 20 seeds or more.

---

## 2. What the review covered

| Part | Depth | Result |
| --- | --- | --- |
| `TrackService` | Each line | Tasks 2, 3, 5 and 7. |
| `TrackStore`, `TrackProvider` | Each line | Task 4. The provider is safe: it gives only files of the track directory. |
| `ReferenceSelector`, `RouteAgreement` | Each line, and runs with many seeds | One fault. Patch 54 corrected it. |
| Activities and dialogs | The threads and the life cycle | No fault. Each background result checks that the activity is alive. |
| `GpxReader`, `GpxWriter`, `PointLog` | The formats and the errors | Task 6. A GPX file goes to a temporary file first, thus a stop of the process cannot break it. |
| `TrackMatcher`, `WayBuilder`, `MapView`, the tile classes | The structure only | Not examined in depth. See section 6. |

---

## 3. The tasks in one table

| Task | Type | Size | Section |
| --- | --- | --- | --- |
| 2. A replay loses the track segments | Fault | Small | 4 |
| 3. Wrong sentences in the banner of `TrackService` | Comment | Small | 4 |
| 4. Each list and each trip start read all GPX files | Speed | Medium | 4 |
| 5. The comparison logic of `TrackService` has no test | Structure | Large | 4 |
| 6. Save the accuracy of each point | Feature | Medium | 5 |
| 7. Do not count the pause in the comparison | Feature | Medium | 5 |
| 8. A GPS button in the notification of the pause | Feature | Small | 5 |
| 9. Compile the Android code in the test workflow | Build | Small | 5 |

The maintainer declined task 10, a Russian translation. Do not do it.

### 3.1 The sequence

Do the tasks in this sequence: 2, 3, 9, 8, 6, 4, 5, 7.

- Task 9 comes early. After it, each push compiles the Android classes. Thus a fault in an Android class of a later task shows at once.
- Task 8 comes after task 3, because the two change the same comment.
- Task 7 needs task 5. It changes the class that task 5 makes.

---

## 4. Tasks that need no decision

### Task 2. A replay loses the track segments

**The fault.** `TrackService.beginReplay()` keeps only `track.points`. A replay of a track with two track segments draws a line between them, and counts that step as distance.

**File:** `app/src/main/java/net/basov/retrace/TrackService.java`

**Steps:**

1. Add a field near `replayPoints`:

   ```java
   private List<Integer> replaySegmentStarts = new ArrayList<>();
   ```

2. In `beginReplay()`, after `replayPoints = track.points;`, add `replaySegmentStarts = track.segmentStarts;`.
3. In `resetTrip()`, after `replayPoints = null;`, add `replaySegmentStarts = new ArrayList<>();`.
4. In `replayNext()`, find `TrackPoint p = replayPoints.get(replayIndex++);`. Replace it with:

   ```java
   int index = replayIndex++;
   TrackPoint p = replayPoints.get(index);
   if (replaySegmentStarts.contains(index)) {
       // The same steps as Continue after Stop of a trip.
       newSegmentPending = true;
       filter.newSegment();
   }
   ```

5. In the banner comment, add one sentence: a replay keeps the track segments of the replayed track.

**Test.** `TrackService` has no JVM test. `RecordFilter.newSegment()` already has a test in `TrackTest`. Ask the maintainer to replay a track with two track segments on a device.

**Done when:** the map of the replay shows no line between the two track segments.

---

### Task 3. Wrong sentences in the banner of `TrackService`

**The fault.** Two changes left faults in the comments. `CommentStyleTest` cannot find them.

**File:** `app/src/main/java/net/basov/retrace/TrackService.java`

**Steps:**

1. In the banner comment, find the text that ends with "Each pause starts with the fixes on. The notification has the same buttons. A replay has no pause: Stop ends it." The line breaks are wrong, and the notification does not have the GPS button.
2. Replace the last two sentences with: "The notification has the buttons Stop and Waypoint, and during the pause Continue and End. A replay has no pause: Stop ends it."
3. Wrap the paragraph again, with the same line width as the comment lines around it.
4. Find the comment above `requestFixes()`. One line is longer than 100 characters. Wrap it again.

**Done when:** `test/run.sh` passes. No code line changes.

---

### Task 4. Each list and each trip start read all GPX files

**The weak point.** `TrackStore.list()` parses each GPX file at each load of the list screen. `TrackService.startSearch()` parses each GPX file at the first point of each trip. During `list()`, the lock of `TrackStore` stops the worker thread of the service. With some hundred tracks, the list is slow, and the first fixes of a trip wait.

**The design.** A cache in memory keeps a summary of each file. The key is the path. The entry is valid while the time of the last change and the length of the file are the same. The summary holds the `Entry` of the list and the bounds of the track.

**Part A: the class `TrackBounds` in `core`.**

1. Make `app/src/main/java/net/basov/retrace/core/TrackBounds.java`. It is immutable, with four fields: `minLat`, `maxLat`, `minLon`, `maxLon`.
2. Add `public static TrackBounds of(List<TrackPoint> points)`. For an empty list, return bounds that contain no point (use `Double.NaN` and return false from `near`).
3. Add `public boolean near(TrackPoint fix, double limitM)`. Change the limit to degrees: `limitM / 111320.0` for the latitude. For the longitude, divide that value by the cosine of the latitude of the fix, and by 0.01 at least. Return true if the fix is inside the bounds made larger by these values.
4. The method must never return false for a fix that is inside `limitM` of a point of the track. A true for a far fix is permitted, because the precise check comes later.
5. Make `TrackBoundsTest` with these cases:
   - A fix on the track.
   - A fix 10 m outside the bounds, with a limit of 20 m.
   - A fix 500 m outside the bounds.
   - A track at latitude 60.
   - An empty list.

**Part B: the class `SummaryCache` in `core`.**

1. Make `app/src/main/java/net/basov/retrace/core/SummaryCache.java`, a generic class `SummaryCache<V>`. It has no Android import.
2. It holds a `HashMap<String, Item<V>>`. An item holds `modifiedMs`, `length` and the value.
3. Add `V get(String path, long modifiedMs, long length)`. It returns the value if the two numbers agree, else null.
4. Add `void put(String path, long modifiedMs, long length, V value)`, `void remove(String path)` and `void retain(Collection<String> paths)`. `retain` removes each path that is not in the collection.
5. The class has no lock. The caller holds the lock.
6. Make `SummaryCacheTest`: a hit, a miss after a change of the time, a miss after a change of the length, `remove` and `retain`.

**Part C: `TrackStore`.**

1. Add a static field: `private static final SummaryCache<Summary> CACHE = new SummaryCache<>();`. Use it only inside `synchronized (LOCK)`.
2. Add a private static class `Summary` with two fields: the `Entry` and the `TrackBounds`.
3. Add a private method `Summary summaryLocked(File f)`. It asks the cache with `f.getPath()`, `f.lastModified()` and `f.length()`. On a miss, it reads the file with `readGpx`, makes the `Entry` and the bounds, and puts them into the cache. For a file that it cannot read, it makes `new Entry(f)` and empty bounds, and puts them into the cache too.
4. Change `list()` to use `summaryLocked(f).entry`. At the end of `list()`, call `CACHE.retain` with the paths of the files.
5. In `writeGpx` and in `delete`, the caller holds the lock. After the change of the file, call `CACHE.remove(file.getPath())`.
6. Add a method for the service:

   ```java
   // Returns the files with a track that can be near the fix. The check
   // uses the bounds only. The caller does the precise check.
   List<File> filesNear(TrackPoint fix, double limitM)
   ```

   It uses `summaryLocked(f).bounds.near(fix, limitM)` for each file of `trackFiles()`.

**Part D: `ReferenceSelector` and `TrackService`.**

1. In `ReferenceSelector.routeNear()`, the limit is `START_FACTOR * (profile.radiusM + 2 * accuracy)`. Move this calculation to a new method `public static double startLimitM(Profile profile, TrackPoint fix)`. `routeNear()` calls it. The behavior does not change.
2. In `TrackService.startSearch()`, replace `store.trackFiles()` with `store.filesNear(first, ReferenceSelector.startLimitM(profile, first))`. The loop and the call of `routeNear` stay.

**Documents.** Add two list items to section 5.2 of `doc/PLAN.md`: the cache, and the bounds check before the selection.

**Done when:** `test/run.sh` passes with the two new test classes. The list screen and the automatic selection give the same results as before.

**Risk.** A wrong cache shows old data. Thus the key must use the time and the length, and each write must remove the entry.

---

### Task 5. The comparison logic of `TrackService` has no test

**The weak point.** `TrackService` has more than 1100 lines. About 250 of them decide the comparison. They hold the manual selection, the automatic selection, the stop of a comparison that differs, the smoothing and the finish signal. This logic uses no Android class, but it is in an Android class. Thus no test runs it. The fault of task 2, and the loop fault that patch 54 corrected, show the cost.

**The goal.** Move this logic to a new class `TripComparison` in `core`, with no change of behavior. Then test it on the JVM.

**Do this task after tasks 2, 3 and 4.** Do it in two patches: the move first, then the tests.

**Patch A: the move.**

1. Make the interface `app/src/main/java/net/basov/retrace/core/TrackSource.java`:

   ```java
   public interface TrackSource {
       // The ids of the saved tracks that can be near the fix.
       List<String> near(TrackPoint fix, double limitM);

       // Reads one saved track.
       Track load(String id) throws IOException;
   }
   ```

2. Make `app/src/main/java/net/basov/retrace/core/TripComparison.java`. Its constructor takes a `TrackSource`.
3. Move these fields from `TrackService` to it: `referencePath`, `referenceReversed`, `referenceWayName`, `selection`, `selector`, `matcher`, `smoother`, `agreement`, `match`, `smoothedDeltaMs` and `finishSignaled`. Add a field `profile` and a field `excludedId` for the replayed track.
4. Move these methods with their code: `resetComparison`, `loadReference`, `startSearch`, `compare`, `stopDifferentComparison`, `compareCandidates` and `follow`. Use these public names:

   | Method | Old code |
   | --- | --- |
   | `void reset(Profile p)` | `resetComparison(p)` |
   | `void searchAutomatically()` | the line `selection = SELECTION_SEARCH` |
   | `void setReference(String id, boolean reversed)` | `loadReference` |
   | `void setExcluded(String id)` | the check of `replayPath` in `startSearch` |
   | `boolean add(TrackPoint fix)` | `compare(fix)` |
   | `void select(String id, boolean reversed, boolean automatic, List<TrackPoint> points)` | the first part of `select()`: the reset, the selection and the loop over the points |

5. Add a public getter for each value that `publish()` and `finish()` read: the reference id, the reversed flag, the way name, the selection, the match, the smoothed difference and the finish signal.
6. `startSearch` catches `IOException` and `IllegalArgumentException` for each track, the same as now.
7. In `TrackService`, add the field `private TripComparison comparison;`. Make the object in `onCreate()`. Its `TrackSource` is an inner class: `near` calls `store.filesNear` and returns the paths, and `load` calls `store.load(new File(id))`.
8. Replace each use of a moved field with a call of the new class. `resetTrip()` calls `reset(p)`. `beginReplay()` calls `setExcluded(path)` after `resetTrip()`.
9. Do not change the sequence of the calls. Do not change a condition.
10. Update the banner comment of `TrackService` and the table of `doc/MATCHING.md`.

**Patch B: the tests.** Make `TripComparisonTest` with a `TrackSource` that holds tracks in a map. Each case uses invented tracks and 10 seeds or more.

| Case | Check |
| --- | --- |
| A manual reference track, the trip follows it | `add()` returns true one time only, at the finish. The selection is `SELECTION_MANUAL`. |
| No reference track | `add()` always returns false. The match is null. |
| The automatic selection with one saved track of the same route | The selection goes from `SELECTION_SEARCH` to `SELECTION_TEMPORARY` to `SELECTION_AUTOMATIC`. |
| The automatic selection, no saved track near | The selection is `SELECTION_NOT_FOUND` after the first point. |
| The automatic reference track differs later | The selection becomes `SELECTION_NOT_FOUND`, and the match is null. Use the routes of `RouteAgreementTest`. |
| A manual reference track that differs | The comparison stays to the end. |
| `select()` during the trip, after the finish place | The finish signal is set, and the next `add()` returns false. |
| The excluded track | It is never the reference track of its own replay. |
| A track that the source cannot read | `setReference` gives a trip with no comparison, and no exception. |

**Done when:** `TrackService` holds no matcher and no selector, and `test/run.sh` passes with the new test class.

**Risk.** This is the largest task. A small difference changes the comparison of each trip. Compare the old and the new code line by line before you give the patch. Ask the maintainer to record one trip and run one replay on a device.

---

## 5. Tasks that the maintainer approved

The maintainer approved these tasks on 2026-11-14. Do not ask again.

### Task 6. Save the accuracy of each point

**The goal.** The log file and the GPX file keep the accuracy of each point. A later analysis can then find the bad points. A replay then gives the real accuracy to `RecordFilter`, the same as the trip.

**Part A: the log file.** File: `app/src/main/java/net/basov/retrace/core/PointLog.java`.

1. Change `FORMAT` from 2 to 3.
2. Change `line()` so that it also writes the accuracy as a fifth value:

   ```java
   return p.timeMs + " " + p.latitude + " " + p.longitude + " " + p.elevationM
           + " " + p.accuracyM;
   ```

   Java writes `NaN` for a point with no accuracy, and `Double.parseDouble` reads it back.
3. In `read()`, accept the formats "1", "2" and "3" in the header.
4. In `parsePoint(String[] f)`, accept four or five values. With five values, the fifth is the accuracy. With four values, the accuracy is `Double.NaN`. `parseWaypoint` gives four values, thus a waypoint does not change.
5. Update the banner comment: format 3 adds the accuracy, and the reader accepts formats 1, 2 and 3.
6. In `TrackTest`, find the check that reads `"RETRACE-LOG 3 walking 1\n"` as an unknown format. Change the 3 to 4.

**Part B: the GPX file.** File: `app/src/main/java/net/basov/retrace/core/GpxWriter.java`.

1. Write the Retrace namespace on the root element. Change the line that writes `<gpx` so that it also writes ` xmlns:retrace="` + `WAY_NAMESPACE` + `"`. The `retrace:way` element of a way keeps its own declaration. Two declarations of the same namespace are correct XML.
2. In the loop of the track points, after `appendElevationAndTime`, add the accuracy if it is a finite number:

   ```java
   if (!Double.isNaN(p.accuracyM) && !Double.isInfinite(p.accuracyM)) {
       line.append("<extensions><retrace:accuracy>");
       appendFixed(line, p.accuracyM, 1);
       line.append("</retrace:accuracy></extensions>");
   }
   ```

   GPX 1.1 puts `extensions` last in a `trkpt`. Thus it comes after `time`. Do not write the accuracy for a waypoint.
3. Add two sentences to the banner comment about the accuracy element.

**Part C: the GPX reader.** File: `app/src/main/java/net/basov/retrace/core/GpxReader.java`.

1. In the handler, add a field `private double accuracy;`.
2. In `startElement`, where a point starts, set `accuracy = Double.NaN;`.
3. In `endElement`, add this branch before the branch for the end of `trkpt`:

   ```java
   } else if (inPoint && name.equals("accuracy")
           && GpxWriter.WAY_NAMESPACE.equals(uri)) {
       accuracy = parseDouble(value);
   ```

4. Give `accuracy` to the `TrackPoint` of a `trkpt` and of a `rtept`, in place of `Double.NaN`.
5. Update the banner comment.

**Tests.** Add a method to `SegmentsTest` for each case. Give each method a comment that tells why it exists.

- A track with the accuracies 4.5, `Double.NaN` and 12.0 keeps them after `GpxWriter.write` and `GpxReader.read`. 4.5 comes back as 4.5, and `NaN` stays `NaN`.
- The GPX text holds `retrace:accuracy` two times for these three points.
- A GPX text of a different application with `<extensions><other:accuracy xmlns:other="urn:x">7</other:accuracy></extensions>` in a `trkpt` gives `NaN`.
- A log line with five values gives the accuracy. A format 2 log file is still read, with `NaN`.

**Documents.** Add one list item to section 5.2 of `doc/PLAN.md`. Add one sentence to `README.md`: the GPX file keeps the accuracy of each point in a Retrace extension, and other applications ignore it.

**Done when:** `test/run.sh` passes. Each test class that uses `TestTracks.recorded` gives the same result as before.

**Risk.** A replay now uses the saved accuracy in `RecordFilter`. The trip used the same accuracy, thus the replay keeps the same points. An old track has no accuracy, and its replay does not change.

---

### Task 7. Do not count the pause in the comparison

**The goal.** After Stop and Continue, the difference to the reference track does not count the time of the pause. The saved points, the log file and the GPX file keep the real time. The elapsed time on the trip screen and in the notification stays the clock time.

**Do this task after task 5.** It changes `TripComparison`.

**Part A: `TripComparison`.** File: `app/src/main/java/net/basov/retrace/core/TripComparison.java`.

1. Add three fields:

   ```java
   // The index of the first point after each pause, and the sum of the
   // pauses up to that point, in the same sequence.
   private final List<Integer> shiftStarts = new ArrayList<>();
   private final List<Long> shifts = new ArrayList<>();
   // The count of the points that add() got since the last reset.
   private int added;
   ```

2. In `reset(Profile p)`, set `added = 0`. Do not clear the two lists there. `select()` calls `reset()` and then gives all points again. The points after a pause must get the same shift again.
3. Add a method `clearPauses()` that clears the two lists. `TrackService.resetTrip()` calls it at the start of each trip and each replay.
4. Add a method `addPause(long pausedMs)`. The next point that `add()` gets starts the new shift:

   ```java
   public void addPause(long pausedMs) {
       long total = shifts.isEmpty() ? 0 : shifts.get(shifts.size() - 1);
       shiftStarts.add(added);
       shifts.add(total + Math.max(0, pausedMs));
   }
   ```

5. Add a private method `long shiftAt(int index)`. It returns the shift of the last start that is not after the index, or 0.
6. At the start of `add(TrackPoint fix)`, make the shifted point and use it in place of `fix` in the whole method:

   ```java
   long shift = shiftAt(added++);
   TrackPoint p = shift == 0 || !fix.hasTime() ? fix : new TrackPoint(fix.latitude,
           fix.longitude, fix.timeMs - shift, fix.elevationM, fix.accuracyM);
   ```

   `follow()` also gets `p`. Thus `DeltaSmoother` uses the shifted time too.
7. Update the banner comment of the class.

**Part B: `TrackService`.**

1. Add a field `private long pauseClockMs;`.
2. In `pause()`, after `paused = true;`, set `pauseClockMs = SystemClock.elapsedRealtime();`.
3. In `resume()`, before `paused = false;`, call `comparison.addPause(SystemClock.elapsedRealtime() - pauseClockMs);`.
4. In `resetTrip()`, call `comparison.clearPauses();`.

The clock since the boot does not jump when the user changes the time of the device. The fixes of a pause do not go to the comparison. Thus the index of `add()` agrees with the index of `POINTS`.

**Tests.** Add these cases to `TripComparisonTest`. Use walking, 1.4 m/s, a straight route of 1200 m, 4 m noise and 10 seeds.

| Case | Check |
| --- | --- |
| The trip has a pause of 600 s at 600 m: each later point has 600 000 ms more. No `addPause`. | The difference at the finish is about +600 s, with a tolerance of 30 s. |
| The same trip with `addPause(600000)` before the first point after the pause. | The difference at the finish is about 0 s, with a tolerance of 30 s. |
| The same trip with the pause, then `select()` with the same reference track. | The difference at the finish is the same as in the second case, with a tolerance of 1 s. |
| `clearPauses()`, then a new trip with no pause. | The difference at the finish is about 0 s. |

**Documents.** In section 5.1 of `doc/PLAN.md`, change "the elapsed time continues" in the paragraph of Stop. The new text: the elapsed time on the screen continues, but the comparison does not count the time of the pause. Add the same sentence to `README.md`.

**Done when:** `test/run.sh` passes with the new cases.

---

### Task 8. A GPS button in the notification of the pause

**The goal.** During the pause, the notification has a third button: GPS off or GPS on. It does the same as the button of the trip screen.

**Steps:**

1. Make `app/src/main/res/drawable/ic_gps.xml`, a vector icon of a target. Use strokes, not a fill type, because Android 6 does not read `android:fillType`:

   ```xml
   <?xml version="1.0" encoding="utf-8"?>
   <!-- The icon of the GPS button in the notification of the pause. -->
   <vector xmlns:android="http://schemas.android.com/apk/res/android"
       android:width="24dp"
       android:height="24dp"
       android:viewportWidth="24"
       android:viewportHeight="24">
       <path
           android:strokeColor="#FFFFFFFF"
           android:strokeWidth="2"
           android:pathData="M12,5 A7,7 0 1,1 11.99,5 M12,1 V5 M12,19 V23 M1,12 H5 M19,12 H23" />
       <path
           android:fillColor="#FFFFFFFF"
           android:pathData="M12,9 A3,3 0 1,1 11.99,9 Z" />
   </vector>
   ```

2. In `TrackService.notification()`, in the branch of the pause, add a third action after End:

   ```java
   b.addAction(action(ACTION_PAUSE_FIXES, 5, R.drawable.ic_gps,
           s.fixesOn ? R.string.trip_gps_off : R.string.trip_gps_on));
   ```

   The request code 5 is free. The codes 1 to 4 are in use.
3. Change the comment above the actions: after Stop, the buttons are Continue, End and GPS off or GPS on.
4. Change the banner comment: the sentence of task 3 about the notification gets the GPS button of the pause.
5. `togglePauseFixes()` already calls `publishAndNotify()`. Thus the label changes after each push. Do not change that method.
6. Add one sentence to the paragraph of Stop in `README.md` and in section 5.1 of `doc/PLAN.md`.

**Test.** No JVM test can show a notification. Ask the maintainer to push the button in the notification two times during a pause. The label and the dot of the trip screen must change each time.

**Done when:** `test/run.sh` passes, and the maintainer confirms the button on a device.

---

### Task 9. Compile the Android code in the test workflow

**The goal.** The test workflow also compiles each Android class. Now a fault in an Android class shows only in the release build.

**The design.** A new Docker stage `compile` runs the Gradle task `compileDebugJavaWithJavac`. The debug variant needs no keystore, and the task makes no APK. The stage copies `/gate-passed` from the test stage, thus it also runs the tests. The test workflow builds this stage in place of the test stage. Each run is some minutes longer, because the cache mount of Gradle does not go into the cache of GitHub.

**Part A: `Dockerfile`.** Add this stage between the stage `test` and the stage `project_builder`. `project_builder` must stay the last stage, because `local/build.sh` builds the last stage.

```dockerfile
# ------------------------------ compile ------------------------------
# The compile check of the Android code. test/run.sh compiles only the
# package core. This stage compiles each Java class of the application.
# The debug variant needs no keystore, and the task makes no APK.
# Run the check alone with this command:
#   docker buildx build --target compile .
FROM retrace-base:latest AS compile

COPY --from=test /gate-passed /tmp/gate-passed

COPY . .

RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    gradle --no-daemon compileDebugJavaWithJavac
```

**Part B: `Dockerfile.ci`.** Add the same stage between `test` and `project_builder`. The only difference is the FROM line: `FROM base_env AS compile`. The stage `export` stays the last stage.

**Part C: `.github/workflows/test.yml`.**

1. Change `--target test` to `--target compile`.
2. Change the header comment: the workflow runs the quality gate and the compile check of the Android code.

**Part D: `BuildFilesTest`.** File: `test/java/net/basov/retrace/repocheck/BuildFilesTest.java`. After the check of the test stage, add:

```java
// The compile stage of Dockerfile is in Dockerfile.ci. The test workflow
// builds it.
List<String> compileStage = stage(local, "FROM retrace-base:latest AS compile");
check("Dockerfile: compile stage", false, compileStage.isEmpty());
checkInOrder("compile stage in Dockerfile.ci", compileStage,
        stage(ci, "FROM base_env AS compile"));
check("test workflow: compile target", true, test.contains("--target compile"));
```

Read the method `main` first. Use the names of the variables that it uses for the lines of `Dockerfile`, of `Dockerfile.ci` and of the text of `test.yml`. If a name differs, use the name of the file.

**Part E: documents.**

1. `doc/RELEASE.md`: in the table of the workflows, the row "Test Retrace" now says: the result of the quality gate and of the compile check. Add a row to the table of faults: "The compile stage fails | A Java fault in an Android class. | Run `docker buildx build --target compile .` and correct the fault."
2. `CLAUDE.md` section 6: add one sentence: the stage `compile` compiles the Android code, and the test workflow builds it.
3. `CLAUDE.md` section 7: in the list item about the two stages of `Dockerfile`, write three stages: `test`, `compile` and `project_builder`.

**Done when:** `test/run.sh` passes, and the first run of the test workflow on GitHub is green. If you cannot run Docker, say so. The maintainer then checks the first run.

**Risk.** If the stage fails on the first run for a reason that is not a Java fault, stop. Give the log to the maintainer. Do not add a package or a tool to `Dockerfile.base` without a question.

---

## 6. Known limits with no task

These points are correct behavior for now. Do not change them without a request.

- **An out-and-back route on one line.** The two directions are the same line at the start. The selection takes the opposite direction in about one trip of ten. The route and the distance are then correct, and only the times of the reference track differ.
- **A reference track with two or more track segments.** `TrackMatcher` and `WayBuilder` use all points as one line. The step between two track segments is a straight line with the time of the pause.
- **A waypoint with the GPS off.** During the pause with GPS off, a new waypoint goes to the last known position.
- **The elapsed time of the notification and of the trip screen** continues during the pause. After task 7, only the comparison does not count the pause.
- **A movement during the pause.** Task 7 removes the time of the pause, not its distance. If the user travels far during the pause, the matcher sees a large step in a short time.

These parts did not get a review in depth. A later review can start here:

- `WayBuilder` has more than 1100 lines in one file. Its tests are good, but a change there is a risk.
- `TrackMatcher`: for a loop, the matcher of the opposite direction found the route again after an off-route state, against the direction of the movement. Patch 54 corrected the effect in the selection only. A check of the direction rule at that place is a possible next step.
- `MapView` and the tile classes.
