package com.maparts.link.command;

import com.maparts.link.capture.FrameDataWaiter;
import com.maparts.link.capture.MapGridCapture;
import com.maparts.link.config.TokenStore;
import com.maparts.link.http.BackendClient;
import com.maparts.link.util.Links;
import com.maparts.link.util.ModChat;
import com.maparts.link.util.TitleGuesser;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * /mapuploadall — looks at a wall holding several maparts, splits it into
 * separate arts automatically (map item names first, then color continuity
 * across frame seams), and uploads every piece as its own draft. Titles are
 * guessed per art from the map names.
 */
public final class MapUploadAllCommand {
    private MapUploadAllCommand() {}

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
                ClientCommandManager.literal("mapuploadall")
                        .executes(context -> run(context.getSource())));
    }

    private static int run(FabricClientCommandSource source) {
        MinecraftClient client = source.getClient();
        String token = TokenStore.load();

        if (token == null) {
            ModChat.error(source, Text.literal(
                    "Link your account first: get a code on the website's login page"
                            + " and run /maplink <code>."));
            ModChat.feedback(source,
                    Links.prefixedLink("→ ", "Open the sign-in page",
                            LinkCommand.BACKEND_URL + "/login"));
            return 0;
        }

        if (FrameDataWaiter.isBusy()) {
            ModChat.error(source, Text.literal(
                    "Still processing a previous capture — wait for it to finish."));
            return 0;
        }

        ModChat.feedback(source, Text.literal("Scanning the wall…").formatted(Formatting.GRAY));

        MapGridCapture.captureAll(client, wall -> {
            if (wall.failed()) {
                ModChat.error(source, Text.literal(wall.error()));
                return;
            }

            List<MapGridCapture.Result> segments = wall.segments();

            StringBuilder summary = new StringBuilder(
                    "Found " + segments.size() + " mapart"
                            + (segments.size() == 1 ? "" : "s") + " on this wall: ");

            for (int i = 0; i < segments.size(); i++) {
                MapGridCapture.Result segment = segments.get(i);
                String title = title(segment);
                summary.append(i > 0 ? ", " : "")
                        .append(segment.mapsWide()).append("x").append(segment.mapsTall());

                if (title != null) {
                    summary.append(" \"").append(title).append("\"");
                }
            }

            if (wall.skippedIrregular() > 0) {
                summary.append(" (").append(wall.skippedIrregular())
                        .append(" irregular group")
                        .append(wall.skippedIrregular() == 1 ? "" : "s")
                        .append(" skipped — use /mapselect for those)");
            }

            summary.append(". Uploading…");
            ModChat.feedback(source, Text.literal(summary.toString()).formatted(Formatting.GRAY));

            BackendClient backend = new BackendClient(LinkCommand.BACKEND_URL);
            AtomicInteger uploaded = new AtomicInteger();
            AtomicInteger failed = new AtomicInteger();

            // Sequential on purpose: one draft/upload/finalize at a time keeps
            // the load on the site (and the player's connection) gentle.
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);

            for (MapGridCapture.Result segment : segments) {
                String title = title(segment);
                String label = segment.mapsWide() + "x" + segment.mapsTall()
                        + (title != null ? " \"" + title + "\"" : "");

                chain = chain.thenCompose(ignored ->
                        UploadPipeline.upload(backend, token, title, segment,
                                        () -> client.execute(() -> ModChat.feedback(source,
                                                Text.literal(label + ": upload failed —"
                                                                + " retrying in 5s…")
                                                        .formatted(Formatting.GRAY))))
                                .thenAccept(outcome -> client.execute(() -> {
                                    if (outcome.success()) {
                                        uploaded.incrementAndGet();
                                        ModChat.feedback(source, Text.literal(
                                                outcome.message() != null
                                                        ? label + ": " + outcome.message()
                                                        : "Uploaded " + label + ".")
                                                .formatted(Formatting.GREEN));
                                    } else {
                                        failed.incrementAndGet();
                                        ModChat.error(source, Text.literal(
                                                label + ": " + outcome.message()));
                                    }

                                    if (outcome.url() != null) {
                                        ModChat.feedback(source, Links.prefixedLink(
                                                "   → ", outcome.urlLabel(), outcome.url()));
                                    }
                                })));
            }

            chain.thenRun(() -> client.execute(() -> {
                ModChat.feedback(source, Text.literal(
                        "Done: " + uploaded.get() + " uploaded"
                                + (failed.get() > 0 ? ", " + failed.get() + " failed" : "")
                                + ".").formatted(Formatting.GOLD));
                ModChat.feedback(source, Links.prefixedLink(
                        "→ ", "Review your drafts",
                        LinkCommand.BACKEND_URL + "/dashboard/maparts"));
            }));
        });

        return Command.SINGLE_SUCCESS;
    }

    private static String title(MapGridCapture.Result segment) {
        String guessed = TitleGuesser.guess(segment.tiles());

        if (guessed != null && guessed.length() > MapUploadCommand.MAX_TITLE_LENGTH) {
            return guessed.substring(0, MapUploadCommand.MAX_TITLE_LENGTH);
        }

        return guessed;
    }
}
