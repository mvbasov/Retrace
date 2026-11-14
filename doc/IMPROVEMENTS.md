# Retrace improvement plan

This document is the result of a review of the code at version 26.11.2. It lists the faults and the weak points that the review found, and it gives one task for each. A person or an AI model with less experience can do each task from its text alone.

This document uses ASD-STE100 Simplified Technical English, the same as `CLAUDE.md`.

---

## 1. How to use this plan

1. Read `CLAUDE.md` and `doc/TERMINOLOGY.md` first. Each rule there applies to each task here.
2. Do the tasks in the sequence of section 4. Do one task in one patch.
3. Do not start a task of section 5 without the answer of the maintainer.
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
| `TrackService` | Each line | Tasks 1, 2, 3, 5 and 7. |
| `TrackStore`, `TrackProvider` | Each line | Task 4. The provider is safe: it gives only files of the track directory. |
| `ReferenceSelector`, `RouteAgreement` | Each line, and runs with many seeds | Task 1. |
| Activities and dialogs | The threads and the life cycle | No fault. Each background result checks that the activity is alive. |
| `GpxReader`, `GpxWriter`, `PointLog` | The formats and the errors | Task 6. A GPX file goes to a temporary file first, thus a stop of the process cannot break it. |
| `TrackMatcher`, `WayBuilder`, `MapView`, the tile classes | The structure only | Not examined in depth. See section 6. |

---

## 3. The tasks in one table

| Task | Type | Size | Needs the maintainer |
| --- | --- | --- | --- |
| 1. A loop selects the opposite direction | Fault | Small | No |
| 2. A replay loses the track segments | Fault | Small | No |
| 3. Wrong sentences in the banner of `TrackService` | Comment | Small | No |
| 4. Each list and each trip start read all GPX files | Speed | Medium | No |
| 5. The comparison logic of `TrackService` has no test | Structure | Large | No |
| 6. Save the accuracy of each point | Feature | Medium | Yes |
| 7. Do not count the pause in the comparison | Feature | Small | Yes |
| 8. A GPS button in the notification of the pause | Feature | Small | Yes |
| 9. Compile the Android code in the test workflow | Build | Small | Yes |
| 10. A Russian translation of the user interface | Feature | Medium | Yes |

---

## 4. Tasks that need no decision

### Task 1. A loop selects the opposite direction

**The fault.** The reference track is a loop: its finish is near its start. The automatic selection then selects the opposite direction in about half of the trips. The comparison is then wrong for the whole trip.

**The cause.** The candidate of the opposite direction starts at the same place. After some meters, its matcher is off the route. Then it finds the route again near the finish of the reversed track. The first leg of the loop is the last leg of the reversed track. The distance along the route jumps from 0 m to almost the full length. `Trial.progress()` in `ReferenceSelector` is the distance along the route minus `firstAlong`. Thus the progress is larger than the decision distance at once, and the selection decides for this candidate.

**The correction.** A candidate cannot move farther along the route than the trip moved. A larger change is a new join, and the progress starts again there. The review made this change and ran it: the loop then selects the correct direction for each of 120 trips.

**File:** `app/src/main/java/net/basov/retrace/core/ReferenceSelector.java`

**Steps:**

1. In the class `Trial`, add two fields after `firstAlong`:

   ```java
   double lastAlong = Double.NaN;
   double lastAlongTravelM;
   ```

2. Add a field to `ReferenceSelector` after `decisionM`:

   ```java
   private final double jumpSlackM;
   ```

3. In the constructor, after the line that sets `decisionM`, add:

   ```java
   jumpSlackM = 2 * profile.offRouteM;
   ```

4. In `update()`, find this code:

   ```java
   if (t.last.state == TrackMatcher.State.ON_ROUTE) {
       if (Double.isNaN(t.firstAlong)) {
           t.firstAlong = t.last.alongM;
       }
   ```

   Replace it with this code:

   ```java
   if (t.last.state == TrackMatcher.State.ON_ROUTE) {
       // A jump of the place along the route is a new join. The
       // progress then starts again there.
       boolean jump = !Double.isNaN(t.lastAlong)
               && Math.abs(t.last.alongM - t.lastAlong)
               > travelledM - t.lastAlongTravelM + jumpSlackM;
       if (Double.isNaN(t.firstAlong) || jump) {
           t.firstAlong = t.last.alongM;
       }
       t.lastAlong = t.last.alongM;
       t.lastAlongTravelM = travelledM;
   ```

5. Add two sentences about the jump rule to the banner comment of the class.

**Test.** Add the method `aLoopKeepsItsDirection` to `ReferenceSelectorTest`. Give it a comment that tells why it exists.

- The route: `{{0, 0}, {400, 0}, {400, 400}, {0, 400}, {0, 10}}`.
- For each seed from 1 to 30, make the saved track and the trip with `TestTracks.ideal`, `TestTracks.noisy` with 4 m and `TestTracks.recorded`. Use walking, 1.4 m/s, and different seeds for the two.
- Get the candidates with `ReferenceSelector.nearRoute`. Give each point of the trip to `update()` until the state is not `SELECTING`.
- Check for each seed: the state is `SELECTED`, and `current().reversed` is false.

Before the correction, about 16 of the 30 seeds fail. Run the new test once without the correction, to see that it finds the fault.

**Documents.** Add one list item to section 5.5 of `doc/PLAN.md` about the jump rule.

**Done when:** `test/run.sh` passes, and the new test fails without the correction.

**Do not change:** an out-and-back route on one line still selects the opposite direction in some trips. See section 6.

---

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

**The weak point.** `TrackService` has more than 1100 lines. About 250 of them decide the comparison: the manual selection, the automatic selection, the stop of a comparison that differs, the smoothing and the finish signal. This logic uses no Android class, but it is in an Android class. Thus no test runs it. The faults of tasks 1 and 2 show the cost.

**The goal.** Move this logic to a new class `TripComparison` in `core`, with no change of behavior. Then test it on the JVM.

**Do this task after tasks 1 to 4.** Do it in two patches: the move first, then the tests.

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
7. In `TrackService`, make one `TripComparison` in `onCreate()`. Its `TrackSource` is an inner class: `near` calls `store.filesNear` and returns the paths, and `load` calls `store.load(new File(id))`.
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

## 5. Tasks that need a decision of the maintainer

Ask the question of each task first. Do the task only after a clear answer.

### Task 6. Save the accuracy of each point

**The question.** "Do you want the accuracy of each point in the log file and in the GPX file? The files become larger."

**Why.** The maintainer asked for this earlier. With the accuracy in the file, a later analysis can find bad points. A replay then also uses the real accuracy in `RecordFilter`.

**Steps after a yes:**

1. `PointLog`: the format number becomes 3. `line()` writes the accuracy as a fifth value. The reader accepts formats 1, 2 and 3. A point line with four values has no accuracy.
2. `GpxWriter`: write the accuracy of a point inside the `trkpt` element, in an `extensions` element. Use a `retrace:` element, the same as the members of a way, with the namespace `WAY_NAMESPACE`. Write one decimal. Write nothing for a point with no accuracy.
3. `GpxReader`: read that element into `TrackPoint.accuracyM`. A file with no such element reads as before.
4. Add tests to `SegmentsTest` or `GpxTest`:
   - The accuracy stays after a write and a read.
   - A format 2 log file is still read.
   - A GPX file of a different application is still read.
5. Check with `GpxReader` that the GPX file stays valid XML. Update section 5.2 of `doc/PLAN.md`.

**Risk.** `RouteAgreementTest` and other tests use `TestTracks.recorded`. Check that no test changes its result.

### Task 7. Do not count the pause in the comparison

**The question.** "After Stop and Continue, the time of the pause counts as time behind the reference track. Do you want the comparison without the time of the pause?"

**Steps after a yes:**

1. In `TrackService`, add a field `pausedTotalMs` and a field `pauseStartMs`. `pause()` sets `pauseStartMs` to the time of the last position. The first accepted point after `resume()` adds its time minus `pauseStartMs` to `pausedTotalMs`.
2. Give the comparison a copy of the point with `timeMs - pausedTotalMs`. The saved point and the log file keep the real time.
3. `select()` gives the old points to the new matcher again. It must apply the same shift for each track segment. Thus keep the shift of each track segment in a list, next to `SEGMENT_STARTS`.
4. If task 5 is done, put this logic into `TripComparison` and test it there.

### Task 8. A GPS button in the notification of the pause

**The question.** "Do you want the button GPS off and GPS on in the notification of the pause too?"

**Steps after a yes:** in `TrackService.notification()`, add a third action to the paused state: `ACTION_PAUSE_FIXES` with request code 5. The label is `R.string.trip_gps_off` if `s.fixesOn`, else `R.string.trip_gps_on`. Use the icon `ic_notification`. Correct the banner comment.

### Task 9. Compile the Android code in the test workflow

**The question.** "The test workflow does not compile the Android classes. A fault there shows only in the release build. Do you want a compile step in the test workflow? It makes each run some minutes longer."

**Steps after a yes:** follow section 7 of `CLAUDE.md`. Add a stage to `Dockerfile` and `Dockerfile.ci` that runs the Java compile task of Gradle with no signing. Change `BuildFilesTest` and `doc/RELEASE.md` together with the workflows.

### Task 10. A Russian translation of the user interface

**The question.** "Do you want a Russian translation in `res/values-ru/strings.xml`? Who checks the terms?"

**Steps after a yes:** copy `strings.xml`, translate each value, and keep each format argument such as `%1$s`. Do not translate the names of the keys. The maintainer reads each string before the patch.

---

## 6. Known limits with no task

These points are correct behavior for now. Do not change them without a request.

- **An out-and-back route on one line.** The two directions are the same line at the start. The selection takes the opposite direction in about one trip of ten. The route and the distance are then correct, and only the times of the reference track differ.
- **A reference track with two or more track segments.** `TrackMatcher` and `WayBuilder` use all points as one line. The step between two track segments is a straight line with the time of the pause.
- **A waypoint with the GPS off.** During the pause with GPS off, a new waypoint goes to the last known position.
- **The elapsed time of the notification** continues during the pause. See task 7.

These parts did not get a review in depth. A later review can start here:

- `WayBuilder` has more than 1100 lines in one file. Its tests are good, but a change there is a risk.
- `TrackMatcher`: in the runs for task 1, the matcher found the route again after an off-route state, against the direction of the movement. Task 1 corrects the effect in the selection only. A check of the direction rule at that place is a possible next step.
- `MapView` and the tile classes.
