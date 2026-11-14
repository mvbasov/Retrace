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
| 11. Search for a different route after the trip leaves the route | Feature | Medium | 5 |

The maintainer declined task 10, a Russian translation. Do not do it.

### 3.1 The sequence

Do the task that stays: 11.

- After patch 58, each push compiles the Android classes. Thus a fault in an Android class shows in the test workflow of GitHub. Look at its result after each push.
- Task 11 changes `TripComparison`. Add its tests to `TripComparisonTest`.

---

## 4. Tasks that need no decision

Patches 54 to 64 did each task of this section.

---

## 5. Tasks that the maintainer approved

The maintainer approved these tasks on 2026-11-14. Do not ask again. The maintainer asked for task 11 on the same day.

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

`add()` makes a shifted point for the pauses. Use the original point `fix` for the distance. The time has no effect on the distance.

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

Add one more case for the pauses. The automatic comparison with route A stops, and the trip pauses for 600 s. Then it joins route B. The difference at the finish of B does not count the pause. `reset()` must not change the count of points in `TripComparison`. See the comment of the field `added`.

Also run `RouteAgreementTest`. The case `aShortDetourOnALongRouteAgrees` must still pass. Its detour is shorter than 500 m.

**Documents.** In section 5.5 of `doc/PLAN.md`, change items 7 to 9: the trip searches again after 10 off-route limits with no comparison. Add the third rule of `RouteAgreement`. Add one sentence to `README.md`.

**Done when:** `test/run.sh` passes. Ask the maintainer to run a replay of a trip that leaves a route and joins a different saved route.

---

## 6. Known limits with no task

These points are correct behavior for now. Do not change them without a request.

- **An out-and-back route on one line.** The two directions are the same line at the start. The selection takes the opposite direction in about one trip of ten. The route and the distance are then correct, and only the times of the reference track differ.
- **A reference track with two or more track segments.** `TrackMatcher` and `WayBuilder` use all points as one line. The step between two track segments is a straight line with the time of the pause.
- **A waypoint with the GPS off.** During the pause with GPS off, a new waypoint goes to the last known position.
- **The elapsed time of the notification and of the trip screen** continues during the pause. Only the comparison does not count the pause.
- **A replay of a track with a pause** counts the time of the pause. The saved track has no record of the pause. The time between two track segments has the pause and a normal step, and the replay cannot tell them apart.
- **A movement during the pause.** The comparison removes the time of the pause, not its distance. If the user travels far during the pause, the matcher sees a large step in a short time.

These parts did not get a review in depth. A later review can start here:

- `WayBuilder` has more than 1100 lines in one file. Its tests are good, but a change there is a risk.
- `TrackMatcher`: for a loop, the matcher of the opposite direction found the route again after an off-route state, against the direction of the movement. Patch 54 corrected the effect in the selection only. A check of the direction rule at that place is a possible next step.
- `MapView` and the tile classes.
