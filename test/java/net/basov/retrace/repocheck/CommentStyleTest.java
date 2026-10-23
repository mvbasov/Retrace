package net.basov.retrace.repocheck;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// ----------------------------------------------------------------------
// The style check of each comment in the repository
// ----------------------------------------------------------------------
//
// Section 8 of CLAUDE.md asks for Simplified Technical English in each
// comment. This test reads each whole-line comment of the Java, Gradle,
// shell, Docker and properties files. It demands zero faults.
//
// The rules that a program can check:
// - A sentence has at most 25 words.
// - A comment has no semicolon and no contraction.
// - A comment has none of the banned words of section 8.
// - A comment holds no application version number. The number goes out of
//   date, and nobody updates it.
//
// Text between backticks is code. The test does not read it for the word
// rules. A comment line with two or more spaces before its text is a
// command example. The test reads it for the version rule only.
//
// This file runs on the JVM of the build image, not on Android. Thus it can
// use java.nio.file and java.util.stream.
public final class CommentStyleTest {

    private static final int MAX_WORDS = 25;

    private static final Set<String> SKIP_DIRECTORIES = new HashSet<>(Arrays.asList(
            ".git", ".gradle", ".idea", "build", "output-binaries"));

    private static final Pattern BANNED = Pattern.compile(
            "(?i)\\b(seamless|seamlessly|robust|powerful|leverage|ensure|ensures|delve"
                    + "|streamline|simply|just|utilize|utilise|in\\s+order\\s+to)\\b");

    private static final Pattern CONTRACTION = Pattern.compile(
            "(?i)\\b(\\w+n't|it's|that's|there's|here's|what's|let's|i'm"
                    + "|\\w+'re|\\w+'ve|\\w+'ll|\\w+'d)(?!\\w)");

    private static final Pattern APP_VERSION = Pattern.compile(
            "(?<![\\d.])\\d{2}\\.\\d{2}\\.\\d{1,3}(?![\\d.])");

    private static final Pattern CODE_SPAN = Pattern.compile("`[^`]*`");

    private static final Pattern RULE_LINE = Pattern.compile("^[-=*_~\\s]+$");

    private static final Pattern LIST_ITEM = Pattern.compile("^(-|\\*|\\d+[.)])\\s.*");

    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?:])\\s+");

    private enum Style { SLASH, HASH }

    // One line of comment text, with its place in the file.
    private static final class Line {
        final Path file;
        final int number;
        final String text;
        final boolean code;

        Line(Path file, int number, String body) {
            this.file = file;
            this.number = number;
            this.code = body.startsWith("  ") || body.startsWith("\t");
            this.text = body.trim();
        }
    }

    private static Path root;
    private static int faults = 0;
    private static int commentLines = 0;

    public static void main(String[] args) throws IOException {
        root = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        List<Path> files = sourceFiles();
        for (Path file : files) {
            for (List<Line> block : commentBlocks(file, styleOf(file))) {
                commentLines += block.size();
                checkBlock(block);
            }
        }

        if (faults > 0) {
            System.out.println("CommentStyleTest: " + faults + " fault(s)");
            System.exit(1);
        }
        System.out.println("CommentStyleTest: every check passed ("
                + files.size() + " files, " + commentLines + " comment lines)");
    }

    private static List<Path> sourceFiles() throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(p -> !inSkippedDirectory(p))
                    .filter(p -> styleOf(p) != null)
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    private static boolean inSkippedDirectory(Path file) {
        for (Path part : root.relativize(file)) {
            if (SKIP_DIRECTORIES.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private static Style styleOf(Path file) {
        String name = file.getFileName().toString();
        if (name.endsWith(".java") || name.endsWith(".gradle")) {
            return Style.SLASH;
        }
        if (name.endsWith(".sh") || name.endsWith(".properties") || name.endsWith(".pro")
                || name.startsWith("Dockerfile") || name.equals(".env.example")
                || name.endsWith(".yml")) {
            return Style.HASH;
        }
        return null;
    }

    // Groups the comment lines of a file. Lines that follow each other
    // make one group, and a code line ends the group.
    private static List<List<Line>> commentBlocks(Path file, Style style) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<List<Line>> blocks = new ArrayList<>();
        List<Line> current = null;
        boolean inBlockComment = false;

        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            String body = null;

            if (style == Style.HASH) {
                if (trimmed.startsWith("#") && !trimmed.startsWith("#!")) {
                    body = trimmed.substring(1);
                }
            } else if (inBlockComment) {
                body = trimmed;
                int end = body.indexOf("*/");
                if (end >= 0) {
                    body = body.substring(0, end);
                    inBlockComment = false;
                }
                if (body.startsWith("*")) {
                    body = body.substring(1);
                }
            } else if (trimmed.startsWith("/*")) {
                body = trimmed.substring(trimmed.startsWith("/**") ? 3 : 2);
                int end = body.indexOf("*/");
                if (end >= 0) {
                    body = body.substring(0, end);
                } else {
                    inBlockComment = true;
                }
            } else if (trimmed.startsWith("//")) {
                body = trimmed.substring(2);
            }

            if (body == null) {
                current = null;
                continue;
            }
            if (body.startsWith(" ")) {
                body = body.substring(1);
            }
            if (current == null) {
                current = new ArrayList<>();
                blocks.add(current);
            }
            current.add(new Line(file, i + 1, body));
        }
        return blocks;
    }

    private static void checkBlock(List<Line> block) {
        List<Line> paragraph = new ArrayList<>();
        for (Line line : block) {
            checkLine(line);
            boolean breakLine = line.code || line.text.isEmpty()
                    || RULE_LINE.matcher(line.text).matches();
            if (breakLine || LIST_ITEM.matcher(line.text).matches()) {
                checkParagraph(paragraph);
                paragraph.clear();
            }
            if (!breakLine) {
                paragraph.add(line);
            }
        }
        checkParagraph(paragraph);
    }

    private static void checkLine(Line line) {
        Matcher version = APP_VERSION.matcher(line.text);
        if (version.find()) {
            fault(line, "version number " + version.group());
        }
        if (line.code) {
            return;
        }
        String prose = withoutCode(line.text);
        if (prose.indexOf(';') >= 0) {
            fault(line, "semicolon");
        }
        Matcher contraction = CONTRACTION.matcher(prose);
        if (contraction.find()) {
            fault(line, "contraction \"" + contraction.group() + "\"");
        }
    }

    // Checks the banned words and the length of each sentence. A banned
    // phrase can run over two lines, thus the check reads the joined text.
    private static void checkParagraph(List<Line> paragraph) {
        if (paragraph.isEmpty()) {
            return;
        }
        StringBuilder joined = new StringBuilder();
        for (Line line : paragraph) {
            joined.append(withoutCode(line.text)).append(' ');
        }
        Line first = paragraph.get(0);
        String text = joined.toString().trim();

        Matcher banned = BANNED.matcher(text);
        while (banned.find()) {
            fault(first, "banned word \"" + banned.group() + "\"");
        }
        for (String sentence : SENTENCE_END.split(text)) {
            int words = wordCount(sentence);
            if (words > MAX_WORDS) {
                fault(first, words + " words in one sentence: " + shorten(sentence));
            }
        }
    }

    private static int wordCount(String sentence) {
        int count = 0;
        for (String token : sentence.trim().split("\\s+")) {
            if (token.matches(".*[\\p{L}\\p{N}].*")) {
                count++;
            }
        }
        return count;
    }

    private static String withoutCode(String text) {
        return CODE_SPAN.matcher(text).replaceAll("CODE");
    }

    private static String shorten(String text) {
        return text.length() <= 70 ? text : text.substring(0, 70) + "...";
    }

    private static void fault(Line line, String message) {
        faults++;
        System.out.println("FAIL " + root.relativize(line.file) + ":" + line.number + ": " + message);
    }
}
