# Retrace improvement plan

This document is the result of a review of the code at version 26.11.2. It lists the faults and the weak points that the review found, and it gives one task for each. A person or an AI model with less experience can do each task from its text alone.

This document uses ASD-STE100 Simplified Technical English, the same as `CLAUDE.md`.

---

## 1. How to use this plan

1. Read `CLAUDE.md` and `doc/TERMINOLOGY.md` first. Each rule there applies to each task here.
2. Do one task in one patch.
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

## 3. The state of the tasks

No task stays. Patches 54 to 66 did each task of sections 4 and 5.

The maintainer declined task 10, a Russian translation. Do not do it.

After patch 58, each push compiles the Android classes. Thus a fault in an Android class shows in the test workflow of GitHub. Look at its result after each push.

---

## 4. Tasks that need no decision

Patches 54 to 64 did each task of this section.

---

## 5. Tasks that the maintainer approved

The maintainer approved these tasks on 2026-11-14. Patches 58 to 66 did each task of this section.

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
