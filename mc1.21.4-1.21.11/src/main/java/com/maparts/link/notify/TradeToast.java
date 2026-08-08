package com.maparts.link.notify;

import net.minecraft.client.toast.SystemToast;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

/**
 * A vanilla top-right system toast for trade notifications. Plain
 * SystemToasts are silent; overriding getSoundEvent() is what makes this one
 * play a chime when it appears.
 */
final class TradeToast extends SystemToast {
    TradeToast(Text title, Text description) {
        super(SystemToast.Type.PERIODIC_NOTIFICATION, title, description);
    }

    @Override
    public SoundEvent getSoundEvent() {
        return SoundEvents.ENTITY_VILLAGER_YES;
    }
}
