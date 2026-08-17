package com.maparts.link.notify;

import com.maparts.link.command.LinkCommand;
import com.maparts.link.config.TokenStore;
import com.maparts.link.http.BackendClient;
import com.maparts.link.util.Links;
import com.maparts.link.util.ModChat;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

/**
 * In-game trade notifications: a summary shortly after joining a world, and a
 * ping whenever a new proposal arrives while playing (60s poll).
 */
public final class TradeNotifier {
    private static final int POLL_TICKS = 20 * 60;
    private static final int JOIN_DELAY_TICKS = 20 * 5;
    // Wall-clock floor beneath the tick-based schedule above: it caps the
    // steady-state cadence, but a rejoin resets ticksUntilCheck to
    // JOIN_DELAY_TICKS regardless of when the last check ran, so repeated
    // rejoins alone could otherwise call the API faster than once a minute.
    private static final long MIN_CHECK_INTERVAL_MILLIS = 60_000;

    private static int ticksUntilCheck = -1;
    private static int lastPending = -1;
    private static boolean checking = false;
    private static long lastCheckMillis = 0;

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

    private static void check(Minecraft client) {
        if (checking) {
            return;
        }

        long now = System.currentTimeMillis();

        if (now - lastCheckMillis < MIN_CHECK_INTERVAL_MILLIS) {
            return;
        }

        String token = TokenStore.load();

        if (token == null) {
            return;
        }

        lastCheckMillis = now;
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
                        Component description = Component.literal(
                                "You have " + result.pending()
                                        + " trade proposal"
                                        + (result.pending() == 1 ? "" : "s")
                                        + " waiting"
                                        + (result.latestFrom() != null
                                                ? " (latest from " + result.latestFrom() + ")"
                                                : "")
                                        + ".");
                        ModChat.message(client.player, description.copy().withStyle(ChatFormatting.GOLD));
                        ModChat.message(client.player, Links.prefixedLink(
                                "→ ", "Open your trades",
                                LinkCommand.BACKEND_URL + "/dashboard/trades"));
                        client.getToastManager().addToast(new TradeToast(
                                Component.literal("6b-art trades"), description));
                    } else if (result.pending() > previous) {
                        Component description = Component.literal(
                                "New trade proposal"
                                        + (result.latestFrom() != null
                                                ? " from " + result.latestFrom()
                                                : "")
                                        + "!");
                        ModChat.message(client.player, description.copy().withStyle(ChatFormatting.GOLD));
                        ModChat.message(client.player, Links.prefixedLink(
                                "→ ", "Open your trades",
                                LinkCommand.BACKEND_URL + "/dashboard/trades"));
                        client.getToastManager().addToast(new TradeToast(
                                Component.literal("6b-art trades"), description));
                    }
                }));
    }
}

