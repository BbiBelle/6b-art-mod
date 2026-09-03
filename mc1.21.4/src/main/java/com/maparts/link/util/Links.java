package com.maparts.link.util;

import net.minecraft.text.ClickEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Clickable chat links back to the website.
 *
 * <p>1.21.4 still has the flat {@code ClickEvent(Action, String)} constructor.
 * 1.21.5 replaced it with sealed per-action records ({@code ClickEvent.OpenUrl},
 * taking a {@link java.net.URI}) — see the {@code mc1.21.5-1.21.11/} build for
 * that form. The two do not share a source line, hence the separate builds.
 */
public final class Links {
    private Links() {}

    public static MutableText link(String label, String url) {
        return Text.literal(label).styled(style -> style
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                .withUnderline(true)
                .withFormatting(Formatting.AQUA));
    }

    public static MutableText prefixedLink(String prefix, String label, String url) {
        return Text.literal(prefix).formatted(Formatting.GRAY)
                .append(link(label, url));
    }
}
