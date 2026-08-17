package com.maparts.link.update;

import net.minecraft.client.toast.SystemToast;
import net.minecraft.text.Text;

/** A plain top-right system toast for update notifications (silent, unlike TradeToast). */
final class UpdateToast extends SystemToast {
    UpdateToast(Text title, Text description) {
        super(SystemToast.Type.PERIODIC_NOTIFICATION, title, description);
    }
}
