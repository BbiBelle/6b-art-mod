package com.maparts.link.command;

import com.maparts.link.config.TokenStore;
import com.maparts.link.http.BackendClient;
import com.maparts.link.util.Links;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

public final class LinkCommand {
    // The mod is compiled and distributed per-deployment, so the backend it
    // talks to is fixed at build time rather than left player-editable.
    public static final String BACKEND_URL = "https://maparts-website.vercel.app";

    private LinkCommand() {}

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
                ClientCommands.literal("maplink")
                        .then(ClientCommands
                                .argument("code", StringArgumentType.word())
                                .executes(LinkCommand::run)));
    }

    private static int run(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String code = StringArgumentType.getString(context, "code");

        Minecraft client = source.getClient();
        String username = client.getUser().getName();

        source.sendFeedback(Component.literal("Verifying your code…").withStyle(ChatFormatting.GRAY));

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
                        source.sendFeedback(Component.literal(message).withStyle(ChatFormatting.GREEN));
                        source.sendFeedback(Component.literal(
                                "Upload maparts with /mapselect (two corners) +"
                                        + " /mapupload, or just /mapupload while looking"
                                        + " at the wall.").withStyle(ChatFormatting.GRAY));
                        source.sendFeedback(Links.prefixedLink(
                                "→ ", "Open your dashboard",
                                BACKEND_URL + "/dashboard"));
                    } else {
                        source.sendError(Component.literal(result.message()));
                    }
                }));

        return Command.SINGLE_SUCCESS;
    }
}



