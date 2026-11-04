package com.github.oogasawa.slmime;

import java.nio.file.Paths;
import java.util.List;

/**
 * Builds the predictions from real conversation logs and prints how many texts they keep, how long it took, the
 * memory used, and the predictions for some readings. Not a test: it needs the logs and the dictionary.
 *
 * <pre>java -cp ... com.github.oogasawa.slmime.PredictionCheck LOG_DIR SYSTEM_DIC [reading ...]</pre>
 */
public final class PredictionCheck {

    public static void main(String[] args) throws Exception {
        long start = System.currentTimeMillis();
        List<ConversationLogs.Said> said = new ConversationLogs(Paths.get(args[0])).newTexts();
        long read = System.currentTimeMillis();
        long users = said.stream().filter(ConversationLogs.Said::byUser).count();
        long chars = said.stream().mapToLong(s -> s.text().length()).sum();
        System.out.printf("texts %d (by the user %d), %d characters, read in %d ms%n", said.size(), users, chars,
                read - start);
        Predictions predictions = new Predictions();
        try (Readings readings = new Readings(Paths.get(args[1]))) {
            for (ConversationLogs.Said s : said) {
                predictions.learn(readings.words(s.text()), PredictionService.WEIGHT, s.when());
            }
        }
        long built = System.currentTimeMillis();
        System.gc();
        Runtime rt = Runtime.getRuntime();
        System.out.printf("kept %d texts in %d ms; heap used %d MB%n", predictions.size(), built - read,
                (rt.totalMemory() - rt.freeMemory()) >> 20);
        long now = System.currentTimeMillis();
        for (int i = 2; i < args.length; i++) {
            long t0 = System.nanoTime();
            List<String> found = predictions.predict(args[i], 5, now);
            System.out.printf("%s (%d us): %s%n", args[i], (System.nanoTime() - t0) / 1000, found);
        }
    }
}
