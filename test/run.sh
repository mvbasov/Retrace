#!/bin/sh
# Compiles and runs the Java tests with plain javac and java.
#
# The tests use no JUnit and no Gradle. Each test class has a main method.
# A failed check gives exit code 1, and the script stops at the first failed
# class.
#
# The script compiles the package net.basov.retrace.core together with the
# tests. That package imports no Android package. An Android import there
# stops the compilation, thus this script also checks that rule.
set -e

cd "$(dirname "$0")/.."

OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT

find app/src/main/java/net/basov/retrace/core test/java -name '*.java' > "$OUT/sources.txt"
javac -encoding UTF-8 -Xlint:all -Werror -d "$OUT/classes" @"$OUT/sources.txt"

for file in $(cd test/java && find . -name '*Test.java' | sort); do
    class=$(echo "$file" | sed 's|^\./||; s|\.java$||; s|/|.|g')
    java -cp "$OUT/classes" "$class" "$PWD"
done
