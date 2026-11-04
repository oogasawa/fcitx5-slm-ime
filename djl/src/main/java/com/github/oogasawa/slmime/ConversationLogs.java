package com.github.oogasawa.slmime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
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
 * message is {@code QUESTION: <what the user wrote>} and then {@code ANSWER: <what the model answered>}. A file is
 * never opened where it is: one that changed since it was read last is copied, and the copy is read, so that a
 * program writing the file, running or started meanwhile, is not touched. A file is read from the rows after those
 * read last time, and a turn is taken once however many files hold it (the merged file of AI-workspace holds the
 * turns of the others). Where each file was read to, and the turns taken, are kept in a file of their own
 * ({@link #saveState}), so that the files are not read again from the start after a restart.</p>
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

    /** How far a file was read: its last row read, and its size and time when it was read. */
    private record Place(long lastRow, long size, long modified) {
    }

    private final Path dir;
    private final Map<Path, Place> places = new HashMap<>();
    /** The turns taken, by the hash of their message. */
    private final Set<Integer> taken = new HashSet<>();

    ConversationLogs(Path dir) {
        this.dir = dir;
    }

    /** The texts of the turns recorded since the last call (all of them at the first call). */
    List<Said> newTexts() {
        List<Said> said = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return said;
        }
        for (Path file : files()) {
            try {
                BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class);
                Place place = places.get(file);
                long modified = a.lastModifiedTime().toMillis();
                if (place != null && place.size() == a.size() && place.modified() == modified) {
                    continue;
                }
                long lastRow = readCopy(file, place == null ? -1 : place.lastRow(), said);
                places.put(file, new Place(lastRow, a.size(), modified));
            } catch (IOException | SQLException e) {
                // A copy made while the file was being written may not open: it is read again next time
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

    /** Reads the rows after {@code after} of a copy of {@code file}; the last row read. */
    private long readCopy(Path file, long after, List<Said> said) throws IOException, SQLException {
        Path tmp = Files.createTempDirectory("slm-ime-log",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path copy = tmp.resolve("log.mv.db");
        try {
            Files.copy(file, copy, StandardCopyOption.REPLACE_EXISTING);
            String url = "jdbc:h2:" + tmp.resolve("log") + ";ACCESS_MODE_DATA=r;IFEXISTS=TRUE";
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
            return after;
        } finally {
            try (Stream<Path> left = Files.list(tmp)) {
                for (Path p : left.toList()) {
                    Files.deleteIfExists(p);
                }
            }
            Files.deleteIfExists(tmp);
        }
    }

    /** The user's text and the model's text of one turn. */
    static void split(String message, long when, List<Said> said) {
        int answer = message.indexOf("\n" + ANSWER);
        if (answer < 0) {
            answer = message.indexOf(ANSWER);
        }
        String question = message.substring(QUESTION.length(), answer < 0 ? message.length() : answer);
        String user = question.strip();
        if (!user.isEmpty()) {
            said.add(new Said(user, true, when));
        }
        if (answer >= 0) {
            String model = message.substring(message.indexOf(ANSWER, answer) + ANSWER.length()).strip();
            if (!model.isEmpty()) {
                said.add(new Said(model, false, when));
            }
        }
    }

    /** Writes where each file was read to and the turns taken: {@code file TAB path TAB row TAB size TAB time} and
     *  {@code turn TAB hash} lines. */
    void saveState(Path file) throws IOException {
        List<String> lines = new ArrayList<>();
        places.forEach((p, place) -> lines.add("file\t" + p + "\t" + place.lastRow() + "\t" + place.size() + "\t"
                + place.modified()));
        taken.forEach(h -> lines.add("turn\t" + h));
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.write(tmp, lines, StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Reads what {@link #saveState} wrote, if the file is there. */
    void loadState(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String[] f = line.split("\t");
            try {
                if (f.length == 5 && f[0].equals("file")) {
                    places.put(Paths.get(f[1]), new Place(Long.parseLong(f[2]), Long.parseLong(f[3]),
                            Long.parseLong(f[4])));
                } else if (f.length == 2 && f[0].equals("turn")) {
                    taken.add(Integer.parseInt(f[1]));
                }
            } catch (NumberFormatException e) {
                // skipped
            }
        }
    }
}
