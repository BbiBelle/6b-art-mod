package com.maparts.link.update;

import com.maparts.link.util.ModChat;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.text.Text;

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
                MinecraftClient client = MinecraftClient.getInstance();
                client.execute(() -> tryOffer(client, client.currentScreen));
            }
        });

        ScreenEvents.AFTER_INIT.register(
                (client, screen, scaledWidth, scaledHeight) -> tryOffer(client, screen));
    }

    private static void tryOffer(MinecraftClient client, Screen screen) {
        if (offered || !(screen instanceof TitleScreen) || available == null) {
            return;
        }

        offered = true;
        client.setScreen(confirmScreen(client, screen, available));
    }

    private static ConfirmScreen confirmScreen(MinecraftClient client, Screen titleScreen, UpdateChecker.UpdateInfo info) {
        return new ConfirmScreen(
                accepted -> {
                    if (accepted) {
                        client.setScreen(titleScreen);
                        install(client, info);
                    } else {
                        client.setScreen(titleScreen);
                    }
                },
                ModChat.prefixed(Text.literal("Update available")),
                Text.literal("A new version of 6b-art (v" + info.version() + ") is available. Install it now?"),
                Text.literal("Install"),
                Text.literal("Not now"));
    }

    private static void install(MinecraftClient client, UpdateChecker.UpdateInfo info) {
        UpdateInstaller.installAsync(info.downloadUrl()).thenAccept(result -> client.execute(() -> {
            // No player/chat exists on the title screen, so the toast is the
            // only notification surface available here.
            if (result.success()) {
                client.getToastManager().add(new UpdateToast(
                        Text.literal("6b-art updated"),
                        Text.literal("Installed v" + info.version() + " — restart Minecraft to apply it.")));
            } else {
                client.getToastManager().add(new UpdateToast(
                        Text.literal("6b-art update failed"),
                        Text.literal(result.message() + " Get it at: " + info.releaseUrl())));
            }
        }));
    }
}
