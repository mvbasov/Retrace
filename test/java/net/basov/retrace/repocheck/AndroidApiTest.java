package net.basov.retrace.repocheck;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// ----------------------------------------------------------------------
// The check of Java library calls that Android 6 does not have
// ----------------------------------------------------------------------
//
// The build compiles the application against SDK platform 36. Thus javac
// accepts a Java library call that Android adds after API 23. On Android 6,
// that call stops the application with NoSuchMethodError.
//
// This test reads the source files of the application. It finds each call
// of a known list. The list is not complete. Rule 4 of section 1 of
// CLAUDE.md is the full rule.
public final class AndroidApiTest {

    // Each entry is a pattern and the reason. All are API 24 or newer.
    private static final String[][] FORBIDDEN = {
            {"\\bjava\\.time\\b", "java.time is API 26"},
            {"\\bjava\\.util\\.stream\\b", "java.util.stream is API 24"},
            {"\\bjava\\.util\\.function\\b", "java.util.function is API 24"},
            {"\\bjava\\.util\\.Optional\\b", "Optional is API 24"},
            {"\\bMath\\.(floorDiv|floorMod|addExact|subtractExact|multiplyExact|toIntExact)\\b",
                    "these Math methods are API 24"},
            {"\\bString\\.join\\b", "String.join is API 26"},
            {"\\b(List|Set|Map)\\.(of|copyOf)\\b", "List.of, Set.of and Map.of are API 30"},
            {"\\.getOrDefault\\(", "Map.getOrDefault is API 24"},
            {"\\.putIfAbsent\\(", "Map.putIfAbsent is API 24"},
            {"\\.computeIfAbsent\\(", "Map.computeIfAbsent is API 24"},
            {"\\.forEach\\(", "Iterable.forEach is API 24"},
            {"\\.removeIf\\(", "Collection.removeIf is API 24"},
            {"\\.stream\\(\\)", "Collection.stream is API 24"},
            {"\\bObjects\\.(requireNonNullElse|isNull|nonNull)\\b", "these Objects methods are API 24"},
            {"\\b(Long|Integer|Double)\\.(hashCode|sum|max|min)\\(", "these boxed methods are API 24"},
            {"\\bString\\.(repeat|strip|isBlank|lines)\\b", "these String methods are API 33 or JDK 11"},
            {"\\.(repeat|strip|isBlank|lines)\\(\\)", "these String methods are API 33 or JDK 11"},
            {"\\bjava\\.nio\\.file\\b", "java.nio.file is API 26"},
    };

    private static int faults = 0;

    public static void main(String[] args) throws IOException {
        Path root = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Path source = root.resolve("app/src/main/java");
        List<Path> files;
        try (Stream<Path> walk = Files.walk(source)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).sorted()
                    .collect(Collectors.toList());
        }

        Pattern[] patterns = new Pattern[FORBIDDEN.length];
        for (int i = 0; i < FORBIDDEN.length; i++) {
            patterns[i] = Pattern.compile(FORBIDDEN[i][0]);
        }

        for (Path file : files) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int n = 0; n < lines.size(); n++) {
                String code = withoutComment(lines.get(n));
                for (int i = 0; i < patterns.length; i++) {
                    Matcher m = patterns[i].matcher(code);
                    if (m.find()) {
                        faults++;
                        System.out.println("FAIL " + root.relativize(file) + ":" + (n + 1)
                                + ": " + m.group() + " - " + FORBIDDEN[i][1]);
                    }
                }
            }
        }

        if (faults > 0) {
            System.out.println("AndroidApiTest: " + faults + " fault(s)");
            System.exit(1);
        }
        System.out.println("AndroidApiTest: every check passed (" + files.size() + " files)");
    }

    // A comment can name a forbidden call, for example to say why the code
    // does not use it.
    private static String withoutComment(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
            return "";
        }
        int slash = line.indexOf("//");
        return slash >= 0 ? line.substring(0, slash) : line;
    }
}
