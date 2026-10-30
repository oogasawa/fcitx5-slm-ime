package com.github.oogasawa.slmime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccessGuardTest {

    @Test
    void onlyTheTokenAndThisMachineAreLetThrough() {
        AccessGuard guard = new AccessGuard("abc123", 8190);
        assertTrue(guard.allows("localhost:8190", "abc123"));
        assertTrue(guard.allows("127.0.0.1:8190", "abc123"));
        assertTrue(guard.allows("[::1]:8190", "abc123"));
        assertTrue(guard.allows("LOCALHOST", "abc123"));
        assertFalse(guard.allows("localhost:8190", null), "no token");
        assertFalse(guard.allows("localhost:8190", ""), "empty token");
        assertFalse(guard.allows("localhost:8190", "abc124"), "another token");
        assertFalse(guard.allows("localhost:8190", "abc1234"), "a longer token");
        assertFalse(guard.allows("evil.example.com:8190", "abc123"), "a page that renamed its address");
        assertFalse(guard.allows("localhost:8191", "abc123"), "another port");
        assertFalse(guard.allows(null, "abc123"), "no Host");
    }

    @Test
    void theTokenFileIsTheUsersOnlyAndNewAtEveryStart(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("slm-ime").resolve("server-token");
        String first = AccessGuard.writeNewToken(file);
        assertEquals(64, first.length());
        assertEquals(first, Files.readString(file));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.getParent())));
        String second = AccessGuard.writeNewToken(file);
        assertNotEquals(first, second);
        assertEquals(second, Files.readString(file));
    }
}
