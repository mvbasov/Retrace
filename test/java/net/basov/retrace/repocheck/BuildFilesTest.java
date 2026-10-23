package net.basov.retrace.repocheck;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

// ----------------------------------------------------------------------
// The check of the build files against each other
// ----------------------------------------------------------------------
//
// Dockerfile.ci is a copy of Dockerfile.base and Dockerfile for the CI, the
// same as in OMN-Go. A change of one of the files must go into the other
// files too. The release workflow gives the secrets and reads the APK by
// name. A fault here shows only at the next release. This test finds it at
// once.
public final class BuildFilesTest {

    private static int failures = 0;

    public static void main(String[] args) throws IOException {
        Path root = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        List<String> base = instructions(root.resolve("Dockerfile.base"));
        List<String> local = instructions(root.resolve("Dockerfile"));
        List<String> ci = instructions(root.resolve("Dockerfile.ci"));
        String workflow = read(root.resolve(".github/workflows/release.yml"));
        String test = read(root.resolve(".github/workflows/test.yml"));
        String envExample = read(root.resolve(".env.example"));

        // Each line of Dockerfile.base after its FROM line is in the first
        // stage of Dockerfile.ci, in the same sequence.
        check("Dockerfile.base: one FROM line", 1, countFrom(base));
        check("Dockerfile.ci: base_env stage", true,
                ci.contains(base.get(0) + " AS base_env"));
        checkInOrder("Dockerfile.base in Dockerfile.ci", base.subList(1, base.size()), ci);

        // The test stage of Dockerfile is in Dockerfile.ci.
        List<String> testStage = stage(local, "FROM retrace-base:latest AS test");
        check("Dockerfile: test stage", false, testStage.isEmpty());
        checkInOrder("test stage in Dockerfile.ci", testStage, stage(ci, "FROM base_env AS test"));

        // The two builds make the APK with the same commands.
        String[] same = {
                "COPY --from=test /gate-passed /tmp/gate-passed",
                "gradle --no-daemon assembleRelease && \\",
                "VERSION=$(awk -F\"'\" '/^def appVersion/ {print $2}' app/build.gradle) && \\",
                "cp app/build/outputs/apk/release/app-release.apk \"bin/retrace-v${VERSION}-release.apk\"",
        };
        for (String line : same) {
            check("Dockerfile: " + line, true, local.contains(line));
            check("Dockerfile.ci: " + line, true, ci.contains(line));
        }
        check("Dockerfile.ci: export stage", "FROM scratch AS export", lastFrom(ci));

        // The secret ids of Dockerfile.ci and of the workflow agree.
        String ciText = String.join("\n", ci);
        for (String id : new String[]{"keystore", "keystore_password", "key_alias", "key_password"}) {
            check("Dockerfile.ci: secret " + id, true,
                    ciText.contains("--mount=type=secret,id=" + id + ",required=true"));
            check("workflow: secret " + id, true, workflow.contains("--secret id=" + id + ","));
        }
        for (String secret : new String[]{"KEYSTORE_BASE64", "KEYSTORE_PASSWORD", "KEY_ALIAS",
                "KEY_PASSWORD"}) {
            check("workflow: repository secret " + secret, true,
                    workflow.contains("${{ secrets." + secret + " }}"));
        }

        // The local build reads the same keys from .env.
        for (String key : new String[]{"KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD"}) {
            check(".env.example: " + key, true, envExample.contains("\n" + key + "="));
        }

        check("workflow: APK name", true, workflow.contains("artifacts/retrace-v*-release.apk"));
        check("workflow: tag filter", true, workflow.contains("tags: ['v*']"));
        check("workflow: Dockerfile.ci", true, workflow.contains("-f Dockerfile.ci"));
        check("test workflow: Dockerfile.ci", true, test.contains("-f Dockerfile.ci"));

        if (failures > 0) {
            System.out.println("BuildFilesTest: " + failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("BuildFilesTest: every check passed");
    }

    // The lines of a Dockerfile with no comment and no empty line, trimmed.
    private static List<String> instructions(Path file) throws IOException {
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("#")) {
                out.add(t);
            }
        }
        return out;
    }

    // The lines after the FROM line, up to the next FROM line.
    private static List<String> stage(List<String> lines, String from) {
        List<String> out = new ArrayList<>();
        int start = lines.indexOf(from);
        if (start < 0) {
            return out;
        }
        for (int i = start + 1; i < lines.size() && !lines.get(i).startsWith("FROM "); i++) {
            out.add(lines.get(i));
        }
        return out;
    }

    private static int countFrom(List<String> lines) {
        int n = 0;
        for (String l : lines) {
            if (l.startsWith("FROM ")) {
                n++;
            }
        }
        return n;
    }

    private static String lastFrom(List<String> lines) {
        String last = null;
        for (String l : lines) {
            if (l.startsWith("FROM ")) {
                last = l;
            }
        }
        return last;
    }

    // Each expected line is in the actual lines, in the same sequence.
    // Other lines can come between them.
    private static void checkInOrder(String name, List<String> expected, List<String> actual) {
        int at = 0;
        for (String line : expected) {
            int found = -1;
            for (int i = at; i < actual.size(); i++) {
                if (actual.get(i).equals(line)) {
                    found = i;
                    break;
                }
            }
            if (found < 0) {
                failures++;
                System.out.println("FAIL " + name + ": missing or out of sequence: " + line);
                return;
            }
            at = found + 1;
        }
    }

    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static void check(String name, Object expected, Object actual) {
        boolean same = expected == null ? actual == null : expected.equals(actual);
        if (!same) {
            failures++;
            System.out.println("FAIL " + name + ": expected " + expected + ", got " + actual);
        }
    }
}
