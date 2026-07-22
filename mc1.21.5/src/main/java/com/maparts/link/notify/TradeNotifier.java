package com.maparts.link.notify;

import com.maparts.link.command.LinkCommand;
import com.maparts.link.config.TokenStore;
import com.maparts.link.http.BackendClient;
import com.maparts.link.util.Links;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * In-game trade notifications: a summary shortly after joining a world, and a
 * ping whenever a new proposal arrives while playing (60s poll).
 */
public final class TradeNotifier {
    private static final int POLL_TICKS = 20 * 60;
    private static final int JOIN_DELAY_TICKS = 20 * 5;

    private static int ticksUntilCheck = -1;
    private static int lastPending = -1;
    private static boolean checking = false;

    private TradeNotifier() {}

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            lastPending = -1;
            ticksUntilCheck = JOIN_DELAY_TICKS;
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || ticksUntilCheck < 0) {
                return;
            }

            if (ticksUntilCheck > 0) {
                ticksUntilCheck--;
                return;
            }

            ticksUntilCheck = POLL_TICKS;
            check(client);
        });
    }

    private static void check(MinecraftClient client) {
        if (checking) {
            return;
        }

        String token = TokenStore.load();

        if (token == null) {
            return;
        }

        checking = true;

        new BackendClient(LinkCommand.BACKEND_URL)
                .pendingTrades(token)
                .thenAccept(result -> client.execute(() -> {
                    checking = false;

                    if (result == null || client.player == null) {
                        return;
                    }

                    boolean firstCheck = lastPending < 0;
                    int previous = lastPending;
                    lastPending = result.pending();

                    if (result.pending() == 0) {
                        return;
                    }

                    if (firstCheck) {
                        client.player.sendMessage(Text.literal(
                                "[6bMaps] You have " + result.pending()
                                        + " trade proposal"
                                        + (result.pending() == 1 ? "" : "s")
                                        + " waiting"
                                        + (result.latestFrom() != null
                                                ? " (latest from " + result.latestFrom() + ")"
                                                : "")
                                        + ".").formatted(Formatting.GOLD), false);
                        client.player.sendMessage(Links.prefixedLink(
                                "→ ", "Open your trades",
                                LinkCommand.BACKEND_URL + "/dashboard/trades"), false);
                    } else if (result.pending() > previous) {
                        client.player.sendMessage(Text.literal(
                                "[6bMaps] New trade proposal"
                                        + (result.latestFrom() != null
                                                ? " from " + result.latestFrom()
                                                : "")
                                        + "!").formatted(Formatting.GOLD), false);
                        client.player.sendMessage(Links.prefixedLink(
                                "→ ", "Open your trades",
                                LinkCommand.BACKEND_URL + "/dashboard/trades"), false);
                    }
                }));
    }
}
