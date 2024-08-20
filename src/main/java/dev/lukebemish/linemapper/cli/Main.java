package dev.lukebemish.linemapper.cli;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

@CommandLine.Command(name = "linemapper", mixinStandardHelpOptions = true, description = "Map line numbers in bytecode given vineflower output")
public class Main implements Runnable {
    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    @CommandLine.Option(names = "--input", description = "Input jar", required = true)
    Path input;

    @CommandLine.Option(names = "--output", description = "Output jar", required = true)
    Path output;

    @CommandLine.Option(names = "--vineflower", description = "Vineflower output", arity = "*")
    List<Path> vineflowerPaths = List.of();

    @CommandLine.Option(names = "--patches", description = "Patch archive files", arity = "*")
    List<Path> patchPaths = List.of();

    @CommandLine.Option(names = "--batch-size", description = "How many class files to process at once")
    int batchSize = Runtime.getRuntime().availableProcessors();

    public static void main(String[] args) {
        var exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        input = input.toAbsolutePath();
        output = output.toAbsolutePath();
        Map<String, Map<Integer, Integer>> lineMappings = new HashMap<>();
        Map<String, PatchFile> patches = new HashMap<>();

        for (var vineflower : vineflowerPaths) {
            vineflower = vineflower.toAbsolutePath();
            try (var is = Files.newInputStream(vineflower);
                 var zis = new ZipInputStream(is)) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.getName().endsWith(".java")) {
                        Map<Integer, Integer> lines = getLineMap(entry.getExtra());
                        if (lines != null) {
                            lineMappings.put(entry.getName().substring(0, entry.getName().length()-5), lines);
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        for (var patch : patchPaths) {
            patch = patch.toAbsolutePath();
            try (var is = Files.newInputStream(patch);
                 var zis = new ZipInputStream(is)) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.getName().endsWith(".java.patch")) {
                        var bytes = zis.readAllBytes();
                        var lines = new String(bytes, StandardCharsets.UTF_8).lines().toList();
                        var name = entry.getName().substring(0, entry.getName().length()-".java.patch".length());
                        if (patches.containsKey(name)) {
                            LOGGER.warn("Duplicate patch file for class {}; choosing patch from {}", name, patch);
                        }
                        patches.put(name, PatchFile.fromLines(entry.getName(), lines));
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        try {
            Files.createDirectories(output.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try (var is = Files.newInputStream(input);
             var os = Files.newOutputStream(output);
             var zis = new ZipInputStream(is);
             var zos = new ZipOutputStream(os)) {
            Entry[] entries = new Entry[batchSize];
            byte[][] labelled = new byte[batchSize][];
            Future<?>[] futures = new Future[batchSize];
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                int i = 0;
                while (i < batchSize && entry != null) {
                    var bytes = new ByteArrayOutputStream();
                    zis.transferTo(bytes);
                    entries[i] = new Entry(entry, bytes.toByteArray());
                    i++;
                    if (i < batchSize) {
                        entry = zis.getNextEntry();
                    }
                }
                for (; i < batchSize; i++) {
                    entries[i] = null;
                    labelled[i] = new byte[0];
                }

                mapEntries(lineMappings, patches, labelled, entries, futures);

                for (int j = 0; j < batchSize; j++) {
                    var entryIn = entries[j];
                    if (entryIn == null) {
                        continue;
                    }
                    ZipEntry zipEntry = entryIn.entry();
                    var newEntry = new ZipEntry(zipEntry.getName());
                    if (zipEntry.getExtra() != null) {
                        newEntry.setExtra(zipEntry.getExtra());
                    }
                    if (zipEntry.getLastAccessTime() != null) {
                        newEntry.setLastAccessTime(zipEntry.getLastAccessTime());
                    }
                    if (zipEntry.getLastModifiedTime() != null) {
                        newEntry.setLastModifiedTime(zipEntry.getLastModifiedTime());
                    }
                    if (zipEntry.getCreationTime() != null) {
                        newEntry.setCreationTime(zipEntry.getCreationTime());
                    }
                    if (zipEntry.getComment() != null) {
                        newEntry.setComment(zipEntry.getComment());
                    }
                    byte[] bytes = labelled[j];
                    zos.putNextEntry(newEntry);
                    zos.write(bytes);
                    zos.closeEntry();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void mapEntries(Map<String, Map<Integer, Integer>> mappings, Map<String, PatchFile> patches, byte[][] labelled, Entry[] entries, Future<?>[] futures) {
        for (int i = 0; i < batchSize; i++) {
            var entry = entries[i];
            if (entry == null) {
                continue;
            }
            var number = i;
            futures[i] = executorService.submit(() -> {
                if (entry.entry().getName().endsWith(".class")) {
                    var name = entry.entry().getName().substring(0, entry.entry().getName().length()-".class".length());
                    var lines = mappings.get(name);
                    var patchName = name;
                    var innerIndex = patchName.indexOf('$');
                    if (innerIndex != -1) {
                        patchName = patchName.substring(0, innerIndex);
                    }
                    var patch = patches.get(patchName);
                    labelled[number] = mapEntry(lines, patch, entry.contents());
                } else {
                    labelled[number] = entry.contents();
                }
            });
        }
        for (int i = 0; i < batchSize; i++) {
            try {
                futures[i].get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private byte[] mapEntry(Map<Integer, Integer> lines, PatchFile patch, byte[] contents) {
        if (lines == null && patch == null) {
            return contents;
        }
        var reader = new ClassReader(contents);
        var writer = new ClassWriter(0);
        Map<Integer, Integer> finalLines = lines == null ? Map.of() : lines;
        PatchFile finalPatch = patch == null ? PatchFile.EMPTY : patch;
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                    @Override
                    public void visitLineNumber(int line, Label start) {
                        var lineNumber = finalLines.getOrDefault(line, line);
                        lineNumber = finalPatch.remapLineNumber(lineNumber);
                        super.visitLineNumber(lineNumber, start);
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }

    private record Entry(ZipEntry entry, byte[] contents) {}

    private final ExecutorService executorService = Executors.newFixedThreadPool(batchSize);

    private static Map<Integer, Integer> getLineMap(byte[] extra) {
        if (extra == null) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(extra);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        while (buffer.hasRemaining() && buffer.getShort() != 0x4646) {
            var length = buffer.getShort();
            buffer.position(buffer.position() + length);
        }
        if (buffer.remaining() == 0) {
            return null;
        }
        var length = buffer.getShort();
        if (buffer.get() != (byte) 1) {
            return null; // we don't know how to deal with other versions
        }
        Map<Integer, Integer> map = new HashMap<>();
        for (int i = 0; i < (length-1)/2; i+=2) {
            map.put((int) buffer.getShort(), (int) buffer.getShort());
        }
        return map;
    }
}
