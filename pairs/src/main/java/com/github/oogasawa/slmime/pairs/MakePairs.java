package com.github.oogasawa.slmime.pairs;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Has a language model write the pairs of a reading and a sentence, both cut into phrasal units (bunsetsu), for the
 * chunks of sentences in a list. Several chunks are sent at a time, one by each maker; the work is waiting for the
 * server, which answers several requests at once.
 *
 * <p>Usage: {@code java -jar slm-ime-pairs.jar URL MODEL WORKERS ORDER_FILE OUT_DIR}. URL is the chat completions URL
 * of an OpenAI-compatible server; ORDER_FILE has one chunk file per line, written by WikiToChunks.java or
 * fineweb/make_chunks.py; OUT_DIR gets CHUNK_pairs.tsv for every chunk. A chunk whose pairs are there is skipped, so
 * the program can be run again after a failure.</p>
 */
public final class MakePairs {

    private MakePairs() {
    }

    /** The file of the pairs of a chunk. */
    static Path pairsOf(Path chunk, Path outDir) {
        String name = chunk.getFileName().toString().replaceFirst("\\.tsv$", "");
        return outDir.resolve(name + "_pairs.tsv");
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 5) {
            System.err.println("usage: MakePairs URL MODEL WORKERS ORDER_FILE OUT_DIR");
            System.exit(2);
        }
        String url = args[0];
        String model = args[1];
        if (url.isBlank() || model.isBlank()) {
            System.err.println("MakePairs: URL and MODEL must not be empty");
            System.exit(2);
        }
        int workers = Integer.parseInt(args[2]);
        List<Path> chunks = Files.readAllLines(Path.of(args[3])).stream()
                .filter(line -> !line.isBlank()).map(Path::of).toList();
        Path outDir = Path.of(args[4]);
        Files.createDirectories(outDir);

        ActorSystem system = new ActorSystem("pairs", workers);
        try {
            ActorRef<ChunkList> list = system.actorOf("chunks", new ChunkList(chunks, outDir));
            System.out.println("chunks to make: " + list.ask(ChunkList::size).join() + " of " + chunks.size());
            List<CompletableFuture<Integer>> makers = new ArrayList<>();
            for (int i = 1; i <= workers; i++) {
                ActorRef<ChunkMaker> maker = system.actorOf("maker-" + i, new ChunkMaker(url, model, outDir, "w" + i));
                makers.add(maker.ask(m -> m.drain(list)));
            }
            int made = makers.stream().mapToInt(CompletableFuture::join).sum();
            System.out.println("chunks made: " + made + ", left: " + list.ask(ChunkList::size).join());
        } finally {
            system.terminate();
        }
    }
}
