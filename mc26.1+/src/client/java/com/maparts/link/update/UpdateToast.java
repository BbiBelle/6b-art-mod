package com.maparts.link.update;

import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/** A plain top-right system toast for update notifications. */
final class UpdateToast extends SystemToast {
    UpdateToast(Component title, Component description) {
        super(SystemToast.SystemToastId.PERIODIC_NOTIFICATION, title, description);
    }
}
