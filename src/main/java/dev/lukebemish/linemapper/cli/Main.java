package dev.lukebemish.linemapper.cli;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
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
    @CommandLine.Option(names = "--input", description = "Input jar", required = true)
    Path input;
    
    @CommandLine.Option(names = "--output", description = "Output jar", required = true)
    Path output;
    
    @CommandLine.Option(names = "--vineflower", description = "Vineflower output", required = true)
    Path vineflower;

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
        vineflower = vineflower.toAbsolutePath();

        Map<String, Map<Integer, Integer>> mappings = new HashMap<>();
        try (var is = Files.newInputStream(vineflower);
             var zis = new ZipInputStream(is)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().endsWith(".java")) {
                    Map<Integer, Integer> lines = getLineMap(entry.getExtra());
                    if (lines != null) {
                        mappings.put(entry.getName().substring(0, entry.getName().length()-5), lines);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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

                mapEntries(mappings, labelled, entries, futures);

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

    private void mapEntries(Map<String, Map<Integer, Integer>> mappings, byte[][] labelled, Entry[] entries, Future<?>[] futures) {
        for (int i = 0; i < batchSize; i++) {
            var entry = entries[i];
            if (entry == null) {
                continue;
            }
            var number = i;
            futures[i] = executorService.submit(() -> {
                if (entry.entry().getName().endsWith(".class")) {
                    var lines = mappings.get(entry.entry().getName().substring(0, entry.entry().getName().length()-6));
                    if (lines != null) {
                        labelled[number] = mapEntry(lines, entry.contents());
                    } else {
                        labelled[number] = entry.contents();
                    }
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

    private byte[] mapEntry(Map<Integer, Integer> lines, byte[] contents) {
        var reader = new ClassReader(contents);
        var writer = new ClassWriter(0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                    @Override
                    public void visitLineNumber(int line, Label start) {
                        var lineNumber = lines.getOrDefault(line, line);
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
