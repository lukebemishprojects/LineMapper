package dev.lukebemish.linemapper.jst;

import com.intellij.psi.PsiFile;
import net.neoforged.jst.api.FileEntry;
import net.neoforged.jst.api.Logger;
import net.neoforged.jst.api.Replacement;
import net.neoforged.jst.api.Replacements;
import net.neoforged.jst.api.SourceTransformer;
import net.neoforged.jst.api.TransformContext;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class LineMapperTransformer implements SourceTransformer {
    private Logger logger;

    @CommandLine.Option(names = "--line-map-out", description = "The path to the file to write line mappings to")
    public Path lineMapOut;

    @Override
    public void visitFile(PsiFile psiFile, Replacements replacements) {
        // no-op
    }

    private final Map<String, List<LineMapping>> mappings = new ConcurrentHashMap<>();

    private record LineMapping(int original, int transformed) {}

    @Override
    public void beforeRun(TransformContext context) {
        this.logger = context.logger();
    }

    @Override
    public boolean beforeReplacement(FileEntry fileEntry, List<Replacement> replacements) {
        List<LineMapping> offsets = new ArrayList<>();
        var name = fileEntry.relativePath();
        if (name.endsWith(".java")) {
            name = name.substring(0, name.length() - 5);
        } else {
            return true;
        }
        String stringValue;
        try (var is = fileEntry.openInputStream()) {
            stringValue = new String(is.readAllBytes());
        } catch (Exception e) {
            logger.error("Failed to read file `" + name + "`: "+ e.getMessage());
            return false;
        }
        char[] chars = stringValue.toCharArray();
        int offset = 0;
        int line = 1;
        for (var replacement : replacements) {
            var start = replacement.range().getStartOffset();
            var end = replacement.range().getEndOffset();
            while (start > offset) {
                if (chars[offset] == '\n') {
                    line++;
                }
                offset++;
            }
            int originalLineBreakCount = 0;
            while (end > offset) {
                if (chars[offset] == '\n') {
                    originalLineBreakCount++;
                    line++;
                }
                offset++;
            }
            int newLineBreakCount = replacement.newText().split("\n").length - 1;
            if (originalLineBreakCount != newLineBreakCount) {
                offsets.add(new LineMapping(line, line + newLineBreakCount - originalLineBreakCount));
            }
        }
        if (!offsets.isEmpty()) {
            mappings.put(name, offsets);
        }
        return true;
    }

    @Override
    public boolean afterRun(TransformContext context) {
        try (var writer = Files.newBufferedWriter(lineMapOut, StandardCharsets.UTF_8)) {
            var entries = mappings.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
            for (var entry : entries) {
                var name = entry.getKey();
                var offsets = entry.getValue();
                writer.write("source "+name+"\n");
                for (var offset : offsets) {
                    writer.write(offset.original() + " -> "+offset.transformed()+"\n");
                }
            }
            writer.write("\n");
        } catch (IOException e) {
            logger.error("Failed to write line mappings: " + e.getMessage());
            return false;
        }
        return SourceTransformer.super.afterRun(context);
    }
}
