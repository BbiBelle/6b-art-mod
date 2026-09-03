package com.maparts.link.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Looks up the newest GitHub release for this mod variant. Releases in this
 * repo are tagged per-variant (e.g. "mc1.21.4-v1.9.0"), so the tag
 * prefix below is what pins this check to the right variant's release
 * history even though every variant shares one repo.
 */
public final class UpdateChecker {
    private static final String REPO = "BbiBelle/6b-art-mod";
    private static final String TAG_PREFIX = "mc1.21.4-v";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    public record UpdateInfo(String version, String downloadUrl, String releaseUrl) {}

    private UpdateChecker() {}

    /** Resolves to null on any failure, or when already on the latest version. */
    public static CompletableFuture<UpdateInfo> checkAsync(String currentVersion) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.github.com/repos/" + REPO + "/releases?per_page=20"))
                .timeout(TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "maparts-link-updater")
                .GET()
                .build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> response.statusCode() == 200
                        ? parse(response.body(), currentVersion)
                        : null)
                .exceptionally(error -> null);
    }

    private static UpdateInfo parse(String body, String currentVersion) {
        try {
            JsonArray releases = JsonParser.parseString(body).getAsJsonArray();

            for (JsonElement element : releases) {
                JsonObject release = element.getAsJsonObject();

                if (isTrue(release, "draft") || isTrue(release, "prerelease")) {
                    continue;
                }

                String tag = release.has("tag_name") ? release.get("tag_name").getAsString() : null;

                if (tag == null || !tag.startsWith(TAG_PREFIX)) {
                    continue;
                }

                // Releases come back newest-first, so the first tag matching
                // this variant's prefix IS the latest for this variant —
                // settle it right here whether or not it's actually newer.
                String version = tag.substring(TAG_PREFIX.length());

                if (!isNewer(version, currentVersion)) {
                    return null;
                }

                String downloadUrl = findJarAsset(release);

                if (downloadUrl == null) {
                    return null;
                }

                String releaseUrl = release.has("html_url") ? release.get("html_url").getAsString() : null;
                return new UpdateInfo(version, downloadUrl, releaseUrl);
            }
        } catch (JsonSyntaxException | IllegalStateException e) {
            return null;
        }

        return null;
    }

    private static String findJarAsset(JsonObject release) {
        if (!release.has("assets")) {
            return null;
        }

        for (JsonElement element : release.getAsJsonArray("assets")) {
            JsonObject asset = element.getAsJsonObject();
            String name = asset.has("name") ? asset.get("name").getAsString() : "";

            if (name.endsWith(".jar") && asset.has("browser_download_url")) {
                return asset.get("browser_download_url").getAsString();
            }
        }

        return null;
    }

    private static boolean isTrue(JsonObject object, String field) {
        return object.has(field) && !object.get(field).isJsonNull() && object.get(field).getAsBoolean();
    }

    private static boolean isNewer(String remote, String current) {
        int[] r = versionParts(remote);
        int[] c = versionParts(current);
        int length = Math.max(r.length, c.length);

        for (int i = 0; i < length; i++) {
            int rv = i < r.length ? r[i] : 0;
            int cv = i < c.length ? c[i] : 0;

            if (rv != cv) {
                return rv > cv;
            }
        }

        return false;
    }

    private static int[] versionParts(String version) {
        String[] segments = version.split("\\.");
        int[] parts = new int[segments.length];

        for (int i = 0; i < segments.length; i++) {
            String digits = segments[i].replaceAll("[^0-9]", "");
            parts[i] = digits.isEmpty() ? 0 : Integer.parseInt(digits);
        }

        return parts;
    }
}
