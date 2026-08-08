package com.maparts.link.util;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Every chat line the mod sends carries this "[6b-art] " prefix, so players
 * can tell mod output apart from server or other-mod chat at a glance.
 */
public final class ModChat {
    // True orange, not vanilla's more amber ChatFormatting.GOLD.
    private static final int PREFIX_COLOR = 0xFFA500;

    private ModChat() {}

    public static MutableComponent prefixed(Component message) {
        return Component.literal("[6b-art] ")
                .withStyle(style -> style.withColor(PREFIX_COLOR))
                .append(message);
    }

    public static void feedback(FabricClientCommandSource source, Component message) {
        source.sendFeedback(prefixed(message));
    }

    public static void error(FabricClientCommandSource source, Component message) {
        source.sendError(prefixed(message));
    }

    public static void message(LocalPlayer player, Component message) {
        player.sendSystemMessage(prefixed(message));
    }
}
