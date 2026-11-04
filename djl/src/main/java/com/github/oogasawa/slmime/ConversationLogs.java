package com.github.oogasawa.slmime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * The text of the conversations that quarkus-chat-ui and chat-ui-with-audit-trail recorded, read for the predictions
 * ({@link Predictions}): what the user wrote and what the model answered, in the H2 files
 * {@code chat-ui-iolog-<port>.mv.db} under a directory.
 *
 * <p>Each turn of a conversation is a row of the table {@code logs} labelled {@code turn<n>/conversation}, whose
 * message is {@code QUESTION: <what the user wrote>} and then {@code ANSWER: <what the model answered>}. The files
 * are opened with {@code AUTO_SERVER=TRUE}, as the programs that write them open them, so that a file in use can be
 * read; nothing is written. A file is read again only from the rows after those read last time, and a turn is
 * taken once however many files hold it (the merged file of AI-workspace holds the turns of the others).</p>
 */
final class ConversationLogs {

    private static final Logger log = Logger.getLogger(ConversationLogs.class.getName());

    /** One text of a turn: what the user wrote ({@code byUser}) or what the model answered. */
    record Said(String text, boolean byUser, long when) {
    }

    private static final String QUESTION = "QUESTION:";
    private static final String ANSWER = "ANSWER:";
    /** How deep under the directory the files are looked for. */
    private static final int DEPTH = 3;

    private final Path dir;
    /** The last row read of each file. */
    private final Map<Path, Long> lastRead = new HashMap<>();
    /** The turns taken, by the hash of their message. */
    private final Set<Integer> taken = new HashSet<>();

    ConversationLogs(Path dir) {
        this.dir = dir;
    }

    /** The texts of the turns recorded since the last call (all of them at the first call). */
    List<Said> newTexts() {
        List<Said> said = new ArrayList<>();
        for (Path file : files()) {
            try {
                read(file, said);
            } catch (SQLException e) {
                log.warning("cannot read the conversation log " + file + ": " + e.getMessage());
            }
        }
        return said;
    }

    private List<Path> files() {
        try (Stream<Path> found = Files.find(dir, DEPTH, (p, a) -> a.isRegularFile()
                && p.getFileName().toString().startsWith("chat-ui-iolog-")
                && p.getFileName().toString().endsWith(".mv.db"))) {
            return found.sorted().toList();
        } catch (IOException e) {
            log.warning("cannot look for conversation logs in " + dir + ": " + e.getMessage());
            return List.of();
        }
    }

    private void read(Path file, List<Said> said) throws SQLException {
        String name = file.toAbsolutePath().toString();
        String url = "jdbc:h2:" + name.substring(0, name.length() - ".mv.db".length()) + ";AUTO_SERVER=TRUE;IFEXISTS=TRUE";
        long after = lastRead.getOrDefault(file, -1L);
        try (Connection connection = new org.h2.Driver().connect(url, new Properties());
             PreparedStatement query = connection.prepareStatement(
                     "SELECT ID, TIMESTAMP, MESSAGE FROM LOGS WHERE ID > ? AND LABEL LIKE 'turn%/conversation' "
                             + "AND MESSAGE LIKE 'QUESTION:%' ORDER BY ID")) {
            query.setLong(1, after);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    after = rows.getLong(1);
                    String message = rows.getString(3);
                    if (message == null || !taken.add(message.hashCode())) {
                        continue;
                    }
                    long when = rows.getTimestamp(2) == null ? 0 : rows.getTimestamp(2).getTime();
                    split(message, when, said);
                }
            }
        }
        lastRead.put(file, after);
    }

    /** The user's text and the model's text of one turn. */
    static void split(String message, long when, List<Said> said) {
        int answer = message.indexOf("\n" + ANSWER);
        if (answer < 0) {
            answer = message.indexOf(ANSWER);
        }
        String question = message.substring(QUESTION.length(), answer < 0 ? message.length() : answer);
        String user = ownWords(question);
        if (!user.isBlank()) {
            said.add(new Said(user, true, when));
        }
        if (answer >= 0) {
            String model = message.substring(message.indexOf(ANSWER, answer) + ANSWER.length()).strip();
            if (!model.isEmpty()) {
                said.add(new Said(model, false, when));
            }
        }
    }

    /**
     * What the user wrote in a question, without the text of the model that the user quoted: the user puts
     * {@code <=} (or ＜＝) after a quoted passage and writes the reply after it, so in a question that has the mark,
     * the user's own words are the rest of each line after a mark. A question without the mark is the user's own.
     */
    static String ownWords(String question) {
        if (!question.contains("<=") && !question.contains("＜＝")) {
            return question.strip();
        }
        StringBuilder own = new StringBuilder();
        for (String line : question.split("\n")) {
            int at = Math.max(line.lastIndexOf("<="), line.lastIndexOf("＜＝"));
            if (at >= 0) {
                own.append(line.substring(at + 2).strip()).append('\n');
            }
        }
        return own.toString().strip();
    }
}
