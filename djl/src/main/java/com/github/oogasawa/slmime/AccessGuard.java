package com.github.oogasawa.slmime;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Lets only the user's own engine use the server.
 *
 * <p>The server listens on this machine, where other accounts and the web pages a browser shows can send it
 * requests too: they could read conversions (with the user's choices in them) and write into the history. At every
 * start the server makes a new random token and writes it to a file that only the user can read; the engine sends
 * it in the header {@value #HEADER} with every request. A request without the token, or with a Host that is not this
 * machine (a page that renamed its own address to 127.0.0.1), is refused.
 */
final class AccessGuard extends Filter {

    static final String HEADER = "X-SLM-IME-Token";

    private final byte[] token;
    private final int port;

    AccessGuard(String token, int port) {
        this.token = token.getBytes(StandardCharsets.US_ASCII);
        this.port = port;
    }

    /** A new token of 32 random bytes in hex, written to {@code file} (mode 0600) in place of the old one. */
    static String writeNewToken(Path file) throws IOException {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        Path temporary = Files.createTempFile(dir, ".server-token", ".tmp",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(temporary, token, StandardCharsets.US_ASCII);
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return token;
    }

    /** Whether a request with these headers may go on. */
    boolean allows(String host, String givenToken) {
        if (!localHost(host, port) || givenToken == null) {
            return false;
        }
        return MessageDigest.isEqual(token, givenToken.getBytes(StandardCharsets.US_ASCII));
    }

    /** Whether the Host header names this machine: 127.0.0.1, localhost or [::1], with or without the port. */
    static boolean localHost(String host, int port) {
        if (host == null) {
            return false;
        }
        String name = host.trim().toLowerCase();
        String suffix = ":" + port;
        if (name.endsWith(suffix)) {
            name = name.substring(0, name.length() - suffix.length());
        }
        return name.equals("127.0.0.1") || name.equals("localhost") || name.equals("[::1]");
    }

    @Override
    public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
        var headers = exchange.getRequestHeaders();
        if (!allows(headers.getFirst("Host"), headers.getFirst(HEADER))) {
            exchange.getRequestBody().close();
            byte[] body = "{\"error\":\"forbidden\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(403, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
            return;
        }
        chain.doFilter(exchange);
    }

    @Override
    public String description() {
        return "the token of the user's engine";
    }
}
