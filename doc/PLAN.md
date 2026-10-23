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
| minSdk and targetSdk | 23 and 34 |
| Location | `LocationManager.GPS_PROVIDER` in a foreground service |
| Map | A Canvas view of its own, with its own tile loader and tile cache |
| Network | `HttpURLConnection` |
| Storage | GPX files. `XmlPullParser` reads them. |
| Native code | None. Thus one APK works on each ABI. |
| APK size target | Less than 150 KB with R8 and resource shrinking |

Newer APIs go behind a `Build.VERSION.SDK_INT` check. Examples: notification channels (API 26), `POST_NOTIFICATIONS` (API 33) and the `location` type of a foreground service (API 34).

## 3. Docker build

The build follows the pattern of OMN-Go.

- `Dockerfile.base` makes the toolchain image. It holds Debian, OpenJDK 17, the Android command-line tools, one SDK platform, one build-tools package and Gradle. It holds no Go and no NDK.
- `Dockerfile` runs the test gate. Then it runs `gradle assembleRelease`, and the signing configuration signs the APK.
- `local/build.sh` builds the two images and copies the APK to `./output-binaries/`.
- Gradle uses the Android Gradle plugin and **no dependency**. The release build sets `minifyEnabled true` and `shrinkResources true`.
- Signing: the first build makes a keystore with the passwords from `.env`. A CI build can mount a keystore and give the passwords in environment variables.
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
- It writes each point to a temporary file during the trip. Thus a crash loses no data.
- The notification shows the live result, for example "0:42 ahead · 3.2 km to the finish".

### 5.2 TrackStore

- Retrace saves GPX 1.1 files in the media directory, the same as OMN-Go: `/storage/emulated/0/Android/media/net.basov.retrace/tracks/`. This makes a backup easy.
- The GPX `<type>` element holds the profile.
- Share intents import and export a track (`ACTION_SEND`, and `ACTION_VIEW` for a `.gpx` file).
- At the end of a trip, you save the current track as a new track. If the trip was faster, you can also replace the reference track.

### 5.3 Profiles

| Profile | Accuracy limit | Minimum distance | Off route above | Speed smoothing | Start and finish radius |
|---|---|---|---|---|---|
| Walking | 20 m | 2 m | 25 m | 15 s | 15 m |
| Running | 25 m | 3 m | 30 m | 10 s | 20 m |
| Cycling | 30 m | 5 m | 40 m | 8 s | 25 m |
| Driving | 50 m | 10 m | 60 m | 5 s | 40 m |

These values are the defaults. You can change them in Settings.

### 5.4 TrackMatcher

TrackMatcher is the core of Retrace. It is plain Java with no Android import. Thus the tests run it on the JVM.

Preparation of the reference track:

- Convert each point to local meters. An equirectangular projection near the start is sufficient.
- Calculate the distance along the route `S[i]` and the elapsed time `T[i]` of each point.

Steps for each GPS fix, with the current position P and the elapsed time t:

1. **Filter.** Drop the fix if its accuracy is worse than the limit of the profile.
2. **Project.** Project P onto the segments of the reference track. Search only a window ahead of the last match. This keeps the distance along the route monotonic. It also handles a loop and a route that goes out and back. The result has two values:
   - the cross-track distance
   - the distance along the route, `s`
3. **On route or off route.** You are off route when the cross-track distance is above the limit for N fixes in sequence. While you are off route, a search of the full track finds the route again.
4. **Time difference.** `Δt = t − T_ref(s)`. A negative value means ahead. A positive value means behind.
5. **Distance difference.** `Δs = s − S_ref(t)`. This is your distance from the ghost, for example "120 m ahead of the ghost".
6. **Speed.** Compare your smoothed speed with the speed of the reference track at `s`.
7. **Time of arrival.** `t + (T_total − T_ref(s)) × recent pace ratio`.
8. **Finish.** The trip is at the finish when `s ≥ S_total − radius` and P is inside the radius of the last point. Retrace then vibrates, speaks an optional message and shows the result, for example "1:12 faster than the reference track".

### 5.5 ReferenceSelector

Automatic selection:

1. At the start of a trip, find the saved tracks of the same profile. Keep each track that has its start inside three times the start radius.
2. Show the nearest track as a temporary reference track.
3. After about 300 m, or 10 % of the route if that is shorter, run the matcher on each candidate. Select the track with the lowest mean cross-track distance.
4. If no candidate agrees with the trip, record a new track with no comparison.

Manual selection:

- Select a reference track from the list before the start, or at any time during the trip.
- A manual selection always replaces the automatic selection for that trip.

### 5.6 Map

- A `MapView` of its own, with the Web Mercator projection. It draws the tiles, the reference track, the current track, your position and the ghost.
- Zoom: two-finger zoom, double tap and the + and − buttons. Zoom levels 3 to 19.
- Two modes: the map follows your position, or you move the map yourself. A button shows the full route.
- With no network: if the tile cache does not hold a tile, the map enlarges a tile of a lower zoom level from the tile cache.
- Tile cache:
  - memory: `android.util.LruCache` for the bitmaps
  - disk: one file for each tile in the cache directory, with a size limit and LRU removal (Settings, default 100 MB)
- Loading: a small thread pool with two connections. Retrace stops the request for a tile that is no longer on the screen.
- You can change the tile URL in Settings. Thus you can use a different tile server.

The OpenStreetMap tile usage policy for `tile.openstreetmap.org` has these rules. Retrace obeys each rule.

- Send a User-Agent that names the application.
- Obey the cache headers. Keep each tile for at least 7 days.
- Do not download an area in advance.
- Show "© OpenStreetMap contributors" on the map.

### 5.7 User interface

- **MainActivity**: the list of tracks, with name, profile, distance, time and date. Actions: record a new track, select the reference track, rename, delete, share and import.
- **RunActivity**: the map and a panel with large numbers. The panel shows Δt, Δs, and on route or off route. It also shows the speed compared with the reference track, the distance to the finish and the time of arrival.
- **SettingsActivity**: the limits of each profile, the tile URL, the size of the tile cache and the voice messages.
- Alerts: vibration and optional `TextToSpeech` messages. Android has `TextToSpeech` in the framework.

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

- JVM tests for TrackMatcher and ReferenceSelector, with sample GPX files: a straight line, a loop, a route out and back, a part off route, a faster trip and a slower trip.
- Replay mode in the debug build: the service reads the fixes from a GPX file and not from the GPS, at 1× to 10× speed. Thus you can test the full user interface at your desk.

## 9. Milestones

1. **Skeleton.** The Docker build, a test screen and an APK that installs on Android 6. Check the APK size and the HTTPS connection to the tile server.
2. **Recording.** TrackService, the GPX file, the list of tracks and the profiles.
3. **Matcher.** TrackMatcher and its tests.
4. **Map.** MapView, the tile loader, the tile cache, the zoom levels and the tracks on the map.
5. **Trip screen.** The panel with the comparison, the notification, the finish and the alerts.
6. **Reference selection.** Automatic selection and manual selection.
7. **Last work.** Import and export, replay mode, Settings and the battery hint.
