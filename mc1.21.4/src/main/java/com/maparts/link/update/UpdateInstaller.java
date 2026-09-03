package com.maparts.link.update;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;

/**
 * Downloads a release jar and swaps it in for this mod's own running jar
 * file. The new bytes land next to it as a temp file first, then get moved
 * into place — an atomic rename where the filesystem allows it, so a reader
 * never sees a half-written jar. This only prepares the file on disk;
 * Fabric won't pick it up until the next launch.
 */
public final class UpdateInstaller {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(60);

    public record InstallResult(boolean success, String message) {}

    private UpdateInstaller() {}

    public static CompletableFuture<InstallResult> installAsync(String downloadUrl) {
        Path jarPath = currentJarPath();

        if (jarPath == null) {
            return CompletableFuture.completedFuture(new InstallResult(
                    false, "Could not locate the mod's own jar file to replace."));
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                // GitHub release assets 302 to objects.githubusercontent.com;
                // unlike the backend client, this is a plain GET with no body
                // to lose, so following is safe and required.
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(downloadUrl))
                .timeout(DOWNLOAD_TIMEOUT)
                .header("User-Agent", "maparts-link-updater")
                .GET()
                .build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> install(response, jarPath))
                .exceptionally(error -> new InstallResult(false, "Could not download the update."));
    }

    private static InstallResult install(HttpResponse<byte[]> response, Path jarPath) {
        if (response.statusCode() / 100 != 2) {
            return new InstallResult(false, "Download failed (HTTP " + response.statusCode() + ").");
        }

        byte[] bytes = response.body();

        if (bytes.length == 0) {
            return new InstallResult(false, "Downloaded file was empty.");
        }

        Path temp = null;

        try {
            temp = Files.createTempFile(jarPath.getParent(), "maparts-link-update-", ".jar");
            Files.write(temp, bytes);

            try {
                Files.move(temp, jarPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, jarPath, StandardCopyOption.REPLACE_EXISTING);
            }

            return new InstallResult(true, null);
        } catch (IOException e) {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // Best-effort cleanup only.
                }
            }

            return new InstallResult(false,
                    "Couldn't replace the mod file (it may be locked). Please download it manually.");
        }
    }

    /** Only resolves when this mod is loaded as a plain jar on disk, which is the normal case. */
    private static Path currentJarPath() {
        Optional<ModContainer> container = FabricLoader.getInstance().getModContainer("maparts_link");

        if (container.isEmpty()) {
            return null;
        }

        ModOrigin origin = container.get().getOrigin();

        if (origin.getKind() != ModOrigin.Kind.PATH) {
            return null;
        }

        List<Path> paths = origin.getPaths();
        return paths.isEmpty() ? null : paths.get(0);
    }
}
