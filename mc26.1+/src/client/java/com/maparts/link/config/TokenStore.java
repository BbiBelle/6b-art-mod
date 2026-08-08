package com.maparts.link.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Persists the upload token the backend hands out when a challenge is
 * confirmed. The token authorizes mapart uploads as the linked user; linking
 * again (as any account) simply overwrites it.
 */
public final class TokenStore {
    private static final String FILE_NAME = "maparts-link.token";

    private TokenStore() {}

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    public static void save(String token) {
        try {
            Files.writeString(path(), token);
        } catch (IOException ignored) {
            // Uploads will ask the player to link again.
        }
    }

    public static String load() {
        Path file = path();

        if (!Files.exists(file)) {
            return null;
        }

        try {
            String token = Files.readString(file).trim();
            return token.isEmpty() ? null : token;
        } catch (IOException e) {
            return null;
        }
    }
}



