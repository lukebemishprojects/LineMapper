package dev.lukebemish.linemapper.cli;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class PatchFile {
    public static final PatchFile EMPTY = new PatchFile(List.of());
    private static final Pattern HUNK_OFFSET = Pattern.compile("@@ -(\\d+),(\\d+) \\+([_\\d]+),(\\d+) @@");

    private PatchFile(List<Offset> offsets) {
        this.offsets = offsets;
    }

    private record Offset(int initial, int eventual) implements Comparable<Offset> {
        @Override
        public int compareTo(@NotNull PatchFile.Offset o) {
            return Integer.compare(initial, o.initial);
        }
    }

    private final List<Offset> offsets;

    public int remapLineNumber(int lineNumber) {
        int totalOffset = 0;
        for (Offset offset : offsets) {
            if (lineNumber < offset.initial) {
                return lineNumber + totalOffset;
            }
            totalOffset = offset.eventual - offset.initial;
        }
        return lineNumber + totalOffset;
    }

    public static PatchFile fromLines(String name, List<String> lines) {
        int initialLineNumber = 0;
        int eventualLineNumber = 0;
        int totalPatchDelta = 0;
        boolean justDidAddition = false;
        boolean justDidSubtraction = false;
        List<Offset> offsets = new ArrayList<>();
        for (String line : lines) {
            if (line.isEmpty()) {
                continue;
            }
            initialLineNumber++;
            eventualLineNumber++;
            switch (line.charAt(0)) {
                case '@' -> {
                    Matcher matcher = HUNK_OFFSET.matcher(line);
                    if (!matcher.find()) {
                        throw new IllegalArgumentException(String.format("Invalid patch line in '%s': '%s'", name, line));
                    }
                    var initialStart = Integer.parseInt(matcher.group(1)) - 1;
                    var initialLength = Integer.parseInt(matcher.group(2));
                    var eventualLength = Integer.parseInt(matcher.group(4));
                    initialLineNumber = initialStart;
                    eventualLineNumber = initialStart + totalPatchDelta;
                    totalPatchDelta += eventualLength - initialLength;
                }
                case '+' -> {
                    eventualLineNumber++;
                    justDidAddition = true;
                    justDidSubtraction = false;
                }
                case '-' -> {
                    eventualLineNumber--;
                    justDidSubtraction = true;
                    justDidAddition = false;
                }
                default -> {
                    if (justDidAddition || justDidSubtraction) {
                        offsets.add(new Offset(initialLineNumber, eventualLineNumber));
                    }
                    justDidAddition = false;
                    justDidSubtraction = false;
                }
            }
        }
        return new PatchFile(offsets);
    }
}
