#!/bin/bash
# Builds the release APK in Docker and copies it to ./output-binaries/.
#
# The host needs Docker with BuildKit only. It needs no Java, no Gradle and
# no Android SDK. Optional: the .env file gives the keystore passwords. See
# .env.example.
#
# The terminal shows the interactive progress of BuildKit. That view removes
# the lines of each finished step. Thus the script also writes the full plain
# log to output-binaries/build.log:
# - Docker Buildx v0.20.0 and newer give the log of each stage with
#   "docker buildx history logs".
# - An older Docker Buildx cannot give the log afterwards. The script then
#   builds a failed stage again with --progress=plain into the log. The
#   cache makes the steps before the failed step fast.
set -e

cd "$(dirname "$0")/.."

OUT=./output-binaries
LOG="$OUT/build.log"

# The APK and the log of the last build go into an empty directory. A failed
# build thus leaves its log and no old APK.
rm -rf "$OUT"
mkdir -p "$OUT"

# An older Docker Buildx prints its general help for an unknown command, and
# its exit code is 0. Thus the test looks for an option of "history logs".
if docker buildx history logs --help 2>&1 | grep -q -- '--progress'; then
    HAVE_HISTORY=1
else
    HAVE_HISTORY=0
    {
        echo "This Docker Buildx has no 'docker buildx history logs'."
        echo "That command needs Docker Buildx v0.20.0 or newer: $(docker buildx version 2>&1)"
        echo "This log holds the plain output of a failed stage only."
        echo
    } >> "$LOG"
fi

# Runs one stage. $1 is the title for the log, the other arguments go to
# "docker buildx build".
build_stage() {
    local title="$1"
    shift
    echo "-------- $title -------"
    if docker buildx build "$@"; then
        if [ "$HAVE_HISTORY" = 1 ]; then
            { echo "======== $title ========"; docker buildx history logs 2>&1; echo; } >> "$LOG"
        fi
        return 0
    fi
    {
        echo "======== $title: FAILED ========"
        if [ "$HAVE_HISTORY" = 1 ]; then
            docker buildx history logs 2>&1
        else
            docker buildx build --progress=plain "$@" 2>&1
        fi
        echo
    } >> "$LOG" || true
    echo "$title failed. The log is in $LOG."
    exit 1
}

# The .env file gives the passwords to keytool here. It goes to the build as
# a BuildKit secret, not as build arguments. See the Dockerfile.
if [ -f .env ]; then
    set -a
    . ./.env
    set +a
fi

# These defaults agree with the defaults in app/build.gradle.
KEYSTORE_PASSWORD="${KEYSTORE_PASSWORD:-retrace123}"
KEY_ALIAS="${KEY_ALIAS:-retrace}"
KEY_PASSWORD="${KEY_PASSWORD:-retrace123}"

build_stage "STAGE 1: toolchain image" \
    -f Dockerfile.base -t retrace-base:latest --load .

# The first build makes the release keystore. The keytool of the toolchain
# image makes it, thus the host needs no JDK. Keep a copy of this file.
# Android installs an update only when the same key signs it.
if [ ! -f app/retrace.keystore ]; then
    echo "-------- Make app/retrace.keystore -------"
    docker run --rm \
        --user "$(id -u):$(id -g)" \
        -e HOME=/tmp \
        -v "$PWD/app:/out" \
        retrace-base:latest \
        keytool -genkeypair -v \
            -keystore /out/retrace.keystore \
            -alias "$KEY_ALIAS" \
            -keyalg RSA -keysize 2048 -validity 10000 \
            -storepass "$KEYSTORE_PASSWORD" \
            -keypass "$KEY_PASSWORD" \
            -dname "CN=Retrace, O=Basov"
fi

SECRETS=(--secret id=keystore,src=app/retrace.keystore)
if [ -f .env ]; then
    SECRETS+=(--secret id=signing_env,src=.env)
fi

build_stage "STAGE 2: test gate and APK" \
    -t retrace-builder:latest --load "${SECRETS[@]}" .

# Copy the APK out of the image.
docker rm -f retrace-extract > /dev/null 2>&1 || true
docker create --name retrace-extract retrace-builder:latest > /dev/null
docker cp retrace-extract:/app/bin/. "$OUT/"
docker rm retrace-extract > /dev/null

ls -l "$OUT/"
echo "The APK and build.log are in $OUT/."
