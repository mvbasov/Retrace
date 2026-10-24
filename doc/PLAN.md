# Retrace plan

Retrace is an Android application. It records a trip as a GPS track. On a later trip, it compares the current track with a saved reference track in real time.

- Application name: Retrace
- Package: `net.basov.retrace`
- Version form: `YY.MM.S`. See section 4 of `CLAUDE.md`.

## 1. Requirements

- Record a track from the system GPS.
- Compare the current track with a reference track. Show these values:
  - if you are on route or off route
  - if you are ahead or behind, in time and in distance
  - if you move faster or slower than on the reference track
  - when you will reach the finish, and when you reach it
- Profiles for walking, running, cycling and driving.
- Retrace selects the reference track automatically. You can also select it yourself.
- A map from OpenStreetMap tiles, with a tile cache and several zoom levels.
- A Docker build, the same as OMN-Go: Gradle in Docker, with no Android Studio on the host.
- A small APK. No AndroidX, no AppCompat and no other library.
- Android 6.0 (API 23) and newer.

## 2. Tech stack

| Item | Choice |
|---|---|
| Language | Java with the Android framework API only |
| UI | `android.app.Activity` with `android:Theme.Material` (API 21 and newer) |
| Permissions | `Activity.requestPermissions()` (API 23 and newer) |
| minSdk, targetSdk and compileSdk | 23, 34 and 36 |
| Java language level | 17 |
| Location | `LocationManager.GPS_PROVIDER` in a foreground service |
| Map | A Canvas view of its own, with its own tile loader and tile cache |
| Network | `HttpURLConnection` |
| Storage | GPX files. `XmlPullParser` reads them. |
| Native code | None. Thus one APK works on each ABI. |
| APK size target | Less than 150 KB with R8 and resource shrinking |

`targetSdk` 35 and higher force the edge-to-edge layout. Retrace changes `targetSdk` after the screens handle the window insets.

Newer APIs go behind a `Build.VERSION.SDK_INT` check. Examples: notification channels (API 26), `POST_NOTIFICATIONS` (API 33) and the `location` type of a foreground service (API 34).

## 3. Docker build

The build follows the pattern of OMN-Go.

- `Dockerfile.base` makes the toolchain image. It holds Debian trixie, OpenJDK 21, the Android command-line tools, SDK platform 36, build-tools 36 and Gradle 9. It holds no Go and no NDK.
- `Dockerfile` runs the test gate. Then it runs `gradle assembleRelease`, and the signing configuration signs the APK.
- `local/build.sh` builds the two images and copies the APK to `./output-binaries/`.
- Gradle uses the Android Gradle plugin 9 and **no dependency**. The release build sets `minifyEnabled true` and `shrinkResources true`.
- Signing: the first build makes a keystore with the passwords from `.env`. The keystore and the passwords go to the build as BuildKit secrets.
- Release: the GitHub workflows follow the workflows of OMN-Go. `.github/workflows/release.yml` builds the signed APK from `Dockerfile.ci` for each push to `master`, each pull request and each tag `v*`. A tag push also makes a GitHub release. `.github/workflows/test.yml` runs the quality gate alone. The signing secrets are repository secrets. `doc/RELEASE.md` gives the setup and the release procedure.
- The tests use no JUnit. `test/run.sh` compiles and runs them with `javac` and `java` in the same image.

## 4. Project layout

```
Dockerfile.base
Dockerfile
local/build.sh
settings.gradle
build.gradle
app/build.gradle
app/proguard-rules.pro
app/src/main/AndroidManifest.xml
app/src/main/java/net/basov/retrace/
    core/     TileMath, Track, TrackPoint, GpxReader, GpxWriter,
              TrackMatcher, MatchResult, Profile, ReferenceSelector
    map/      MapView, TileLoader, TileCache
    service/  TrackService
              MainActivity, RunActivity, SettingsActivity, TrackStore
app/src/main/res/
test/java/net/basov/retrace/     tests with a main method
test/resources/                  sample GPX files
test/run.sh
output-binaries/
```

The `core` package imports no Android package. Thus the tests run on the JVM.

## 5. Components

### 5.1 TrackService (recording)

- A foreground service with a notification. It continues to record when the screen is off.
- It asks for a GPS fix each second. The minimum distance between two points comes from the profile.
- It drops a fix with bad accuracy and smooths the speed. The profile table gives the limits.
- It writes each point to the log file during the trip. Thus a crash loses no data.
- The service does not restart after Android stops the process. The next start of MainActivity saves the log file as a track.
- The notification shows the live result, for example "0:42 ahead · 3.2 km to the finish".

### 5.2 TrackStore

- Retrace saves GPX 1.1 files in the media directory, the same as OMN-Go: `/storage/emulated/0/Android/media/net.basov.retrace/tracks/`. This makes a backup easy.
- The GPX `<type>` element holds the profile.
- Export: the share intent (`ACTION_SEND`) sends the GPX file. Android 7 and newer refuse a `file://` URI, and AndroidX `FileProvider` is a library. Thus `TrackProvider` gives read access to the saved tracks. It is not exported. The share intent gives the permission for one URI.
- Import: the menu opens the file picker of Android (`ACTION_OPEN_DOCUMENT`). `TrackImport` keeps each point with a time later than the point before it. A track with fewer than two such points is refused, because a reference track needs a time for each point.
- A GPX file with no Retrace profile gets a guess from the median speed of its moving parts. The user confirms the profile. The limits of the guess:
  - up to 8 km/h: walking
  - up to 16 km/h: running
  - up to 40 km/h: cycling
  - above 40 km/h: driving
- A file larger than 20 MB is refused.
- A saved track with the same start time, profile and count of points is the same track. Retrace does not import it again.
- An `ACTION_VIEW` filter for `.gpx` files is not added yet. Many file managers send a GPX file as `application/octet-stream`. A filter for that type puts Retrace into the list for each unknown file.
- At the end of a trip, you save the current track as a new track. If the trip was faster, you can also replace the reference track.

### 5.3 Profiles

| Profile | Accuracy limit | Minimum distance | Off route above | Speed smoothing | Start and finish radius | Maximum speed |
|---|---|---|---|---|---|---|
| Walking | 20 m | 2 m | 25 m | 15 s | 15 m | 3 m/s |
| Running | 25 m | 3 m | 30 m | 10 s | 20 m | 7 m/s |
| Cycling | 30 m | 5 m | 40 m | 8 s | 25 m | 20 m/s |
| Driving | 50 m | 10 m | 60 m | 5 s | 40 m | 50 m/s |

These values are the defaults. You can change them in Settings.

TrackMatcher adds twice the accuracy of each fix to the off-route limit and to the radius. The reference track and the current track both have GPS noise. A fixed limit then gives a false off-route state in a city with poor GPS.

### 5.4 TrackMatcher

TrackMatcher is the core of Retrace. It is plain Java with no Android import. Thus the tests run it on the JVM. The banner comment of `TrackMatcher.java` gives the reason for each rule. [MATCHING.md](MATCHING.md) gives the math of each step, with figures and worked examples.

Preparation of the reference track:

- Smooth the track. Each point becomes the mean of the points within 10 m along the track. GPS noise gives a recorded track a small zigzag, and the zigzag makes the length too large.
- Calculate the distance along the route `S[i]` and the time `T[i]` of each point.
- The reference track starts where it leaves its start circle. Its finish place is where it enters its finish circle.

The start of the trip:

- At the start of the route, the trip starts when it leaves the start circle. A wait at the start does not count.
- Away from the start, the trip joins the route after 3 fixes near the route.
- In both cases, the elapsed time starts with the time of the reference track at that place. Thus `Δt` starts at zero.

Steps for each GPS fix, with the current position P and the elapsed time t:

1. **Project.** Project P onto the segments of the reference track in a window ahead of the last place. The result has two values:
   - the cross-track distance
   - the distance along the route, `s`
2. **Select the leg.** On a route out and back, or on a loop that crosses itself, two legs of the route can be equally near P. Each leg gives one local minimum of the distance. The search takes the minimum with the lowest cost: the distance, plus 0.25 for each meter forward, plus 1.0 for each meter back. A leg that goes against the direction of movement is not a candidate.
3. **On route or off route.** You are off route after 3 fixes above the limit. Off the route, the search looks at the rest of the route, then at the whole route. You are on route again after 2 fixes inside the limit.
4. **Time difference.** `Δt = t − T_ref(s)`. A negative value means ahead. A positive value means behind.
5. **Distance difference.** `Δs = s − S_ref(t)`. This is your distance from the ghost, for example "120 m ahead of the ghost".
6. **Speed.** Compare your speed along the route with the speed of the reference track at `s`. Both use the smoothing window of the profile.
7. **Time of arrival.** `t + (T_total − T_ref(s)) × (t / T_ref(s))`. The pace ratio applies after 30 s of the reference track.
8. **Finish.** The trip finishes inside the finish circle after the finish place of the reference track. It also finishes when `s` is within the circle size from the end. Retrace then vibrates, speaks an optional message and shows the result, for example "1:12 faster than the reference track".

The precision of `Δt` has a limit. The GPS drift of two trips is different, and it stays for minutes. Thus the two tracks differ by about σ × √2 along the route. At walking speed, that is about 4 s for σ = 4 m and 8 s for σ = 8 m. The error is smaller at a higher speed.

### 5.5 ReferenceSelector

Automatic selection:

1. At the first point of a trip, TrackService reads the saved tracks one at a time. It keeps each track of the same profile with its route inside three start circles of the point. Each such track is a candidate in its own direction and in the opposite direction.
2. Each point of the trip goes to one TrackMatcher for each candidate. A matcher joins the route at the start or in the middle. It does not join against the direction of movement.
3. The progress of a candidate is the distance along its route from the start or the join.
4. The temporary reference track is the valid candidate with the lowest mean cross-track distance. Before the first valid candidate, it is the candidate with the nearest route. A valid candidate is on the route, and its progress is at least half the decision distance.
5. The decision distance is 10 % of the shortest candidate, between 50 m and 300 m. When the progress of one candidate on the route reaches it, Retrace selects the temporary reference track. Its matcher has all the points, thus the comparison continues with no gap.
6. Two tracks of the same route have almost the same score. Thus candidates within 3 m of the best mean cross-track distance count as equal. From them:
   - A way wins, because it is the typical trip on the route.
   - Then the same direction wins over the opposite direction.
   - Then the newest track wins, and the trip races the last trip on that route.
7. If no candidate is valid after three decision distances, Retrace records a new route with no comparison.

Limit: two routes with the same first part. The selection can come before the routes go apart. Then the newest track wins, also if the trip later follows the other route. A manual selection corrects this.

Manual selection:

- The menu of the track screen starts a trip with that track as the reference track, in its direction or in the opposite direction.
- During the trip, the menu of the trip screen selects a track of the same profile, the automatic selection or no comparison. A second dialog asks for the direction of the track.
- The time in the opposite direction comes from `Track.reversed()`. It is correct only on a flat route. The menu item, the dialog and the trip screen tell this.
- After a change, TrackService gives all the points of the trip to the new matcher. A finish in these points gives no vibration.
- A manual selection always replaces the automatic selection for that trip.

### 5.6 Map

- A `MapView` of its own, with the Web Mercator projection. It draws the tiles, the reference track, the current track, your position and the ghost. `MapViewport` in `core` does the math, thus the tests run it on the JVM.
- Zoom: two-finger zoom, double tap and the + and − buttons. Zoom levels 3 to 19. Between two levels, the map scales the tiles of the nearest level.
- A screen with a high density shows each tile larger than 256 pixels. Otherwise the text of the map is too small to read.
- Two modes: the map follows your position, or you move the map yourself. A button shows the whole track.
- With no network: if the tile cache does not hold a tile, the map enlarges a part of a lower tile. It looks up to 4 zoom levels down.
- Tile cache:
  - memory: `android.util.LruCache` for the bitmaps, one eighth of the memory of the application
  - disk: one file for each tile in the cache directory, `tiles/z/x/y.tile`. The first 8 bytes hold the end time of the tile, and the PNG data follows.
  - size limit: 100 MB. Above the limit, Retrace removes the oldest tiles down to 90 % of the limit. A later milestone moves the limit to Settings.
  - A tile after its end time stays on the map until a new download replaces it.
- Loading: a thread pool with two connections. The newest request comes first. A task for a tile that is no longer on the screen stops before its download. After a failed download, Retrace waits 30 s before it tries that tile again.
- A push on a track in the list opens the track screen. It shows the track on the map. A green arrow marks the start and points in the direction of travel. A red square marks the finish.
  - The direction of the arrow comes from the first point at least 16 dp from the start on the screen. The points of a wait at the start do not turn the arrow.
  - The bottom of the track screen has the **Replay** button and the **Menu** button. The menu has the trip items, the way item, and share, rename and delete.
  - A long push on a track in the list shows a short menu: share, rename and delete.
- You can change the tile URL in Settings. Thus you can use a different tile server.

The OpenStreetMap tile usage policy for `tile.openstreetmap.org` has these rules. Retrace obeys each rule.

- Send a User-Agent that names the application.
- Obey the cache headers. Keep each tile for at least 7 days.
- Do not download an area in advance.
- Show "© OpenStreetMap contributors" on the map.

### 5.7 User interface

- **MainActivity**: the list of tracks, with name, profile, distance, time and date. Two tabs show the saved tracks or the ways. The tabs show the count of each, and the preference keeps the selected tab. Actions: record a new track, select the reference track, rename, delete, share and import.
  - The button **Start GPS** starts the GPS receiver before a trip. Then the trip starts with a good fix. The row shows the state of the receiver: the location is off, no fix yet, or the accuracy. From Android 7, it also shows the satellites in use and in view.
  - `GpsReadiness` compares the accuracy with the limit of the selected profile. The rule agrees with `RecordFilter`.
  - The receiver stops after a push on **Stop GPS**, at the start of a trip, when the list screen closes, and after 10 minutes. It uses much power.
- **RunActivity**, the trip screen: the map and a panel with large numbers. The panel shows Δt, Δs, and on route or off route. It also shows the speed compared with the reference track, the distance to the finish and the time to the finish.
  - The large Δt is smoothed by `DeltaSmoother`, with a time constant of two times the speed smoothing window of the profile. At the finish, the panel shows the exact result.
  - The text is green while you are ahead, and red while you are behind or off route.
  - The screen stays on while the trip screen is visible.
  - The map follows your position. A move of the map stops that. The button **Me** starts it again.
  - At the start of a trip, the map shows the reference track. At the first point, the map follows your position, as after a push on **Me**. It zooms in to at least level 16.
  - At the start of a replay, the map shows the whole replayed track and the reference track, as after a push on **All**.
  - After a rotation of the screen, the camera of the user stays.
  - A scale bar at the bottom left shows a round length: 1, 2 or 5 times a power of 10, in meters. `ScaleBar` calculates it for the latitude of the center of the map.
  - The menu of the track screen starts a trip on the route of the track. The trip starts on the list screen, because that screen asks for the location permission and checks the GPS. A push on the notification, or on the status line of the list, opens the trip screen.
  - At the finish, the device vibrates. The recording continues until you push Stop.
  - A line above the panel shows the reference track and how the trip got it: temporary, selected, no saved track, or no comparison. The menu changes the reference track during the trip.
- **SettingsActivity**: the vibration at the finish, the color theme, and the addition of a finished trip to its way.
  - The color theme is light, dark, or the same as the system. Android has the night mode of the system from Android 10. On an older device, the same as the system is light.
  - `ThemedActivity` gives the light and the dark theme. It overrides the night mode of the configuration of each screen. Thus the theme and the colors of `values-night/` follow the setting.
  - The map tiles stay light in the dark theme.
  - Each menu of a track is a dialog. From Android 7, a context menu is a popup at the place of the push. Some systems draw that popup with the night mode of the system, not with the theme of Retrace.
  - The themes set `android:forceDarkAllowed` to false on Android 10 and newer. Retrace has its own dark theme. A forced dark look on a light theme can give dark text on a dark background.
  - Each button has round corners. The themes set `android:buttonStyle` to the style `RoundButton`. Its drawable takes the colors from the theme, thus one drawable serves the two themes. The buttons of a dialog have no background and stay as they are.
  - Later: the limits of each profile, the tile URL, the size of the tile cache and the voice messages.
- Alerts: vibration and optional `TextToSpeech` messages. Android has `TextToSpeech` in the framework.

### 5.8 Ways

A way is the mean track of some tracks on one route. It has the mean position and the mean elapsed time of its tracks at each place. The GPS errors of different trips are not related. Thus a way is nearer to the real route than one track, and its time is a typical trip on the route.

- `WayBuilder` makes the way. The banner comment of `WayBuilder.java` gives each step and the reason for it.
- A way is a GPX file in the track directory. The `<metadata>` element holds an extension with the file names of its tracks. Other applications ignore the extension and read a way as a plain track.
- A way works as a track: on the map, as a reference track, in a replay and in a share intent.
- The track screen of a way can also show its tracks. A check box shows or hides them. They are hidden at first, because the line of the way is the main content. Each track has its own color on the map, below the line of the way. A list below the values names the tracks with the same colors. A push on a name opens the track screen of that track. A track that the user removed shows "not found".
- A track can join the route away from its start, after a GPS error or a start in the middle. It gives its place and its time for the part that it covers.
- A track in the opposite direction is turned around. Its time is correct only on a flat route. Uphill and downhill, the speed in the two directions is different.
- A step faster than two times the top speed of the profile, in the first or the last 10 points, is a GPS jump. The points before or after the jump are not used.
- The way keeps the longest part of the route that two tracks or more cover. A part that only one track covers can be a GPS error.
- The way does not use these tracks:
  - a track that never joins the route
  - a track that covers less than half of the route
  - a track with more than 10 % of its part off the route
- The time of the way starts at the start of its newest track.
- The menu of a track has **Make a way from this route**. The dialog shows the tracks of the same route with a checkbox each. Such a track has the same profile, and `WayBuilder.match()` accepts it. It covers at least half of the route, in one of the two directions. The dialog marks a track in the opposite direction.
- After the build, a message tells the count of the tracks in the opposite direction. The times of the way are then correct only on a flat route.
- The menu of a way has **Change the tracks of the way**. Retrace then builds the way again in its file. Its dialog always shows the members of the way, checked. The way keeps their file names, thus they need no match against the way. The other tracks of the profile need the match. `WayCandidates` makes the list.
- A way is never a member of a way. The removal of a member track does not change the way.
- A trip with a way as the reference track can join the way after the finish. The setting **Add a finished trip to its way** has three values:
  - **Ask each time**: after the stop, the visible screen asks. This is the default.
  - **Automatically**: TrackService builds the way again when it saves the track.
  - **Never**: the way does not change.
- The trip must reach the finish. If `WayBuilder` does not use the new track, the way stays as it was.

Results of 100 seeds of five invented walks with a mean speed of 1.4 m/s. One walk waits two minutes at the start:

| GPS noise | Error of the way / error of one track | Error of the time of the way (rms) |
| --- | --- | --- |
| 4 m | 0.44 to 0.51 | 3 s |
| 8 m | 0.44 to 0.51 | 6 s |

The ratio 0.44 agrees with 1 / √5 for five tracks.

The same runs had a sixth track at a noise of 4 m, in three cases:
- a GPS jump at the start
- the opposite direction
- a start in the middle of the route

In each case, each of the 100 seeds gave a way with all six tracks and a correct time. Two tracks in opposite directions on one street gave the street alone. One of them had a GPS jump at the start, and the other had a tail at the end.

## 6. Permissions

- `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION`
- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_LOCATION`
- `POST_NOTIFICATIONS`
- `INTERNET` and `ACCESS_NETWORK_STATE`
- `VIBRATE`

Retrace does not need `ACCESS_BACKGROUND_LOCATION`. The service starts while the activity is on the screen.

## 7. Risks to check early

- **HTTPS to the tile server on Android 6.** Android 6 does not hold the ISRG Root X1 certificate. If the tile server uses a Let's Encrypt certificate, the connection fails. Android adds `network_security_config` at API 24 only. On API 23, the solution is a `TrustManager` of its own with the root certificate in the APK, for the tile host only. The first version has a test screen for this risk. It shows the root certificate of the tile server.
- **Battery settings of some device makers**, for example Xiaomi and Huawei, can stop the service. Retrace shows a hint with a link to the battery settings.
- **Gradle in Docker needs network access** on the first build. It downloads the Android Gradle plugin. Later builds use the cache.

## 8. Testing

- JVM tests for TrackMatcher and ReferenceSelector. The tests use only invented tracks. A real track holds the precise positions of a person, and the repository is public.
- `test/java/net/basov/retrace/core/TestTracks.java` makes the invented tracks. It moves along a route of corners at a fixed speed, adds GPS-like noise and sends the points through RecordFilter. The noise is a slow drift with a size of σ and a correlation time of 3 minutes, plus a small fast part.
- Each test compares two trips with different seeds. The tests cover these cases:
  - a straight line, a route with corners, and a route for driving
  - a detour off the route
  - a route out and back, a square loop, and a figure eight
  - a trip that joins the route away from the start
  - a wait at the start, and a GPS gap
  - a faster trip, a slower trip, and a route with no times
- Replay mode: the service takes the points of a saved track and not the fixes of the GPS. Thus you can test the full user interface at your desk.
  - The **Replay** button of the track screen starts a replay of that track. A dialog first selects the reference track: the automatic selection, no comparison, or a saved track of the same profile.
  - After the stop of a replay, the **Replay** button of the trip screen starts the same replay again. It uses the last selection of the reference track and the last speed. After the automatic selection, the selection runs again.
  - The automatic selection does not use the replayed track. A replay with the replayed track as the reference track gives a time difference near zero. This is a quick check of the matcher.
  - Each point keeps its own time. Only the wait on the screen is shorter. The speeds are 1×, 5×, 10×, 30× and 100×, and the menu of the trip screen changes the speed. No wait is longer than 2 seconds.
  - A replay uses no GPS, writes no log file and saves no track. It does not use the foreground state. Thus Android can stop it soon after you leave Retrace.
  - A saved track has no accuracy for its points. TrackMatcher then uses an accuracy of 0, and its limits are smaller than on the real trip.

## 9. Milestones

1. **Skeleton.** The Docker build, a test screen and an APK that installs on Android 6. Check the APK size and the HTTPS connection to the tile server.
2. **Recording.** TrackService, the GPX file, the list of tracks and the profiles.
3. **Matcher.** TrackMatcher and its tests.
4. **Map.** MapView, the tile loader, the tile cache, the zoom levels and the tracks on the map.
5. **Trip screen.** The panel with the comparison, the notification, the finish and the alerts.
6. **Reference selection.** Automatic selection and manual selection.
7. **Last work.** Import and export, replay mode, Settings and the battery hint.
