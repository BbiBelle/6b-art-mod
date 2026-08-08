package com.maparts.link.util;

import java.net.URI;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

/** Clickable chat links back to the website. */
public final class Links {
    private Links() {}

    public static MutableComponent link(String label, String url) {
        return Component.literal(label).withStyle(style -> style
                .withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))
                .withUnderlined(true)
                .withColor(ChatFormatting.AQUA));
    }

    public static MutableComponent prefixedLink(String prefix, String label, String url) {
        return Component.literal(prefix).withStyle(ChatFormatting.GRAY)
                .append(link(label, url));
    }
}



