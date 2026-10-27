package com.github.oogasawa.slmime;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
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

    /** @throws UnsupportedOperationException if {@code libanthy} cannot be loaded */
    public AnthyCandidates() {
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

    /** Anthy, or {@link CandidateSource#NONE} with a warning if it cannot be loaded. */
    public static CandidateSource loadOrNone() {
        try {
            return new AnthyCandidates();
        } catch (UnsupportedOperationException | LinkageError e) {
            log.warning("Anthy is not available, bunsetsu get the model's candidate only: " + e.getMessage());
            return NONE;
        }
    }

    @Override
    public synchronized List<String> candidates(String reading, int limit) {
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
            if (joined.length() > 0) {
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
                if (written > 0) {
                    found.add(buffer.getString(0));
                }
            }
            return new ArrayList<>(found);
        } catch (Throwable e) {
            log.warning("Anthy failed for '" + reading + "': " + e);
            return List.of();
        }
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
