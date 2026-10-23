# Retrace

Retrace is an Android application. It records a trip as a GPS track. On a later trip, it compares your position with the saved track while you move. It tells you these things:

- if you are on the route or off the route
- if you are ahead of the saved trip or behind it, in time and in distance
- if you move faster or slower than on the saved trip
- when you will reach the finish, and when you reach it

Retrace has profiles for walking, running, cycling and driving. It finds the saved track that agrees with your trip. You can also select the track yourself. The map uses OpenStreetMap tiles.

## Status

Retrace records tracks, shows them on a map, and compares a trip with a saved track. Retrace selects the reference track, or you select it yourself. The plan and the milestones are in [doc/PLAN.md](doc/PLAN.md).

- Select a profile and push **Start**. A notification shows the recording while the screen is off.
- At the first point, Retrace looks for the saved tracks of the profile that start near you. During the first part of the trip, it compares with the best of them as a temporary reference track. After 300 m, or 10 % of the route if that is shorter, it selects the reference track. If no saved track agrees with your trip, Retrace records a new route with no comparison.
- Push **Stop** in the application or in the notification. Retrace saves the track as a GPX file.
- Two tabs above the list show the saved tracks or the ways.
- A push on a track shows it on the map. The map uses OpenStreetMap tiles and keeps them in a cache.
- To start a trip on the route of a track, do a long push on the track. Then push **Start a trip on this route**. That track becomes the reference track.
- To change the reference track during the trip, open the menu of the trip screen and push **Select the reference track**. Select a track, **Automatic selection** or **No comparison**. The comparison then starts again from the start of the trip.
- The trip screen shows if you are ahead or behind, and your distance from the ghost. It also shows your speed, and the distance and time to the finish. At the finish, the device vibrates.
- To check the comparison at your desk, do a long push on a track and push **Replay this track**. Then select the reference track. The trip screen shows the replay at ten times the real speed. Its menu changes the speed and the reference track.
- To make a way, do a long push on a track and push **Make a way from this route**. Select the tracks of the route. A way is the mean of its tracks: the mean line and the mean time at each place. Use a way the same as a track. In the automatic selection, a way wins over the tracks of its route. After a trip that reaches the finish of a way, Retrace can add the trip to the way. The setting **Add a finished trip to its way** tells if Retrace asks, adds it automatically, or never adds it.
- The menu item **Settings** turns the vibration at the finish on or off. It also selects the color theme: light, dark, or the same as the system.
- The menu item **About Retrace** shows the version of the application.
- A long push on a track opens the menu to share, rename or delete it. **Share** sends the GPX file to a different application.
- To import a GPX file from a different application, open the menu and push **Import a GPX file**. The track needs a time for each point. If the file has no Retrace profile, Retrace shows a guess from the speed. Confirm it or select a different profile.
- Retrace keeps the GPX files in `/storage/emulated/0/Android/media/net.basov.retrace/tracks/`. Copy this directory for a backup.

## Constraints

- Android 6.0 (API 23) and newer.
- No AndroidX, no AppCompat, and no other library.
- A small APK.

## Build

Retrace uses a Docker build environment. You do not need Java, Gradle or Android Studio on your computer.

Prerequisite: Docker with BuildKit (`docker buildx`).

1. Optional: copy `.env.example` to `.env`, and set your own keystore passwords.
2. Run `bash local/build.sh`.
3. Get the APK from `./output-binaries/`.

The first build downloads the Android SDK and Gradle. Later builds use the Docker cache.

If `app/retrace.keystore` does not exist, `local/build.sh` makes a new keystore. Keep a copy of this file. Android installs an update only when the same key signs it. Git ignores the keystore.

To run only the tests, use these commands:

```
docker buildx build -f Dockerfile.base -t retrace-base:latest --load .
docker buildx build --target test .
```

## Check the map tile server

1. Start Retrace.
2. Open the menu and push **Check the map tile server**.
3. Read the result. It shows the HTTP status, the size of the tile, the time and the name of the root certificate.

On Android 6, the result shows if the device trusts the certificate of the tile server. The result decides if the map needs its own trust store. See section 7 of [doc/PLAN.md](doc/PLAN.md).

## License

MIT. See [LICENSE](LICENSE).

Map data © OpenStreetMap contributors.
