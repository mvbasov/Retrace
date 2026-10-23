#!/bin/sh
# Writes one Dockerfile for the release workflow.
#
# The workflow uses the docker-container driver of Docker Buildx. That
# builder has its own image store. Thus a second build cannot use the
# retrace-base:latest image of a first build. This script puts the stages of
# Dockerfile.base and Dockerfile into one file, and the two stages then
# make one graph.
#
# The local build keeps the two files. Thus this script makes the merged
# file at each run, and no copy of the two files goes out of date.
# BuildFilesTest checks the form of the two files that this script needs.
#
# The export stage holds the APK alone. The workflow writes it to a local
# directory with --output.
#
# Usage:
#   sh .github/ci-dockerfile.sh OUTPUT_FILE
set -eu

out="$1"

if [ "$(grep -c '^FROM ' Dockerfile.base)" != "1" ]; then
    echo "Dockerfile.base must have exactly one FROM line." >&2
    exit 1
fi

{
    echo "# Made by .github/ci-dockerfile.sh from Dockerfile.base and Dockerfile."
    awk '/^FROM / && !done { print $0 " AS retrace_base"; done = 1; next } { print }' Dockerfile.base
    echo
    sed 's/^FROM retrace-base:latest AS /FROM retrace_base AS /' Dockerfile
    echo
    echo "FROM scratch AS export"
    echo "COPY --from=project_builder /app/bin/ /"
} > "$out"

# The merged file must not use the image of the local build.
if grep -q '^FROM retrace-base' "$out"; then
    echo "A FROM line of Dockerfile does not have the form FROM retrace-base:latest AS name." >&2
    exit 1
fi
