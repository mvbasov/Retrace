# STAGE 2: The quality gate, then the APK.
#
# project_builder is the last stage. Thus local/build.sh builds it.
# Run the gate alone with this command:
#   docker buildx build --target test .

# ------------------------------ test ---------------------------------
# The quality gate runs the Java tests of test/ with javac and java.
# The last step writes /gate-passed. project_builder copies that file.
# Thus no APK comes from a build with a failed gate.
FROM retrace-base:latest AS test

# Set SKIP_TESTS to 1 to skip the gate for an emergency build:
#   docker buildx build --build-arg SKIP_TESTS=1 ...
ARG SKIP_TESTS=0

COPY . .

RUN if [ "$SKIP_TESTS" = "1" ]; then \
        echo "WARNING: SKIP_TESTS=1 - test gate bypassed"; \
    else \
        sh test/run.sh; \
    fi && \
    touch /gate-passed

# -------------------------- project_builder --------------------------
FROM retrace-base:latest AS project_builder

# The signing configuration in app/build.gradle reads these values.
# local/build.sh gives them from the .env file.
ARG KEYSTORE_PASSWORD
ARG KEY_ALIAS
ARG KEY_PASSWORD

# This copy makes the test stage a part of each build of this stage.
COPY --from=test /gate-passed /tmp/gate-passed

COPY . .

# The cache mount keeps the Gradle downloads between two builds.
# The name of the APK file comes from the appVersion line of app/build.gradle.
# If the signature is missing, Gradle writes app-release-unsigned.apk. The
# copy then fails, and the build stops.
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    gradle --no-daemon assembleRelease && \
    mkdir -p bin && \
    VERSION=$(awk -F"'" '/^def appVersion/ {print $2}' app/build.gradle) && \
    test -n "$VERSION" && \
    cp app/build/outputs/apk/release/app-release.apk "bin/retrace-v${VERSION}-release.apk"
