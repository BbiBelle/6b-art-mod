package com.maparts.link.notify;

import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

/**
 * A vanilla top-right system toast for trade notifications. Plain
 * SystemToasts are silent; overriding getSoundEvent() is what makes this one
 * play a chime when it appears.
 */
final class TradeToast extends SystemToast {
    TradeToast(Component title, Component description) {
        super(SystemToast.SystemToastId.PERIODIC_NOTIFICATION, title, description);
    }

    @Override
    public SoundEvent getSoundEvent() {
        return SoundEvents.VILLAGER_YES;
    }
}
