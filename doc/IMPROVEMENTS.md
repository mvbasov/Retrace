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
| `TrackStore`, `TrackProvider` | Each line | Patch 61 added a cache. The provider is safe: it gives only files of the track directory. |
| `ReferenceSelector`, `RouteAgreement` | Each line, and runs with many seeds | One fault. Patch 54 corrected it. |
| Activities and dialogs | The threads and the life cycle | No fault. Each background result checks that the activity is alive. |
| `GpxReader`, `GpxWriter`, `PointLog` | The formats and the errors | No fault. Patch 60 added the accuracy. A GPX file goes to a temporary file first, thus a stop of the process cannot break it. |
| `TrackMatcher`, `WayBuilder`, `MapView`, the tile classes | The structure only | Not examined in depth. See section 6. |

---

## 3. The tasks in one table

| Task | Type | Size | Section |
| --- | --- | --- | --- |
| 7. Do not count the pause in the comparison | Feature | Medium | 5 |
| 11. Search for a different route after the trip leaves the route | Feature | Medium | 5 |

The maintainer declined task 10, a Russian translation. Do not do it.

### 3.1 The sequence

Do the tasks in this sequence: 7, 11.

- After patch 58, each push compiles the Android classes. Thus a fault in an Android class shows in the test workflow of GitHub. Look at its result after each push.
- Task 7 and task 11 change `TripComparison`. Add their tests to `TripComparisonTest`.

---

## 4. Tasks that need no decision

Patches 54 to 64 did each task of this section.

---

## 5. Tasks that the maintainer approved

The maintainer approved these tasks on 2026-11-14. Do not ask again. The maintainer asked for task 11 on the same day.

### Task 7. Do not count the pause in the comparison

**The goal.** After Stop and Continue, the difference to the reference track does not count the time of the pause. The saved points, the log file and the GPX file keep the real time. The elapsed time on the trip screen and in the notification stays the clock time.

It changes `TripComparison`, which patch 63 made.

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

It changes `TripComparison`, which patch 63 made.

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
