package com.github.oogasawa.slmime;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the predictions from a file of texts in the form of the inbox ({@link LearnInbox}) and prints how many
 * texts they keep, how long it took, the memory used, and the predictions for some readings. Not a test: it needs
 * the texts and the dictionary.
 *
 * <pre>java -cp ... com.github.oogasawa.slmime.PredictionCheck TEXTS.jsonl SYSTEM_DIC [reading ...]</pre>
 */
public final class PredictionCheck {

    public static void main(String[] args) throws Exception {
        long start = System.currentTimeMillis();
        List<LearnInbox.Text> texts = new ArrayList<>();
        for (String line : Files.readAllLines(Paths.get(args[0]), StandardCharsets.UTF_8)) {
            LearnInbox.Text t = LearnInbox.parse(line, start);
            if (t != null) {
                texts.add(t);
            }
        }
        long chars = texts.stream().mapToLong(t -> t.text().length()).sum();
        Predictions predictions = new Predictions();
        try (Readings readings = new Readings(Paths.get(args[1]))) {
            for (LearnInbox.Text t : texts) {
                predictions.learn(readings.words(t.text()), PredictionService.WEIGHT, t.when());
            }
        }
        long built = System.currentTimeMillis();
        System.gc();
        Runtime rt = Runtime.getRuntime();
        System.out.printf("texts %d, %d characters; kept %d texts in %d ms; heap used %d MB%n", texts.size(), chars,
                predictions.size(), built - start, (rt.totalMemory() - rt.freeMemory()) >> 20);
        long now = System.currentTimeMillis();
        for (int i = 2; i < args.length; i++) {
            long t0 = System.nanoTime();
            List<String> found = predictions.predict(args[i], 5, now);
            System.out.printf("%s (%d us): %s%n", args[i], (System.nanoTime() - t0) / 1000, found);
        }
    }
}
