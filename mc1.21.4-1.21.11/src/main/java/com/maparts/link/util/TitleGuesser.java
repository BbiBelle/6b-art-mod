package com.maparts.link.util;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.maparts.link.capture.MapGridCapture;

/**
 * Derives a mapart title from the custom names players give their map items.
 * Multi-map arts are usually named with a counter ("Sunset (3/6)", "Sunset 3
 * of 6", "Sunset #3"); stripping the counter and taking the most common
 * remaining name recovers the intended title.
 */
public final class TitleGuesser {
    // "(3/6)", "[3/6]", "3/6", "3 of 6", "3 di 6" at the end of the name.
    private static final Pattern COUNTER = Pattern.compile(
            "(?i)[\\s\\-_.,:;|#]*[\\(\\[]?\\s*\\d{1,3}\\s*(?:/|of|di|su)\\s*\\d{1,3}\\s*[\\)\\]]?\\s*$");
    // "part 3", "pt 3", "#3", or a bare trailing number after a separator.
    private static final Pattern TRAILING_INDEX = Pattern.compile(
            "(?i)[\\s\\-_.,:;|#]+(?:part|parte|pt\\.?)?\\s*#?\\d{1,3}\\s*$");
    // Leftover decoration once counters are gone ("Sunset -", "Sunset [").
    private static final Pattern TRAILING_SEPARATORS = Pattern.compile(
            "[\\s\\-_.,:;|#\\(\\)\\[\\]]+$");

    private TitleGuesser() {}

    /** A map item name with its per-map counter/index stripped; null if
     * nothing usable remains. */
    public static String cleanName(String rawName) {
        if (rawName == null) {
            return null;
        }

        String name = rawName.trim();
        String previous;

        do {
            previous = name;
            name = COUNTER.matcher(name).replaceAll("");
            name = TRAILING_INDEX.matcher(name).replaceAll("");
            name = TRAILING_SEPARATORS.matcher(name).replaceAll("").trim();
        } while (!name.equals(previous));

        return name.isBlank() ? null : name;
    }

    /**
     * The most common cleaned name across the captured tiles, or null when no
     * tile has a usable name. Ties go to the name seen first.
     */
    public static String guess(List<MapGridCapture.TileInfo> tiles) {
        Map<String, Integer> counts = new HashMap<>();
        Map<String, String> firstCasing = new HashMap<>();
        String best = null;
        int bestCount = 0;

        for (MapGridCapture.TileInfo tile : tiles) {
            String cleaned = cleanName(tile.name());

            if (cleaned == null) {
                continue;
            }

            String key = cleaned.toLowerCase();
            firstCasing.putIfAbsent(key, cleaned);
            int count = counts.merge(key, 1, Integer::sum);

            if (count > bestCount) {
                bestCount = count;
                best = firstCasing.get(key);
            }
        }

        return best;
    }
}
