package com.maparts.link.command;

import com.maparts.link.config.TokenStore;
import com.maparts.link.http.BackendClient;
import com.maparts.link.util.Links;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class LinkCommand {
    // The mod is compiled and distributed per-deployment, so the backend it
    // talks to is fixed at build time rather than left player-editable. Must
    // be the primary domain, not the old .vercel.app host: that one
    // 301-redirects here now, and a redirected POST silently downgrades to
    // GET (dropping the body), which used to surface as a confusing 405.
    public static final String BACKEND_URL = "https://www.6b-art.com";

    // Matches the website's polling cadence and its challenge TTL, so this
    // gives up right around when the server would report the link expired.
    private static final long POLL_INTERVAL_MS = 2000;
    private static final long POLL_TIMEOUT_MS = 10 * 60 * 1000;

    private LinkCommand() {}

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
                ClientCommandManager.literal("maplink")
                        .then(ClientCommandManager
                                .argument("code", StringArgumentType.word())
                                .executes(LinkCommand::run)));
    }

    private static int run(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String code = StringArgumentType.getString(context, "code");

        MinecraftClient client = source.getClient();
        String username = client.getSession().getUsername();

        source.sendFeedback(Text.literal("Verifying your code…").formatted(Formatting.GRAY));

        BackendClient backend = new BackendClient(BACKEND_URL);

        backend.verify(code, username)
                .thenAccept(result -> client.execute(() -> {
                    if (!result.success()) {
                        source.sendError(Text.literal(result.message()));
                        return;
                    }

                    if (result.modToken() != null) {
                        // Legacy-backend fallback: the current backend never
                        // takes this path (passwords are mandatory now), but
                        // an older/rolled-back backend still might.
                        onLinked(source, username, result.created(), result.mcName(), result.modToken());
                        return;
                    }

                    // No token yet either way — claiming the username alone
                    // is never enough anymore. Both remaining cases still
                    // need polling: a brand-new account has to CREATE a
                    // password just as much as a returning one has to ENTER
                    // theirs, so don't key the wait on passwordRequired alone.
                    String instruction = result.passwordSetupRequired()
                            ? "Almost done — open 6b-art in your browser (the"
                                    + " tab you got the code in) and CREATE a"
                                    + " password to finish."
                            : "Almost done — open 6b-art in your browser and"
                                    + " ENTER your password to finish.";
                    source.sendFeedback(Text.literal(instruction).formatted(Formatting.YELLOW));
                    source.sendFeedback(Links.prefixedLink(
                            "→ ", "Finish signing in",
                            BACKEND_URL + "/login"));

                    schedulePasswordPoll(
                            source, client, backend, code, username,
                            System.currentTimeMillis() + POLL_TIMEOUT_MS);
                }));

        return Command.SINGLE_SUCCESS;
    }

    /** Polls roughly every 2 seconds for the mod token a password step on the
     * website mints, until it's confirmed, reported expired/errored, or the
     * overall wait times out. */
    private static void schedulePasswordPoll(
            FabricClientCommandSource source, MinecraftClient client, BackendClient backend,
            String code, String username, long deadlineMillis) {
        CompletableFuture.delayedExecutor(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
                .execute(() -> backend.pollChallengeToken(code).thenAccept(result -> client.execute(() -> {
                    switch (result.status()) {
                        case "confirmed" -> onLinked(source, username, false, username, result.modToken());
                        case "expired" -> source.sendError(Text.literal(
                                "That link attempt expired. Run /maplink again."));
                        case "error" -> source.sendError(Text.literal(result.errorMessage()));
                        default -> {
                            if (System.currentTimeMillis() >= deadlineMillis) {
                                source.sendError(Text.literal(
                                        "Timed out waiting for the password. Run /maplink again."));
                            } else {
                                schedulePasswordPoll(source, client, backend, code, username, deadlineMillis);
                            }
                        }
                    }
                })));
    }

    private static void onLinked(
            FabricClientCommandSource source, String username, boolean created,
            String mcName, String modToken) {
        if (modToken != null) {
            TokenStore.save(modToken);
        }

        String name = mcName != null ? mcName : username;
        String message = created
                ? "Account created and linked as " + name
                        + " — you're signed in on the website."
                : "Welcome back, " + name
                        + " — you're signed in on the website.";
        source.sendFeedback(Text.literal(message).formatted(Formatting.GREEN));
        source.sendFeedback(Text.literal(
                "Upload maparts with /mapselect (two corners) +"
                        + " /mapupload, or just /mapupload while looking"
                        + " at the wall.").formatted(Formatting.GRAY));
        source.sendFeedback(Links.prefixedLink(
                "→ ", "Open your dashboard",
                BACKEND_URL + "/dashboard"));
    }
}
