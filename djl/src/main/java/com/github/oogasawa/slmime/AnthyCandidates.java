package com.github.oogasawa.slmime;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Candidates for one bunsetsu from Anthy, the dictionary-based converter, called through {@code libanthy}.
 *
 * <p>Anthy cuts the text it is given into bunsetsu by itself. The first candidate returned is Anthy's own
 * conversion of the whole reading, its bunsetsu joined: it reaches a word like 天気 even when the model gave a
 * reading of several words as one piece. The others are the candidates of the reading taken as one bunsetsu: the
 * first segment is stretched until it covers the whole reading, and its candidates are read.
 * One instance holds one Anthy context; calls are serialised.</p>
 */
public final class AnthyCandidates implements CandidateSource {

    private static final Logger log = Logger.getLogger(AnthyCandidates.class.getName());
    private static final int UTF8 = 2;
    private static final int BUFFER_BYTES = 1024;

    private final Arena arena = Arena.ofShared();
    private final MethodHandle setString;
    private final MethodHandle getStat;
    private final MethodHandle getSegmentStat;
    private final MethodHandle getSegment;
    private final MethodHandle resizeSegment;
    private final MemorySegment context;

    /** Anthy with the private dictionary of the user; @throws UnsupportedOperationException if {@code libanthy} cannot be loaded */
    public AnthyCandidates() {
        this(true);
    }

    /**
     * @param privateDictionary whether Anthy reads {@code ~/.anthy/private_words_default}; when it does not, Anthy
     *     is given an empty directory as its home, so that it has the dictionary it comes with only (and what it
     *     learns is thrown away with the directory)
     * @throws UnsupportedOperationException if {@code libanthy} cannot be loaded
     */
    public AnthyCandidates(boolean privateDictionary) {
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup lib = SymbolLookup.libraryLookup("libanthy.so.1", arena);
            MethodHandle init = handle(linker, lib, "anthy_init", FunctionDescriptor.of(ValueLayout.JAVA_INT));
            MethodHandle create = handle(linker, lib, "anthy_create_context", FunctionDescriptor.of(ValueLayout.ADDRESS));
            MethodHandle encoding = handle(linker, lib, "anthy_context_set_encoding",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            setString = handle(linker, lib, "anthy_set_string",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            getStat = handle(linker, lib, "anthy_get_stat",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            getSegmentStat = handle(linker, lib, "anthy_get_segment_stat",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS));
            getSegment = handle(linker, lib, "anthy_get_segment",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            resizeSegment = handle(linker, lib, "anthy_resize_segment",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT));
            if (!privateDictionary) {
                // Before anthy_init, which reads the dictionaries of the home directory
                MethodHandle override = handle(linker, lib, "anthy_conf_override",
                        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                override.invoke(arena.allocateFrom("HOME"), arena.allocateFrom(emptyHome().toString()));
            }
            if ((int) init.invoke() != 0) {
                throw new UnsupportedOperationException("anthy_init failed");
            }
            context = (MemorySegment) create.invoke();
            encoding.invoke(context, UTF8);
        } catch (UnsupportedOperationException e) {
            throw e;
        } catch (Throwable e) {
            throw new UnsupportedOperationException("libanthy cannot be used: " + e, e);
        }
    }

    /** An empty directory for the home of Anthy, removed when the server ends. */
    private static Path emptyHome() throws IOException {
        Path home = Files.createTempDirectory("slm-ime-anthy-home-");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteTree(home)));
        return home;
    }

    private static void deleteTree(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException | RuntimeException e) {
            // a directory left in /tmp is harmless
        }
    }

    /** Anthy with its private dictionary, or {@link CandidateSource#NONE} with a warning if it cannot be loaded. */
    public static CandidateSource loadOrNone() {
        return loadOrNone(DictionaryLevel.EXTENDED);
    }

    /** Anthy for a dictionary level, or {@link CandidateSource#NONE} with a warning if it cannot be loaded. */
    public static CandidateSource loadOrNone(DictionaryLevel level) {
        try {
            return new AnthyCandidates(level.privateDictionary());
        } catch (UnsupportedOperationException | LinkageError e) {
            log.warning("Anthy is not available, bunsetsu get the model's candidate only: " + e.getMessage());
            return NONE;
        }
    }

    @Override
    public List<String> candidates(String reading, int limit) {
        if (reading == null || reading.isEmpty() || limit <= 0) {
            return List.of();
        }
        try (Arena call = Arena.ofConfined()) {
            setString.invoke(context, call.allocateFrom(reading));
            MemorySegment stat = call.allocate(4);
            MemorySegment segmentStat = call.allocate(8);
            MemorySegment buffer = call.allocate(BUFFER_BYTES);
            Set<String> found = new LinkedHashSet<>();
            int own = segmentCount(stat);
            StringBuilder joined = new StringBuilder();
            for (int i = 0; i < own; i++) {
                int written = (int) getSegment.invoke(context, i, 0, buffer, BUFFER_BYTES);
                if (written > 0) {
                    joined.append(buffer.getString(0));
                }
            }
            if (joined.length() > 0 && !hasStraySmallKana(joined.toString())) {
                found.add(joined.toString());
            }
            int guard = reading.codePointCount(0, reading.length()) + 1;
            while (segmentCount(stat) > 1 && guard-- > 0) {
                int ignored = (int) resizeSegment.invoke(context, 0, 1);
            }
            if (segmentCount(stat) < 1) {
                return List.of();
            }
            getSegmentStat.invoke(context, 0, segmentStat);
            int available = segmentStat.get(ValueLayout.JAVA_INT, 0);
            for (int k = 0; k < available && found.size() < limit; k++) {
                int written = (int) getSegment.invoke(context, 0, k, buffer, BUFFER_BYTES);
                if (written > 0 && !hasStraySmallKana(buffer.getString(0))) {
                    found.add(buffer.getString(0));
                }
            }
            return new ArrayList<>(found);
        } catch (Throwable e) {
            log.warning("Anthy failed for '" + reading + "': " + e);
            return List.of();
        }
    }

    private static final String SMALL_KANA = "ぁぃぅぇぉゃゅょゎァィゥェォャュョヮ";

    /**
     * Whether a small kana (ゃ, ょ, ぁ, ...) in the text stands where no kana can carry it: at the start, or after a
     * kanji. Anthy gives such text when it converted only the start of a reading ("気死ゃ" for きしゃ); it is not a
     * word.
     */
    static boolean hasStraySmallKana(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (SMALL_KANA.indexOf(text.charAt(i)) >= 0 && (i == 0 || !isKana(text.charAt(i - 1)))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isKana(char c) {
        return (c >= 0x3041 && c <= 0x309F) || (c >= 0x30A0 && c <= 0x30FF);
    }

    private int segmentCount(MemorySegment stat) throws Throwable {
        int ignored = (int) getStat.invoke(context, stat);
        return stat.get(ValueLayout.JAVA_INT, 0);
    }

    private static MethodHandle handle(Linker linker, SymbolLookup lib, String name, FunctionDescriptor descriptor) {
        return linker.downcallHandle(lib.find(name).orElseThrow(
                () -> new UnsupportedOperationException(name + " not found in libanthy")), descriptor);
    }
}
