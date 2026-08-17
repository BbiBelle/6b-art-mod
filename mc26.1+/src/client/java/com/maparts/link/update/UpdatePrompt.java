package com.maparts.link.update;

import com.maparts.link.util.ModChat;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

/**
 * Offers to self-update on the title screen, the way Xaero's mods do — never
 * mid-session, since a player mid-world has no reason to see this and the
 * new jar can't take effect until Minecraft restarts anyway. The check runs
 * once at startup; the prompt is offered at most once per launch, the first
 * time the title screen appears after the check resolves.
 */
public final class UpdatePrompt {
    private static volatile UpdateChecker.UpdateInfo available;
    private static boolean offered = false;

    private UpdatePrompt() {}

    public static void init() {
        String currentVersion = FabricLoader.getInstance()
                .getModContainer("maparts_link")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("0");

        // The title screen usually renders well before this network round
        // trip resolves, so ScreenEvents.AFTER_INIT alone would miss it —
        // that event only fires once, on the way in. Racing the offer from
        // both sides (screen-init here, and again the moment the check
        // itself resolves below) means whichever finishes second is the one
        // that actually shows the prompt, so it still appears on the very
        // first title screen rather than silently never appearing that launch.
        UpdateChecker.checkAsync(currentVersion).thenAccept(info -> {
            available = info;

            if (info != null) {
                Minecraft client = Minecraft.getInstance();
                client.execute(() -> tryOffer(client, client.screen));
            }
        });

        ScreenEvents.AFTER_INIT.register(
                (client, screen, scaledWidth, scaledHeight) -> tryOffer(client, screen));
    }

    private static void tryOffer(Minecraft client, Screen screen) {
        if (offered || !(screen instanceof TitleScreen) || available == null) {
            return;
        }

        offered = true;
        client.setScreen(confirmScreen(client, screen, available));
    }

    private static ConfirmScreen confirmScreen(Minecraft client, Screen titleScreen, UpdateChecker.UpdateInfo info) {
        return new ConfirmScreen(
                accepted -> {
                    if (accepted) {
                        client.setScreen(titleScreen);
                        install(client, info);
                    } else {
                        client.setScreen(titleScreen);
                    }
                },
                ModChat.prefixed(Component.literal("Update available")),
                Component.literal("A new version of 6b-art (v" + info.version() + ") is available. Install it now?"),
                Component.literal("Install"),
                Component.literal("Not now"));
    }

    private static void install(Minecraft client, UpdateChecker.UpdateInfo info) {
        UpdateInstaller.installAsync(info.downloadUrl()).thenAccept(result -> client.execute(() -> {
            // No player/chat exists on the title screen, so the toast is the
            // only notification surface available here.
            if (result.success()) {
                client.getToastManager().addToast(new UpdateToast(
                        Component.literal("6b-art updated"),
                        Component.literal("Installed v" + info.version() + " — restart Minecraft to apply it.")));
            } else {
                client.getToastManager().addToast(new UpdateToast(
                        Component.literal("6b-art update failed"),
                        Component.literal(result.message() + " Get it at: " + info.releaseUrl())));
            }
        }));
    }
}
