package com.maparts.link.capture;

import java.util.function.BooleanSupplier;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * Waits, across client ticks, for map pixel data to finish streaming in from
 * the server before a capture composes its image.
 *
 * An item frame's held stack (and the map ID it references) syncs the instant
 * the entity itself is tracked, but a map's actual 128x128 pixel content
 * (MapState) streams in on a separate channel and can lag behind by several
 * seconds — composing a big wall immediately, before every referenced map's
 * data has arrived, silently treats not-yet-loaded frames as absent, either
 * truncating the wall or fragmenting one mapart into several. Waiting here
 * (rather than sampling once) is what avoids losing those pieces.
 *
 * Registered once at mod init, like TradeNotifier: Fabric's event bus has no
 * listener-removal API, so a persistent single-slot poller (rather than one
 * registration per capture attempt) is the correct shape.
 */
public final class FrameDataWaiter {
    private static final int POLL_EVERY_TICKS = 4;
    private static final int MAX_WAIT_TICKS = 20 * 20; // 20 seconds

    private static Runnable pendingPoll;
    private static int elapsedTicks;
    private static int ticksUntilPoll;

    private FrameDataWaiter() {}

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (pendingPoll == null) {
                return;
            }

            if (ticksUntilPoll > 0) {
                ticksUntilPoll--;
                return;
            }

            ticksUntilPoll = POLL_EVERY_TICKS;
            elapsedTicks += POLL_EVERY_TICKS;

            Runnable poll = pendingPoll;
            pendingPoll = null; // poll re-arms itself below if still waiting
            poll.run();
        });
    }

    /**
     * Polls {@code ready} every few ticks until it reports true (then runs
     * {@code onReady}) or {@code MAX_WAIT_TICKS} elapses (then runs
     * {@code onTimeout}). Only one wait may be active at a time — check
     * {@link #isBusy()} first, since a second call here would silently
     * abandon whichever wait was already pending.
     */
    public static void waitUntil(BooleanSupplier ready, Runnable onReady, Runnable onTimeout) {
        elapsedTicks = 0;
        ticksUntilPoll = 0;
        pendingPoll =
                new Runnable() {
                    @Override
                    public void run() {
                        if (ready.getAsBoolean()) {
                            onReady.run();
                            return;
                        }

                        if (elapsedTicks >= MAX_WAIT_TICKS) {
                            onTimeout.run();
                            return;
                        }

                        pendingPoll = this;
                    }
                };
    }

    /** Whether a wait is currently in progress. */
    public static boolean isBusy() {
        return pendingPoll != null;
    }
}
