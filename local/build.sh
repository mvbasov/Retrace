#!/bin/bash
# Builds the release APK in Docker and copies it to ./output-binaries/.
#
# The host needs Docker with BuildKit only. It needs no Java, no Gradle and
# no Android SDK. Optional: the .env file gives the keystore passwords. See
# .env.example.
#
# The terminal shows the interactive progress of BuildKit. That view
# removes the lines of each finished step. Thus the script also writes the
# full plain log of both stages to output-binaries/build.log.
set -e

cd "$(dirname "$0")/.."

OUT=./output-binaries
LOG="$OUT/build.log"

# The APK and the log of the last build go into an empty directory. A failed
# build thus leaves its log and no old APK.
rm -rf "$OUT"
mkdir -p "$OUT"

# Adds the plain log of the last BuildKit build to the log file.
# "docker buildx history logs" gives the same text as --progress=plain.
save_log() {
    {
        echo "======== $1 ========"
        docker buildx history logs 2>&1 \
            || echo "docker buildx history logs failed. Update Docker Buildx for the log."
        echo
    } >> "$LOG"
}

# Read the .env file. Each line that is not empty and not a comment becomes
# one build argument.
BUILD_ARGS=()
if [ -f .env ]; then
    while IFS= read -r line || [ -n "$line" ]; do
        case "$line" in
            ''|'#'*) continue ;;
        esac
        BUILD_ARGS+=(--build-arg "$line")
    done < .env
    set -a
    . ./.env
    set +a
fi

# These defaults agree with the defaults in app/build.gradle.
KEYSTORE_PASSWORD="${KEYSTORE_PASSWORD:-retrace123}"
KEY_ALIAS="${KEY_ALIAS:-retrace}"
KEY_PASSWORD="${KEY_PASSWORD:-retrace123}"

echo "-------- STAGE 1: toolchain image -------"
if ! docker buildx build -f Dockerfile.base -t retrace-base:latest --load .; then
    save_log "STAGE 1: toolchain image"
    echo "STAGE 1 failed. The log is in $LOG."
    exit 1
fi
save_log "STAGE 1: toolchain image"

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

echo "-------- STAGE 2: test gate and APK -------"
if ! docker buildx build -t retrace-builder:latest --load "${BUILD_ARGS[@]}" .; then
    save_log "STAGE 2: test gate and APK"
    echo "STAGE 2 failed. The log is in $LOG."
    exit 1
fi
save_log "STAGE 2: test gate and APK"

# Copy the APK out of the image.
docker rm -f retrace-extract > /dev/null 2>&1 || true
docker create --name retrace-extract retrace-builder:latest > /dev/null
docker cp retrace-extract:/app/bin/. "$OUT/"
docker rm retrace-extract > /dev/null

ls -l "$OUT/"
echo "The APK and build.log are in $OUT/."
