# Retrace development rules

These are the standing rules for work on the Retrace repository. Read this document before you change code, tests, documents or build files.

The plan and the milestones are in `doc/PLAN.md`. The controlled vocabulary is in `doc/TERMINOLOGY.md`.

This document uses ASD-STE100 Simplified Technical English. See section 8.

---

## 1. Fixed constraints

Do not remove a constraint without an instruction from the maintainer.

1. **No AndroidX. No AppCompat. No other library.** Use the Android framework API only. The `dependencies` block of `app/build.gradle` stays empty.
2. **The minimum is Android 6.0 (API 23).** Put each call above API 23 behind a `Build.VERSION.SDK_INT` check.
3. **The Java language level is 17.** `app/build.gradle` sets `sourceCompatibility` and `targetCompatibility` to 17. The JDK of the build image can be newer.
4. **Use only the Java library that Android 6 has.** Do not use `java.time`, `java.util.stream`, `java.util.Optional` or `java.util.function`. Android adds them at API 24 or 26. Do not use `String.join`, `List.of`, `Map.of` or `Map.getOrDefault`. Lambdas are permitted, because D8 converts them. `AndroidApiTest` finds the most frequent faults.
5. **Keep the APK small.** Do not add a PNG image. Use a vector drawable. Build a small screen in Java, or use a small layout file.
6. **The package `net.basov.retrace.core` imports no Android package.** It holds the logic that the tests run on the JVM. `test/run.sh` compiles it with plain `javac`. Thus an Android import there stops the tests.
7. **Obey the OpenStreetMap tile usage policy.** Send a User-Agent that names Retrace. Obey the cache headers. Do not download an area in advance. Show the attribution on the map. See section 5.6 of `doc/PLAN.md`.
8. **Keep the storage in the media directory.** Tracks go to `/storage/emulated/0/Android/media/net.basov.retrace/`. This directory needs no storage permission.
9. **Do network work and file work off the UI thread.** A background thread gives the result to the UI with `runOnUiThread` or a `Handler`.

---

## 2. Repository map

| Path | Contents |
| --- | --- |
| `app/build.gradle` | The application build file. It holds `appVersion`, the only copy of the version. |
| `app/src/main/AndroidManifest.xml` | The manifest. |
| `app/src/main/java/net/basov/retrace/` | The Android code: activities, the service and the views. |
| `app/src/main/java/net/basov/retrace/core/` | Plain Java with no Android import: track math, the matcher, the GPX reader and writer. |
| `app/src/main/res/` | Strings, colors, styles and vector drawables. |
| `test/java/` | The Java tests. They sit outside the Gradle project. See section 6. |
| `test/run.sh` | Compiles and runs the tests. |
| `Dockerfile.base` | The toolchain image: JDK, Android SDK and Gradle. |
| `Dockerfile` | The test gate and the APK build. |
| `local/build.sh` | The maintainer build script. The Docker context excludes `local/`. |
| `doc/` | Maintainer documents. The Docker context excludes `doc/`. |
| `CLAUDE.md` | This document. |

The repository does not hold `output-binaries/`, `.env` or the keystore.

---

## 3. Java rules

1. Give each task its own plain class. An activity holds the life cycle and the views. Other work goes into a class beside it.
2. Put a banner comment at the top of each class. Say what the class does and why it exists.
3. A comment says why. The code says what.
4. Do not use reflection. R8 then removes all code that the application does not call.
5. Write user text in `res/values/strings.xml`. Do not write user text in Java.
6. Use `Locale.ROOT` in `String.format` for text that a program reads, for example a URL.

---

## 4. Version and release

- `app/build.gradle` holds the version as `def appVersion = 'YY.MM.S'`.
- YY is the year and MM is the month, with two digits each. S is the sequence number of the change in that month. S starts at 1. It has one, two or three digits, with no leading zero. Examples: `26.10.1`, `26.10.37`, `26.11.100`.
- `app/build.gradle` calculates `versionCode` as `YY*100000 + MM*1000 + S`. Example: `26.10.1` gives `2610001`. Do not write the version code by hand.
- **Bump the version in each commit.** A bump changes one line. The maintainer can ask for an exception.
- **The maintainer makes each tag.** Do not make a tag.
- Do not write a version number in a comment. The number goes out of date. `CommentStyleTest` finds it.

---

## 5. Commits

A commit message has three parts: a subject line, one empty line, and a list of the changes.

1. Subject line: `type(scope): Sentence. vYY.MM.S`. Write at most 80 characters. Count the version in that limit.
2. One empty line.
3. One `-` bullet for each change. Write one change in one bullet. Put a period at the end of each bullet.

```
feat(track): Record a track in a foreground service. v26.10.2

- Add TrackService with GPS updates every second.
- Write each point to a temporary file during the trip.
- Save the track as a GPX file at the finish.
```

- Types: `feat`, `fix`, `build`, `refactor`, `test`, `doc`, `chore`, `release`.
- Scopes: `android`, `build`, `core`, `track`, `match`, `map`, `ui`, `service`, `test`, `doc`.
- Write the subject line and each bullet in Simplified Technical English. Use the terms of `doc/TERMINOLOGY.md`.
- `master` is the working branch.

---

## 6. Tests

- The tests use no JUnit and no Gradle. Each test class has a `main` method and its own `check` helper. A failed check sets the exit code to 1. Thus the project needs no test dependency.
- `test/run.sh` compiles `app/src/main/java/net/basov/retrace/core/` and `test/java/` with `javac --release 17 -Xlint:all -Werror`. Then it runs each class with a name that ends in `Test`.
- The `test` stage of `Dockerfile` runs `test/run.sh`. A failed test stops the build, and no APK comes out. To run the gate alone, use `docker buildx build --target test .`.
- `--build-arg SKIP_TESTS=1` skips the gate. Use it only for an emergency build.
- **Put the logic into `core`, and test it there.** A test that runs the code is better than a test that reads the source text.
- Give each test method a comment that says why the test exists.
- `test/java/net/basov/retrace/repocheck/CommentStyleTest.java` reads each comment of the repository. It checks the rules of section 8. It demands zero faults.
- `test/java/net/basov/retrace/repocheck/AndroidApiTest.java` reads the source of the application. It finds calls of the Java library that Android 6 does not have. See rule 4 of section 1.

---

## 7. Build

- The Docker build is the reference build. The host needs no Java, no Android Studio and no Gradle.
- `Dockerfile.base` makes the toolchain image `retrace-base:latest`. Docker keeps it in the cache.
- The toolchain: Debian trixie, JDK 21, Gradle 9, the Android Gradle plugin 9, SDK platform 36 and build-tools 36.
- The version of the Android Gradle plugin is in `build.gradle`. The version of Gradle is in `Dockerfile.base`. Change the two together.
- The Gradle files use the `name = value` form for each property. Do not use the old `name value` form.
- `app/build.gradle` sets `enableKotlin = false`. Without it, the plugin adds the Kotlin standard library to the APK. Do not use the deprecated `android.builtInKotlin` option.
- `Dockerfile` has two stages. `test` runs the gate. `project_builder` runs `gradle assembleRelease` and copies the APK to `/app/bin/`. It reads the version from `app/build.gradle` for the name of the APK file.
- The build does not use the variant API of the Android Gradle plugin. The plugin removes the old variant API.
- `local/build.sh` builds both images and copies the APK to `output-binaries/`. It keeps the full output of each build in `local/logs/`. Git ignores that directory.
- The toolchain image holds each SDK package that the build needs. A missing package goes into the layer of the build step, thus each build downloads it again. If the log shows "Install Android SDK", add that package to `Dockerfile.base`.
- The first run of `local/build.sh` makes `app/retrace.keystore` with the passwords from `.env`. The keytool of the toolchain image makes it. Without `.env`, the defaults of `.env.example` apply.
- The release build uses R8 (`minifyEnabled = true`) and `shrinkResources = true`.
- **Ask the maintainer before you add a build step, a tool or a dependency.**

---

## 8. Documentation style

Apply the `ste-writing` skill to each word that a person reads. This covers the documents, the user interface text, each code comment and each commit message. Code, identifiers and command syntax stay as they are.

- Write at most 20 words in one instruction and at most 25 words in one descriptive sentence.
- Use active voice.
- Do not use a contraction.
- Do not use a semicolon in prose.
- Do not use these words: seamless, robust, powerful, leverage, ensure, delve, streamline, simply, just, utilize, in order to.
- Use the terms of `doc/TERMINOLOGY.md`. Use one name for one thing.
- Use American spelling.
- A comment line with two or more spaces before its text is a command example. `CommentStyleTest` does not read it for the word rules.

Check your own text before you give a patch. A long sentence and a banned word are easy to find with a search, and easy to miss by eye.

---

## 9. Working agreement for Claude sessions

1. Read this document and `doc/TERMINOLOGY.md` before you propose a change.
2. Ask before you add a dependency, a build step or a framework.
3. Bump the version in each change. See section 4.
4. Run `test/run.sh` and report the result for each change. If you cannot run the Android build, say so.
5. Write each document, comment and commit message in Simplified Technical English. See section 8.
6. **Never commit. Never push. Never make a tag.** The maintainer applies each change.
7. Give each change as a unified diff for `git apply`.
8. Give a commit message file together with the patch. Use the format of section 5.
9. Before each step, compare `master` on GitHub with the last patch. If the last patch is not on GitHub, remind the maintainer to push it.
