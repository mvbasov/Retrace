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

`test/run.sh` compiles only the package `core`. It does not compile the Android classes. Thus read each changed Android class again with care: each name, each import and each bracket. The test workflow of GitHub compiles the Android classes after each push. Ask the maintainer for its result.

### 1.2 The rule for the tests

Use only invented tracks from `TestTracks`. Do not add a real track. Check a change of the matcher or of the selection with 20 seeds or more.

---

## 2. What the review covered

| Part | Depth | Result |
| --- | --- | --- |
| `TrackService` | Each line | Tasks 5 and 7. Patches 56 and 57 corrected a fault and two comments. |
| `TrackStore`, `TrackProvider` | Each line | Task 4. The provider is safe: it gives only files of the track directory. |
| `ReferenceSelector`, `RouteAgreement` | Each line, and runs with many seeds | One fault. Patch 54 corrected it. |
| Activities and dialogs | The threads and the life cycle | No fault. Each background result checks that the activity is alive. |
| `GpxReader`, `GpxWriter`, `PointLog` | The formats and the errors | Task 6. A GPX file goes to a temporary file first, thus a stop of the process cannot break it. |
| `TrackMatcher`, `WayBuilder`, `MapView`, the tile classes | The structure only | Not examined in depth. See section 6. |

---

## 3. The tasks in one table

| Task | Type | Size | Section |
| --- | --- | --- | --- |
| 4. Each list and each trip start read all GPX files | Speed | Medium | 4 |
| 5. The comparison logic of `TrackService` has no test | Structure | Large | 4 |
| 6. Save the accuracy of each point | Feature | Medium | 5 |
| 7. Do not count the pause in the comparison | Feature | Medium | 5 |
| 11. Search for a different route after the trip leaves the route | Feature | Medium | 5 |
| 12. Compare with a track of a different profile | Feature | Medium | 5 |

The maintainer declined task 10, a Russian translation. Do not do it.

### 3.1 The sequence

Do the tasks in this sequence: 6, 4, 12, 5, 7, 11.

- After patch 58, each push compiles the Android classes. Thus a fault in an Android class shows in the test workflow of GitHub. Look at its result after each push.
- Task 7 and task 11 need task 5. They change the class that task 5 makes.
- Task 12 comes before task 5. Thus task 5 moves the code with the new profile rule.

---

## 4. Tasks that need no decision

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

**The weak point.** `TrackService` has more than 1100 lines. About 250 of them decide the comparison. They hold the manual selection, the automatic selection, the stop of a comparison that differs, the smoothing and the finish signal. This logic uses no Android class, but it is in an Android class. Thus no test runs it. The replay fault that patch 56 corrected, and the loop fault that patch 54 corrected, show the cost.

**The goal.** Move this logic to a new class `TripComparison` in `core`, with no change of behavior. Then test it on the JVM.

**Do this task after tasks 4 and 12.** Do it in two patches: the move first, then the tests.

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

The maintainer approved these tasks on 2026-11-14. Do not ask again. The maintainer asked for tasks 11 and 12 on the same day.

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

### Task 11. Search for a different route after the trip leaves the route

**The goal.** A trip with the automatic selection can have no comparison. There are three causes:

- No saved route was near the first point.
- The selection found no candidate that agrees with the trip.
- The trip left its automatic reference track, and `RouteAgreement` stopped the comparison.

Now the trip stays with no comparison to its end. After this task, the trip searches again after it moved some distance. A trip that joins a different saved route then gets a comparison with that route.

**Decisions.** These decisions keep the earlier behavior of the manual choices:

- A reference track that the user selected never stops, and never starts a new search.
- After the user selects "No comparison", the trip never searches.
- A replay with the automatic selection searches again in the same way.

**Do this task after task 5.** It changes `TripComparison`.

**Part A: a long detour differs.** File: `app/src/main/java/net/basov/retrace/core/RouteAgreement.java`.

On a long route, the rule "more off the route than on it" can stay false for a long detour. Add a third rule: a continuous detour of `DETOUR_FACTOR` off-route limits differs. For walking, that is 500 m.

1. Add the constant `public static final double DETOUR_FACTOR = 20;`.
2. Add the fields `private final double detourLimitM;` and `private double detourM;`. In the constructor, set `detourLimitM = DETOUR_FACTOR * profile.offRouteM;`.
3. In `add()`, in the branch of `ON_ROUTE`, set `detourM = 0;`.
4. In the branch of `OFF_ROUTE`, add `detourM += step;`. Then also set `differs = true` if `detourM >= detourLimitM`.
5. Add the third rule to the banner comment.

**Part B: the new search.** File: `app/src/main/java/net/basov/retrace/core/TripComparison.java`.

1. Add the constant `public static final double RESEARCH_FACTOR = 10;`. The new search comes after the trip moved `RESEARCH_FACTOR` off-route limits with no comparison. For walking, that is 250 m. For driving, it is 600 m.
2. Add these fields:

   ```java
   // True while the user wants the automatic selection.
   private boolean automatic;
   // The distance of the points that add() got since the last reset.
   private double travelledM;
   private TrackPoint lastAdded;
   // The value of travelledM when the last search or comparison ended.
   private double searchEndM;
   ```

3. In `reset(Profile p)`, set `travelledM = 0`, `lastAdded = null` and `searchEndM = 0`. Do not change `automatic` there.
4. Set `automatic = true` in `searchAutomatically()`, and in `select()` when its argument `automatic` is true. Set `automatic = false` in `setReference()`, and in `select()` when its argument `automatic` is false.
5. Find each place that sets the selection to `SELECTION_NOT_FOUND`. There are three: the search with no near track, the selector with the state `NONE`, and `stopDifferentComparison()`. At each place, also set `searchEndM = travelledM;`.
6. At the start of `add()`, before the check of `SELECTION_SEARCH`, add:

   ```java
   if (lastAdded != null) {
       travelledM += Geo.distanceM(lastAdded, fix);
   }
   lastAdded = fix;
   if (automatic && selection == RecordingSnapshot.SELECTION_NOT_FOUND
           && travelledM - searchEndM >= RESEARCH_FACTOR * profile.offRouteM) {
       selection = RecordingSnapshot.SELECTION_SEARCH;
   }
   ```

   The code after it then starts the search at this point. `ReferenceSelector` can join a route in the middle, thus the trip can join a different route at any place.
7. Add a paragraph about the new search to the banner comment.

If task 7 is done, `add()` already makes a shifted point. Use the original point `fix` for the distance. The time has no effect on the distance.

**Part C: `TrackService` and the screen.** No change of code is necessary. The trip screen already shows the name of the new temporary reference track. Read `RunActivity` to make sure. If the screen keeps the text "No saved track agrees with this trip" after a new selection, correct that.

**Tests.** Use invented tracks, walking at 1.4 m/s, 4 m noise and 10 seeds.

| Case | Check |
| --- | --- |
| `RouteAgreement`: a route of 3000 m north. The trip leaves it for 600 m and comes back. | `differs()` is true. |
| `RouteAgreement`: the same route, a detour of 300 m. | `differs()` is false. |
| Saved route A goes north from (0, 0) to (0, 1000). Saved route B goes east from (0, 400) to (1500, 400). The trip goes north to (0, 400), then east to (1500, 400). The automatic selection. | At the end, the reference id is B, the match is not null, and the difference at the finish is less than 60 s. |
| The same trip after `select(null, false, false)`, the choice "No comparison". | The selection stays `SELECTION_OFF` to the end. |
| The same trip with the manual reference track A. | The selection stays `SELECTION_MANUAL`, with id A, to the end. |
| The trip starts at (0, -400) and goes north on route A. The automatic selection. | At the first point, the selection is `SELECTION_NOT_FOUND`. At the end, the reference id is A. |

Also run `RouteAgreementTest`. The case `aShortDetourOnALongRouteAgrees` must still pass. Its detour is shorter than 500 m.

**Documents.** In section 5.5 of `doc/PLAN.md`, change items 7 to 9: the trip searches again after 10 off-route limits with no comparison. Add the third rule of `RouteAgreement`. Add one sentence to `README.md`.

**Done when:** `test/run.sh` passes. Ask the maintainer to run a replay of a trip that leaves a route and joins a different saved route.

---

### Task 12. Compare with a track of a different profile

**The goal.** The user can record a route with the wrong profile, for example a walk as cycling. That track is then never a reference track for a walk. After this task:

- The manual selection shows the tracks of all profiles.
- The automatic selection also uses a track of a different profile if its speed fits the profile of the trip.
- The menu of a track can change its profile.

The comparison always uses the profile of the trip for its limits. Only the reference times come from the track.

**Part A: the manual selection.** File: `app/src/main/java/net/basov/retrace/ReferenceDialog.java`.

1. In `show()`, make two lists. The first holds each readable track of the trip profile. The second holds each readable track of a different profile that is not null. Keep the newest-first sequence in each list. Give the dialog the first list, then the second list.
2. In `showList()`, show a track of a different profile with its profile. Add the string `select_item_profile` to `res/values/strings.xml`: `%1$s (%2$s, %3$s)`. The third value is `Labels.profile(activity, e.profile)`.
3. Change the banner comment: the tracks of the trip profile come first, then the tracks of the other profiles.

**Part B: the automatic selection.** Files in `app/src/main/java/net/basov/retrace/core/`.

1. In `Profile`, add three constants and a method:

   ```java
   // The limits of the mean speed between two profiles, in m/s. A track
   // with a wrong profile gets its real profile from its mean speed.
   public static final double WALK_RUN_MPS = 2.2;
   public static final double RUN_CYCLE_MPS = 4.5;
   public static final double CYCLE_DRIVE_MPS = 10;

   // The profile that fits a mean speed, or null for NaN.
   public static Profile ofMeanSpeed(double mps) {
       if (Double.isNaN(mps)) {
           return null;
       }
       if (mps < WALK_RUN_MPS) {
           return WALKING;
       }
       if (mps < RUN_CYCLE_MPS) {
           return RUNNING;
       }
       return mps < CYCLE_DRIVE_MPS ? CYCLING : DRIVING;
   }
   ```

2. In `ReferenceSelector`, add this method:

   ```java
   // True if the track can be a reference track for a trip of the profile.
   // A track of a different profile fits if its mean speed fits the profile
   // of the trip. An example is a walk that the user recorded as cycling.
   public static boolean fitsProfile(Track track, Profile profile) {
       if (track.profile == profile) {
           return true;
       }
       return track.profile != null
               && Profile.ofMeanSpeed(TrackSpeeds.of(track, track.profile).meanMps) == profile;
   }
   ```

3. In `routeNear()`, replace `track.profile != profile` with `!fitsProfile(track, profile)`. Keep the check of the count of points. Call `fitsProfile` after the distance check, because `TrackSpeeds` costs more time than the distance.
4. In `best()`, add a first tie rule: a candidate of the trip profile wins over a candidate of a different profile. The class must keep the profile of the trip. If it has no field for it, add `private final Profile profile;` and set it in the constructor.
5. Update the banner comment: the candidates, and the new first tie rule.

**Part C: change the profile of a track.**

1. `TrackStore`: add `void changeProfile(File file, Profile profile) throws IOException`. Under the lock, read the track, call `withProfile(profile)` and write it with `writeGpx`. If task 4 is done, `writeGpx` also removes the entry of the cache. Do not change the file name. A way keeps the file names of its members, thus a new name breaks the way. The profile in the file name is then only a name.
2. `TrackActions`: add the item `CHANGE_PROFILE = 8` after `RENAME`. Show it if the entry is readable and is not a way. Its label is the new string `change_profile`: "Change the profile".
3. The item opens a dialog with the four labels of `Labels.profile`, with `setSingleChoiceItems`. The current profile is checked. A push on a different profile calls `runFileTask` with `store.changeProfile`, then closes the dialog.
4. Add the code `PROFILE_CHANGED = 5` to the codes of `TrackActions`. Find each implementation of `TrackActions.Listener`. Handle `PROFILE_CHANGED` the same as `RENAMED`.
5. Read `WayDialogs` and `WayCandidates`. A track of a different profile must not go into a way, the same as now. Do not change that rule.

**Tests.**

- `ProfileTest` or a new test class: the values 1.4, 2.5, 6 and 15 m/s give the four profiles. NaN gives null.
- `ReferenceSelectorTest`: a walking trip on the north route. The only saved track is the same route at 1.4 m/s, but with the profile `CYCLING`. The selector selects it.
- `ReferenceSelectorTest`: the same trip, and a saved track at 5 m/s with the profile `CYCLING`. It is not a candidate.
- `ReferenceSelectorTest`: the same trip, a walking track and a track at 1.4 m/s with the profile `CYCLING`, on the same route. The walking track wins.
- The existing test `onlyNearTracksOfTheProfileAreCandidates` must still pass. Its cycling track goes at 5 m/s, thus it is still not a candidate.
- `TrackStore` has no JVM test. Ask the maintainer to change the profile of one track on a device, and then to open the track again.

**Documents.** Change item 1 of section 5.5 of `doc/PLAN.md`, and the manual selection of the same section. Add the new menu item to section 5.7. Add one sentence to `README.md`.

**Done when:** `test/run.sh` passes with the new cases.

**Risk.** The speed limits overlap with real trips: a slow cyclist can go at 4 m/s. That only matters for a track of a different profile. The tie rule and `RouteAgreement` protect the comparison.

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
