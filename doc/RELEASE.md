# The GitHub build and release

This document tells how the GitHub workflows of Retrace build the APK and make a release. It also gives the one-time setup of the repository on GitHub, and the release procedure.

The workflows follow the workflows of OMN-Go. This document uses ASD-STE100 Simplified Technical English.

---

## 1. The workflows

| Workflow | File | Start | Result |
| --- | --- | --- | --- |
| Build Retrace | `.github/workflows/release.yml` | A push to `master`, a pull request to `master`, a push of a tag `v*` | A signed APK. For a tag push, also a GitHub release with the APK. |
| Test Retrace | `.github/workflows/test.yml` | A push to `master`, a pull request to `master` | The result of the quality gate. It needs no secret. |

Each workflow runs on a new `ubuntu-latest` machine. The machine has no Java, no Android SDK and no Gradle. The Docker build gives all of them, the same as the local build.

---

## 2. How the release workflow works

The workflow "Build Retrace" does these steps:

1. It checks out the commit, with all the tags.
2. It sets up Docker Buildx.
3. It writes the keystore from the secret `KEYSTORE_BASE64` to a file in `RUNNER_TEMP`. `RUNNER_TEMP` is outside the checkout. Thus the Docker context does not hold the keystore.
4. It builds the stage `export` of `Dockerfile.ci`:
   - The stage `test` runs `test/run.sh`. A failed test stops the build, and no APK comes out.
   - The stage `project_builder` runs `gradle assembleRelease` and signs the APK.
   - The stage `export` holds the APK alone. BuildKit writes it to `./artifacts`.
5. It removes the keystore file. This step runs also after a failed build.
6. It reads the version from the line `def appVersion` of `app/build.gradle`.
7. For a tag push only, it compares the tag with `v` and the version. A tag that does not agree gives no release.
8. For a tag push only, it makes a GitHub release with the name "Retrace YY.MM.S". The release holds the file `retrace-vYY.MM.S-release.apk` and notes that GitHub makes from the commits.

### 2.1 Why Dockerfile.ci

The local build has two files: `Dockerfile.base` makes the image `retrace-base:latest`, and `Dockerfile` starts from that image. The workflow uses the `docker-container` driver of Docker Buildx. That builder has its own image store. Thus a second build cannot find `retrace-base:latest` from a first build. It tries to download the image from a registry, and the build fails.

`Dockerfile.ci` holds the stages of the two files in one file. One file gives one BuildKit graph, and no image of an earlier build is necessary. OMN-Go uses the same solution.

**Change `Dockerfile.ci` together with `Dockerfile.base` or `Dockerfile`.** `BuildFilesTest` compares the files and finds a difference.

### 2.2 How the secrets go to the build

The keystore and the passwords go to the build as BuildKit secrets, not as build arguments. A build argument stays readable in the image history. A BuildKit secret is present only during the one build step that mounts it. Thus no image layer holds the key or its passwords.

| Repository secret | BuildKit secret | Use in `Dockerfile.ci` |
| --- | --- | --- |
| `KEYSTORE_BASE64` | `keystore` (the decoded file) | `KEYSTORE_FILE=/run/secrets/keystore` |
| `KEYSTORE_PASSWORD` | `keystore_password` | `KEYSTORE_PASSWORD` |
| `KEY_ALIAS` | `key_alias` | `KEY_ALIAS` |
| `KEY_PASSWORD` | `key_password` | `KEY_PASSWORD` |

`app/build.gradle` reads the four environment variables for the signing configuration. Each mount of `Dockerfile.ci` has `required=true`. A missing secret thus stops the build at once with a clear message.

GitHub does not give the secrets to a pull request from a fork. The build of such a pull request thus fails at the signing step. The workflow "Test Retrace" still gives the test result.

### 2.3 The cache

A workflow machine is new for each run. The workflow keeps the Docker layers in the GitHub cache with `--cache-from` and `--cache-to type=gha`. The first run downloads the Android SDK and Gradle, and takes some minutes more. A later run uses the cache, if `Dockerfile.ci` did not change before the step.

---

## 3. One-time setup on GitHub

Do these steps one time, in the web interface of GitHub.

### 3.1 The same key as the local build

Android installs an update only when the same key signs it. The workflow must thus use the keystore of your local build, `app/retrace.keystore`. Then an APK from GitHub installs over an APK from `local/build.sh`, and the reverse.

The secrets of OMN-Go have the same names. Their values are the values of the OMN-Go keystore. Do not copy them. The Retrace repository needs its own secrets with the values of the Retrace keystore.

### 3.2 Add the secrets

1. On your computer, go to the directory of the repository.
2. Write the keystore as base64 text to a file outside the repository:

   ```
   base64 -w0 app/retrace.keystore > /tmp/retrace-keystore.txt
   ```

   On macOS, use this command:

   ```
   base64 -i app/retrace.keystore -o /tmp/retrace-keystore.txt
   ```

3. On GitHub, open the repository Retrace.
4. Push **Settings → Secrets and variables → Actions**.
5. On the tab **Secrets**, push **New repository secret**.
6. In **Name**, type `KEYSTORE_BASE64`.
7. In **Secret**, paste all the text of `/tmp/retrace-keystore.txt`. The text is one long line with no space.
8. Push **Add secret**.
9. Add three more secrets in the same way. Take the values from `.env`. Without `.env`, take the values from `.env.example`.

   | Name | Value |
   | --- | --- |
   | `KEYSTORE_PASSWORD` | The value of `KEYSTORE_PASSWORD` |
   | `KEY_ALIAS` | The value of `KEY_ALIAS` |
   | `KEY_PASSWORD` | The value of `KEY_PASSWORD` |

10. Remove the text file:

    ```
    rm /tmp/retrace-keystore.txt
    ```

11. Make sure that **Repository secrets** shows `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`.

GitHub does not show a secret again after you add it. Keep a copy of `app/retrace.keystore` outside the repository, for example on a USB drive. Without the keystore, no update installs over the old APK.

### 3.3 Check the settings of the actions

1. Push **Settings → Actions → General**.
2. In **Actions permissions**, make sure that the repository permits the actions of the workflows. **Allow all actions and reusable workflows** permits them.
   The workflows use `actions/checkout`, `docker/setup-buildx-action` and `softprops/action-gh-release`.
3. The release workflow asks for the permission `contents: write` itself. Thus the setting **Workflow permissions** can stay at the default.

---

## 4. Make a release

1. Bump `appVersion` in `app/build.gradle`. Each patch does this already.
2. Commit the change, and push it to `master`.
3. Make the tag of the same version, and push it. Example for the version 26.10.27:

   ```
   git tag v26.10.27
   git push origin v26.10.27
   ```

4. On GitHub, open **Actions**, and open the run of **Build Retrace** for the tag.
5. After the run, open **Releases**. The new release holds the APK `retrace-vYY.MM.S-release.apk`.

### 4.1 Check the signature

Compare the certificate of a local APK with the certificate of the release APK. Do this one time after the setup, and after each change of the secrets.

1. Download the APK of the release to `output-binaries/`.
2. Run this command for the release APK and for a local APK:

   ```
   docker run --rm -v "$PWD:/w" retrace-base:latest /opt/android/build-tools/36.0.0/apksigner verify --print-certs /w/output-binaries/retrace-v26.10.27-release.apk
   ```

3. Compare the line `Signer #1 certificate SHA-256 digest`. It must be the same for the two files.

---

## 5. Change the default passwords

Without `.env`, the passwords are the public values of `.env.example`. Then the keystore file is the only protection of the key. You can change the password and keep the key. The signature stays the same.

1. Change the password. The keystore is a PKCS12 keystore, thus the key has the password of the keystore:

   ```
   docker run --rm -it --user "$(id -u):$(id -g)" -e HOME=/tmp -v "$PWD/app:/out" retrace-base:latest keytool -storepasswd -keystore /out/retrace.keystore
   ```

2. Write the new password into `.env`, for `KEYSTORE_PASSWORD` and for `KEY_PASSWORD`.
3. Run `docker builder prune`. A change of the keystore alone does not change the cache key of the local build step.
4. Make the base64 text again. Use steps 1 and 2 of section 3.2.
5. On GitHub, open **Settings → Secrets and variables → Actions**.
6. Push the pencil icon of `KEYSTORE_BASE64`. Paste the new text, and push **Update secret**.
7. Do step 6 again for `KEYSTORE_PASSWORD` and `KEY_PASSWORD`, with the new password.
8. Remove the text file.
9. Do the check of section 4.1.

---

## 6. Faults

| Message or symptom | Cause | Remedy |
| --- | --- | --- |
| `secret keystore_password is required`, or the same for another id. A message of Gradle about the keystore is also possible. | A secret is missing, or it is empty. GitHub gives a missing secret as an empty value. | Add the secret. See section 3.2. Then push **Re-run jobs → Re-run failed jobs** on the page of the run. |
| `base64: invalid input` in the step "Restore Android keystore" | The secret `KEYSTORE_BASE64` has a part of the text, or has spaces. | Make the base64 text again, and paste all of it. |
| `Keystore was tampered with, or password was incorrect` | `KEYSTORE_PASSWORD` does not agree with the keystore. | Correct the secret. |
| `Cannot recover key`, or `No key with alias` | `KEY_ALIAS` or `KEY_PASSWORD` does not agree with the keystore. | Correct the secret. For a PKCS12 keystore, `KEY_PASSWORD` is the same as `KEYSTORE_PASSWORD`. |
| `The tag ... does not agree with appVersion ...` | The tag is not `v` and the version of `app/build.gradle`. | Remove the wrong tag. Push the tag of the version. See section 6.1. |
| Android says "App not installed", or "The package conflicts with an existing package" | The release APK has a different key from the installed APK. | Use the keystore of the local build. See section 3.1 and section 4.1. |
| The build of a pull request from a fork fails at the signing step | GitHub does not give the secrets to a fork. | No remedy is necessary. See the result of "Test Retrace". |
| A failed test stops the build | The quality gate found a fault. | Run `test/run.sh` on your computer, and correct the fault. |
| A difference between `Dockerfile.ci` and `Dockerfile.base` or `Dockerfile` | `BuildFilesTest` found a change in one file only. | Make the same change in the other file. |

### 6.1 Remove a wrong tag

1. Remove the tag on your computer and on GitHub:

   ```
   git tag -d v26.10.27
   git push origin :refs/tags/v26.10.27
   ```

2. If GitHub made a release for the tag, open **Releases** and delete that release.
3. Push the correct tag. See section 4.

### 6.2 A failed release build

The tag of a failed build stays, and it has no release. Do one of these:

- If the fault was in the setup, for example a missing secret, correct it. Then push **Re-run jobs → Re-run failed jobs** on the page of the run. The run builds the same commit again.
- If the fault was in the code, correct it in a new commit with a new version. Then push the tag of the new version.
