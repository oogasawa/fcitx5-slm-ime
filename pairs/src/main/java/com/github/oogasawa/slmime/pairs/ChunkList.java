package com.github.oogasawa.slmime.pairs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/** The chunks still to be made into pairs, handed out one at a time. A chunk whose pairs are there is left out. */
public class ChunkList {

    private final Deque<Path> left = new ArrayDeque<>();

    public ChunkList(List<Path> chunks, Path outDir) {
        for (Path chunk : chunks) {
            if (!Files.exists(MakePairs.pairsOf(chunk, outDir))) {
                left.add(chunk);
            }
        }
    }

    public Optional<Path> next() {
        return Optional.ofNullable(left.poll());
    }

    public int size() {
        return left.size();
    }
}
