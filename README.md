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

## Release

A push of a tag `vYY.MM.S` starts the release workflow on GitHub. The workflow builds the signed APK and makes a GitHub release with it. The tag must agree with `appVersion` in `app/build.gradle`.

The workflow signs the APK with the keystore of your local build. Android installs an update only when the same key signs it. Thus an APK from GitHub installs over an APK from `local/build.sh`, and the reverse.

### Set up the release environment

Do these steps one time. The secrets go to the environment `release`, not to the repository. The rules of the environment let only a tag `v*` use them.

1. On GitHub, open **Settings → Environments → New environment**. Type the name `release`.
2. In **Deployment branches and tags**, select **Selected branches and tags**. Add a rule of the type **Tag** with the pattern `v*`.
3. Optional: in **Required reviewers**, add yourself. Then each release waits for your approval.
4. On your computer, go to the directory of the repository. Send the keystore and the passwords with the GitHub CLI (`gh`):

   ```
   base64 -w0 app/retrace.keystore | gh secret set KEYSTORE_BASE64 --env release --repo mvbasov/Retrace
   gh secret set --env release --repo mvbasov/Retrace -f .env
   ```

   The CLI sends the values directly. Thus the key does not go through the clipboard or a web form. On macOS, use `base64 -i app/retrace.keystore`. Without `.env`, use `-f .env.example`.
5. Make sure that the environment has the secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`. Remove the same secrets from the repository secrets, if they are there.
6. Optional: protect the release tags. Open **Settings → Rules → Rulesets → New ruleset → New tag ruleset**. Add the target pattern `v*`, and select **Restrict creations**, **Restrict updates** and **Restrict deletions**. Add **Repository admin** to the bypass list. Then only you can make a release tag.

Keep a copy of `app/retrace.keystore` outside the repository. GitHub does not show a secret again. Without the keystore, no update installs over the old APK.

### Change the default passwords

Without `.env`, the passwords are the public values of `.env.example`. Then the keystore file is the only protection of the key. You can change the password and keep the key. The signature stays the same.

1. Change the password. The keystore is a PKCS12 keystore, thus the key has the password of the keystore:

   ```
   docker run --rm -it --user "$(id -u):$(id -g)" -e HOME=/tmp -v "$PWD/app:/out" retrace-base:latest keytool -storepasswd -keystore /out/retrace.keystore
   ```

2. Write the new password into `.env`, for `KEYSTORE_PASSWORD` and for `KEY_PASSWORD`.
3. Run `docker builder prune`. A change of the keystore alone does not change the cache key of the build step.
4. Send the keystore and the passwords again with the commands of step 4 above.

### Make a release

Push the tag of the current version:

```
git tag v26.10.25
git push origin v26.10.25
```

### Check the signature

Compare the certificate of a local APK with the certificate of the release APK:

```
docker run --rm -v "$PWD:/w" retrace-base:latest /opt/android/build-tools/36.0.0/apksigner verify --print-certs /w/output-binaries/retrace-v26.10.25-release.apk
```

The line `Signer #1 certificate SHA-256 digest` must be the same for the two files.

## Check the map tile server

1. Start Retrace.
2. Open the menu and push **Check the map tile server**.
3. Read the result. It shows the HTTP status, the size of the tile, the time and the name of the root certificate.

On Android 6, the result shows if the device trusts the certificate of the tile server. The result decides if the map needs its own trust store. See section 7 of [doc/PLAN.md](doc/PLAN.md).

## License

MIT. See [LICENSE](LICENSE).

Map data © OpenStreetMap contributors.
