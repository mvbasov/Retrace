package net.basov.retrace.repocheck;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// ----------------------------------------------------------------------
// The check of the build files against the release workflow
// ----------------------------------------------------------------------
//
// The release workflow makes one Dockerfile from Dockerfile.base and
// Dockerfile with .github/ci-dockerfile.sh. The script needs a fixed form of
// the FROM lines. The workflow also gives the secrets and reads the APK by
// name. A change of one of these files can break a release, and only the
// next tag would show it. This test finds the fault at once.
public final class BuildFilesTest {

    private static final Pattern STAGE = Pattern.compile("^FROM retrace-base:latest AS (\\w+)$");

    private static int failures = 0;

    public static void main(String[] args) throws IOException {
        Path root = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        List<String> base = lines(root.resolve("Dockerfile.base"));
        List<String> main = lines(root.resolve("Dockerfile"));
        String dockerfile = String.join("\n", main);
        String workflow = new String(Files.readAllBytes(
                root.resolve(".github/workflows/release.yml")), StandardCharsets.UTF_8);
        String envExample = new String(Files.readAllBytes(root.resolve(".env.example")),
                StandardCharsets.UTF_8);

        // The script adds a stage name to the one FROM line of the base.
        int baseFrom = 0;
        for (String line : base) {
            if (line.startsWith("FROM ")) {
                baseFrom++;
                check("Dockerfile.base: the FROM line has no stage name", false,
                        line.contains(" AS "));
            }
        }
        check("Dockerfile.base: FROM lines", 1, baseFrom);

        // The script changes each FROM line of Dockerfile to the base stage.
        List<String> stages = new ArrayList<>();
        for (String line : main) {
            if (line.startsWith("FROM ")) {
                Matcher m = STAGE.matcher(line);
                check("Dockerfile: form of \"" + line + "\"", true, m.matches());
                if (m.matches()) {
                    stages.add(m.group(1));
                }
            }
        }
        check("Dockerfile: stages", "[test, project_builder]", stages.toString());

        // The secrets of the workflow and the mounts of the Dockerfile.
        for (String id : new String[]{"keystore", "signing_env"}) {
            check("Dockerfile: secret " + id, true, dockerfile.contains("id=" + id));
            check("workflow: secret " + id, true, workflow.contains("--secret id=" + id + ","));
        }

        // The workflow writes the same keys as .env.example.
        for (String key : new String[]{"KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD"}) {
            check(".env.example: " + key, true, envExample.contains("\n" + key + "="));
            check("workflow: " + key, true, workflow.contains("echo \"" + key + "="));
        }

        // The name of the APK.
        check("Dockerfile: APK name", true,
                dockerfile.contains("bin/retrace-v${VERSION}-release.apk"));
        check("workflow: APK name", true,
                workflow.contains("artifacts/retrace-v${{ steps.version.outputs.version }}-release.apk"));
        // The secrets are secrets of the environment, see README.md.
        check("workflow: environment", true, workflow.contains("    environment: release\n"));
        check("workflow: tag filter", true,
                workflow.contains("tags: ['v[0-9][0-9].[0-9][0-9].[0-9]*']"));

        if (failures > 0) {
            System.out.println("BuildFilesTest: " + failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("BuildFilesTest: every check passed");
    }

    private static List<String> lines(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8);
    }

    private static void check(String name, Object expected, Object actual) {
        boolean same = expected == null ? actual == null : expected.equals(actual);
        if (!same) {
            failures++;
            System.out.println("FAIL " + name + ": expected " + expected + ", got " + actual);
        }
    }
}
