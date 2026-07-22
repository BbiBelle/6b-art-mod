package com.maparts.link.command;

import com.maparts.link.capture.FrameDataWaiter;
import com.maparts.link.capture.MapGridCapture;
import com.maparts.link.capture.SelectionState;
import com.maparts.link.config.TokenStore;
import com.maparts.link.http.BackendClient;
import com.maparts.link.util.Links;
import com.maparts.link.util.TitleGuesser;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import java.util.function.Consumer;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * /mapupload [title] — captures a framed mapart and uploads it to the website
 * as a draft. Uses the /mapselect two-corner selection when one is active,
 * otherwise flood-fills the wall from the frame under the crosshair. Without
 * a title argument, one is guessed from the maps' custom item names.
 */
public final class MapUploadCommand {
    static final int MAX_TITLE_LENGTH = 120;

    private MapUploadCommand() {}

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
                ClientCommandManager.literal("mapupload")
                        .executes(context -> run(context, null))
                        .then(ClientCommandManager
                                .argument("title", StringArgumentType.greedyString())
                                .executes(context -> run(
                                        context,
                                        StringArgumentType.getString(context, "title")))));
    }

    private static int run(CommandContext<FabricClientCommandSource> context, String title) {
        FabricClientCommandSource source = context.getSource();
        MinecraftClient client = source.getClient();

        String token = TokenStore.load();

        if (token == null) {
            source.sendError(Text.literal(
                    "Link your account first: get a code on the website's login page"
                            + " and run /maplink <code>."));
            source.sendFeedback(
                    Links.prefixedLink("→ ", "Open the sign-in page",
                            LinkCommand.BACKEND_URL + "/login"));
            return 0;
        }

        if (FrameDataWaiter.isBusy()) {
            source.sendError(Text.literal(
                    "Still processing a previous capture — wait for it to finish."));
            return 0;
        }

        source.sendFeedback(Text.literal("Scanning the wall…").formatted(Formatting.GRAY));

        captureSelectionOrCrosshair(client, source, capture -> {
            if (capture == null) {
                return;
            }

            if (capture.failed()) {
                source.sendError(Text.literal(capture.error()));
                return;
            }

            SelectionState.clear();

            String trimmedTitle = title == null ? null : title.trim();

            if (trimmedTitle == null || trimmedTitle.isEmpty()) {
                // The maps' custom item names usually carry the intended title.
                String guessed = TitleGuesser.guess(capture.tiles());

                if (guessed != null) {
                    trimmedTitle = guessed;
                    source.sendFeedback(Text.literal(
                            "Using title \"" + guessed + "\" (from the map names).")
                            .formatted(Formatting.GRAY));
                }
            }

            String finalTitle = trimmedTitle != null && trimmedTitle.length() > MAX_TITLE_LENGTH
                    ? trimmedTitle.substring(0, MAX_TITLE_LENGTH)
                    : trimmedTitle;

            source.sendFeedback(Text.literal(
                    "Captured " + capture.mapsWide() + "x" + capture.mapsTall()
                            + " mapart — uploading…").formatted(Formatting.GRAY));

            BackendClient backend = new BackendClient(LinkCommand.BACKEND_URL);

            UploadPipeline.upload(backend, token, finalTitle, capture,
                            () -> client.execute(() -> source.sendFeedback(Text.literal(
                                    "Upload failed — retrying in 5s…")
                                    .formatted(Formatting.GRAY))))
                    .thenAccept(outcome -> client.execute(() -> {
                        if (outcome.success()) {
                            source.sendFeedback(Text.literal(
                                    outcome.message() != null
                                            ? outcome.message()
                                            : "Mapart uploaded as a draft.")
                                    .formatted(Formatting.GREEN));
                        } else {
                            source.sendError(Text.literal(outcome.message()));
                        }

                        if (outcome.url() != null) {
                            source.sendFeedback(Links.prefixedLink(
                                    "→ ", outcome.urlLabel(), outcome.url()));
                        }
                    }));
        });

        return Command.SINGLE_SUCCESS;
    }

    /**
     * Captures from the active /mapselect selection, or from the crosshair
     * when none is active. Invokes the callback with null after sending an
     * error itself when there's nothing to capture.
     */
    private static void captureSelectionOrCrosshair(
            MinecraftClient client, FabricClientCommandSource source,
            Consumer<MapGridCapture.Result> callback) {
        if (!SelectionState.hasSelection()) {
            if (SelectionState.hasPendingFirst()) {
                source.sendError(Text.literal(
                        "Your /mapselect selection is missing its second corner —"
                                + " select it, or look at the mapart and rerun"
                                + " /mapupload to skip selecting."));
                callback.accept(null);
                return;
            }

            MapGridCapture.capture(client, callback);
            return;
        }

        if (client.world == null) {
            SelectionState.clear();
            callback.accept(MapGridCapture.Result.error("No world loaded."));
            return;
        }

        Entity first = client.world.getEntityById(SelectionState.firstId());
        Entity second = client.world.getEntityById(SelectionState.secondId());

        if (!(first instanceof ItemFrameEntity firstFrame)
                || !(second instanceof ItemFrameEntity secondFrame)) {
            SelectionState.clear();
            source.sendError(Text.literal(
                    "The selected corner frames are gone (unloaded or removed) —"
                            + " reselect with /mapselect."));
            callback.accept(null);
            return;
        }

        MapGridCapture.captureCorners(client, firstFrame, secondFrame, callback);
    }
}
