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

public final class LinkCommand {
    // The mod is compiled and distributed per-deployment, so the backend it
    // talks to is fixed at build time rather than left player-editable.
    public static final String BACKEND_URL = "https://maparts-website.vercel.app";

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

        new BackendClient(BACKEND_URL)
                .verify(code, username)
                .thenAccept(result -> client.execute(() -> {
                    if (result.success()) {
                        if (result.modToken() != null) {
                            TokenStore.save(result.modToken());
                        }

                        String name = result.mcName() != null ? result.mcName() : username;
                        String message = result.created()
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
                    } else {
                        source.sendError(Text.literal(result.message()));
                    }
                }));

        return Command.SINGLE_SUCCESS;
    }
}
