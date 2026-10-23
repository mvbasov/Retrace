# Retrace

Retrace is an Android application. It records a trip as a GPS track. On a later trip, it compares your position with the saved track while you move. It tells you these things:

- if you are on the route or off the route
- if you are ahead of the saved trip or behind it, in time and in distance
- if you move faster or slower than on the saved trip
- when you will reach the finish, and when you reach it

Retrace has profiles for walking, running, cycling and driving. It finds the saved track that agrees with your trip. You can also select the track yourself. The map uses OpenStreetMap tiles.

## Status

Retrace records tracks now. It does not compare them yet. The plan and the milestones are in [doc/PLAN.md](doc/PLAN.md).

- Select a profile and push **Start**. A notification shows the recording while the screen is off.
- Push **Stop** in the application or in the notification. Retrace saves the track as a GPX file.
- A long push on a track opens the menu to rename or delete it.
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
