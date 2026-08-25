package com.maparts.link.capture;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import javax.imageio.ImageIO;

import com.maparts.link.util.TitleGuesser;

import net.minecraft.world.level.material.MapColor;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.core.Direction;

/**
 * Captures a wall or floor of item frames holding filled maps as one
 * stitched image.
 *
 * Three entry points, all asynchronous (they hand their result to a callback
 * instead of returning it, since composing may need to wait several client
 * ticks for map data — see {@link FrameDataWaiter}): {@link #capture}
 * flood-fills the connected rectangle around the frame under the crosshair,
 * {@link #captureCorners} takes two explicitly selected corner frames (via
 * /mapselect) and captures everything in between, and {@link #captureAll}
 * segments a whole wall of several maparts. Wall- and floor-mounted maparts
 * are both supported; ceiling grids have an ambiguous "up" and are rejected
 * with a clear message. For a floor grid, column runs east and row runs
 * south, as if viewed from directly above with north at the top.
 *
 * Targets Minecraft 1.21.11 — every Minecraft call here was checked against
 * Yarn 1.21.11+build.6.
 */
public final class MapGridCapture {
    private static final int TILE = 128;
    private static final int MAX_GRID = 64;
    private static final double SEARCH_RADIUS = 70.0;

    /** Vanilla metadata of one captured frame: grid cell, map ID, item name. */
    public record TileInfo(int col, int row, int mapId, String name) {}

    public record Result(
            byte[] png, int mapsWide, int mapsTall, List<TileInfo> tiles, String error) {
        public static Result error(String message) {
            return new Result(null, 0, 0, List.of(), message);
        }

        public static Result success(
                byte[] png, int mapsWide, int mapsTall, List<TileInfo> tiles) {
            return new Result(png, mapsWide, mapsTall, tiles, null);
        }

        public boolean failed() {
            return error != null;
        }
    }

    private MapGridCapture() {}

    /** The map-holding item frame under the crosshair, or null. */
    public static ItemFrame targetedMapFrame(Minecraft client) {
        if (client.level == null
                || !(client.hitResult instanceof EntityHitResult hit)
                || !(hit.getEntity() instanceof ItemFrame frame)
                || !holdsFilledMap(frame)) {
            return null;
        }

        return frame;
    }

    /**
     * Validates a frame as a usable wall- or floor-mounted map frame;
     * returns an error message or null when it's fine. Only checks
     * structural things (is it a filled map, is it ceiling-mounted) — the
     * frame's actual pixel data may still be loading, which
     * {@link #capture}/{@link #captureCorners} wait for separately before
     * composing.
     */
    public static String validateWallFrame(Minecraft client, ItemFrame frame) {
        if (frame == null || !holdsFilledMap(frame)) {
            return "Look at an item frame holding a filled map first.";
        }

        if (frame.getDirection() == Direction.DOWN) {
            return "Ceiling maparts aren't supported yet — place the maps on a wall or floor.";
        }

        return null;
    }

    /**
     * Checks that two corner frames sit on the same wall/floor (same facing,
     * same plane); returns an error message or null.
     */
    public static String validateSameWall(
            ItemFrame first, ItemFrame second) {
        if (first.getDirection() != second.getDirection()) {
            return "Both corners must be on the same wall or floor (their facing differs).";
        }

        if (!onSamePlane(first, second, first.getDirection())) {
            return "Both corners must be at the same depth (wall) or level (floor).";
        }

        return null;
    }

    /** Grid size implied by two opposite corner frames, as {wide, tall}. */
    public static int[] gridSize(ItemFrame first, ItemFrame second) {
        Direction facing = first.getDirection();
        Direction right = rightOf(facing);
        int[] cell = cellOf(first, second, facing, right);
        return new int[] {Math.abs(cell[0]) + 1, Math.abs(cell[1]) + 1};
    }

    /**
     * The "rightward" horizontal direction for a given attachment facing —
     * clockwise from the facing when viewed from in front of the surface.
     * Floor grids (facing UP) have no natural facing-relative right, so they
     * use a fixed convention instead: east is right, matching a top-down,
     * north-up view.
     */
    private static Direction rightOf(Direction facing) {
        return facing == Direction.UP ? Direction.EAST : facing.getOpposite().getClockWise();
    }

    /**
     * Flood-fill capture around the frame under the crosshair. Waits for
     * every involved frame's map data before composing (see class docs).
     */
    public static void capture(Minecraft client, Consumer<Result> callback) {
        ItemFrame start = targetedMapFrame(client);
        String invalid = validateWallFrame(client, start);

        if (invalid != null) {
            callback.accept(Result.error(invalid));
            return;
        }

        Direction facing = start.getDirection();
        Direction right = rightOf(facing);
        Map<Long, ItemFrame> byCell =
                collectCells(client, start, facing, right, SEARCH_RADIUS);

        // Flood fill from the targeted frame so a second mapart elsewhere on
        // the same wall never bleeds into this capture.
        Map<Long, ItemFrame> component = floodFillFrom(byCell, 0, 0);
        int[] bounds = boundsOf(component.keySet());

        awaitMapData(
                client,
                component,
                () ->
                        callback.accept(assemble(
                                client, component, bounds[0], bounds[1], bounds[2], bounds[3])),
                missing -> callback.accept(Result.error(loadTimeoutMessage(missing))));
    }

    /**
     * Capture of the exact rectangle between two selected corner frames.
     * Waits for every involved frame's map data before composing.
     */
    public static void captureCorners(
            Minecraft client, ItemFrame first, ItemFrame second,
            Consumer<Result> callback) {
        String invalid = validateWallFrame(client, first);

        if (invalid == null) {
            invalid = validateWallFrame(client, second);
        }

        if (invalid == null) {
            invalid = validateSameWall(first, second);
        }

        if (invalid != null) {
            callback.accept(Result.error(invalid));
            return;
        }

        Direction facing = first.getDirection();
        Direction right = rightOf(facing);

        double reach = Math.abs(first.getX() - second.getX())
                + Math.abs(first.getY() - second.getY())
                + Math.abs(first.getZ() - second.getZ()) + 2.0;
        Map<Long, ItemFrame> byCell =
                collectCells(client, first, facing, right, reach);

        int[] cornerCell = cellOf(first, second, facing, right);
        int minCol = Math.min(0, cornerCell[0]);
        int maxCol = Math.max(0, cornerCell[0]);
        int minRow = Math.min(0, cornerCell[1]);
        int maxRow = Math.max(0, cornerCell[1]);

        Map<Long, ItemFrame> selected = new HashMap<>();

        for (int col = minCol; col <= maxCol; col++) {
            for (int row = minRow; row <= maxRow; row++) {
                ItemFrame frame = byCell.get(cellKey(col, row));

                if (frame != null) {
                    selected.put(cellKey(col, row), frame);
                }
            }
        }

        awaitMapData(
                client,
                selected,
                () -> callback.accept(assemble(client, selected, minCol, maxCol, minRow, maxRow)),
                missing -> callback.accept(Result.error(loadTimeoutMessage(missing))));
    }

    /** Result of segmenting a whole wall into separate maparts. */
    public record MultiResult(List<Result> segments, int skippedIrregular, String error) {
        public static MultiResult error(String message) {
            return new MultiResult(List.of(), 0, message);
        }

        public boolean failed() {
            return error != null;
        }
    }

    private static final int MAX_SEGMENTS = 12;
    // A boundary between two different arts shows a color jump across the
    // seam well above each side's own edge-to-edge variation.
    private static final double SEAM_FACTOR = 3.0;
    private static final double SEAM_NOISE_FLOOR = 400.0;
    private static final double UNSET_MISMATCH_DISTANCE = 3 * 255.0 * 255.0;

    private record SegmentCandidate(int minRow, int minCol, Result result) {}

    /**
     * Segments the whole wall around the targeted frame into separate
     * maparts and captures each one. Waits for every frame's map data to
     * finish loading first — grouping decisions (name matching, seam
     * continuity) and the final pixel draw both need real data, and running
     * them against not-yet-loaded frames is what previously fragmented or
     * truncated big walls. Grouping rules, in order: maps whose cleaned
     * custom names match belong together (names are how players tag
     * multi-map arts); unnamed neighbors stay together only when the image
     * continues across their shared seam. Non-rectangular groups are skipped
     * (maparts are rectangles) and counted in skippedIrregular.
     */
    public static void captureAll(Minecraft client, Consumer<MultiResult> callback) {
        ItemFrame start = targetedMapFrame(client);
        String invalid = validateWallFrame(client, start);

        if (invalid != null) {
            callback.accept(MultiResult.error(invalid));
            return;
        }

        Direction facing = start.getDirection();
        Direction right = rightOf(facing);
        Map<Long, ItemFrame> byCell =
                collectCells(client, start, facing, right, SEARCH_RADIUS);

        awaitMapData(
                client,
                byCell,
                () -> callback.accept(segmentWall(client, byCell)),
                missing -> callback.accept(MultiResult.error(loadTimeoutMessage(missing))));
    }

    private static String loadTimeoutMessage(int missing) {
        return missing + " map" + (missing == 1 ? "" : "s")
                + " didn't finish loading from the server in time — wait a moment"
                + " (or move closer) and try again.";
    }

    /**
     * Waits (across client ticks) until every candidate frame's map pixel
     * data has arrived, then runs onReady; if some never arrive within the
     * timeout, onTimeout receives how many are still missing so the caller
     * can report it instead of silently dropping them.
     */
    private static void awaitMapData(
            Minecraft client,
            Map<Long, ItemFrame> byCell,
            Runnable onReady,
            IntConsumer onTimeout) {
        if (allMapStatesReady(client, byCell)) {
            onReady.run();
            return;
        }

        FrameDataWaiter.waitUntil(
                () -> allMapStatesReady(client, byCell),
                onReady,
                () -> {
                    int missing = 0;

                    for (ItemFrame frame : byCell.values()) {
                        if (MapItemSavedData(client, frame) == null) {
                            missing++;
                        }
                    }

                    onTimeout.accept(missing);
                });
    }

    private static boolean allMapStatesReady(
            Minecraft client, Map<Long, ItemFrame> byCell) {
        for (ItemFrame frame : byCell.values()) {
            if (MapItemSavedData(client, frame) == null) {
                return false;
            }
        }

        return true;
    }

    /** The grouping + assemble pass, run once every frame's map data is ready. */
    private static MultiResult segmentWall(
            Minecraft client, Map<Long, ItemFrame> byCell) {
        Map<Long, String> cleanedNames = new HashMap<>();

        for (Map.Entry<Long, ItemFrame> entry : byCell.entrySet()) {
            Component customName = entry.getValue().getItem().get(DataComponents.CUSTOM_NAME);
            cleanedNames.put(entry.getKey(),
                    TitleGuesser.cleanName(
                            customName != null ? customName.getString() : null));
        }

        Set<Long> visited = new HashSet<>();
        List<Map<Long, ItemFrame>> components = new ArrayList<>();

        for (Long startKey : byCell.keySet()) {
            if (visited.contains(startKey)) {
                continue;
            }

            Map<Long, ItemFrame> component = new HashMap<>();
            ArrayDeque<Long> queue = new ArrayDeque<>();
            queue.add(startKey);
            visited.add(startKey);

            while (!queue.isEmpty()) {
                long key = queue.poll();
                component.put(key, byCell.get(key));
                int col = (int) (key >> 32);
                int row = (int) key;

                int[][] offsets = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

                for (int[] offset : offsets) {
                    long neighborKey = cellKey(col + offset[0], row + offset[1]);

                    if (visited.contains(neighborKey)
                            || !byCell.containsKey(neighborKey)) {
                        continue;
                    }

                    // The seam test needs the left/top tile first.
                    boolean forward = offset[0] > 0 || offset[1] > 0;
                    long aKey = forward ? key : neighborKey;
                    long bKey = forward ? neighborKey : key;

                    if (!sameArt(client,
                            byCell.get(aKey), byCell.get(bKey),
                            cleanedNames.get(aKey), cleanedNames.get(bKey),
                            offset[1] == 0)) {
                        continue;
                    }

                    visited.add(neighborKey);
                    queue.add(neighborKey);
                }
            }

            components.add(component);
        }

        List<SegmentCandidate> candidates = new ArrayList<>();
        int skipped = 0;

        for (Map<Long, ItemFrame> component : components) {
            int[] bounds = boundsOf(component.keySet());
            int minCol = bounds[0];
            int maxCol = bounds[1];
            int minRow = bounds[2];
            int maxRow = bounds[3];
            int wide = maxCol - minCol + 1;
            int tall = maxRow - minRow + 1;

            if (component.size() != wide * tall
                    || wide > MAX_GRID || tall > MAX_GRID) {
                skipped++;
                continue;
            }

            Result result = assemble(client, component, minCol, maxCol, minRow, maxRow);

            if (result.failed()) {
                skipped++;
                continue;
            }

            candidates.add(new SegmentCandidate(minRow, minCol, result));
        }

        if (candidates.isEmpty()) {
            return MultiResult.error(
                    "No complete rectangular maparts found on this wall"
                            + (skipped > 0
                                    ? " (" + skipped + " irregular group"
                                            + (skipped == 1 ? "" : "s") + " skipped)"
                                    : "")
                            + ".");
        }

        if (candidates.size() > MAX_SEGMENTS) {
            return MultiResult.error(
                    "Found " + candidates.size() + " maparts — more than the "
                            + MAX_SEGMENTS + " per run limit. Upload sections with"
                            + " /mapselect + /mapupload instead.");
        }

        candidates.sort(Comparator
                .comparingInt(SegmentCandidate::minRow)
                .thenComparingInt(SegmentCandidate::minCol));

        return new MultiResult(
                candidates.stream().map(SegmentCandidate::result).toList(),
                skipped, null);
    }

    /**
     * Whether two adjacent frames belong to the same mapart. Named maps
     * decide by name; unnamed pairs decide by seam continuity.
     */
    private static boolean sameArt(
            Minecraft client,
            ItemFrame a, ItemFrame b,
            String nameA, String nameB,
            boolean horizontal) {
        if (nameA != null || nameB != null) {
            return nameA != null && nameB != null && nameA.equalsIgnoreCase(nameB);
        }

        MapItemSavedData stateA = MapItemSavedData(client, a);
        MapItemSavedData stateB = MapItemSavedData(client, b);

        if (stateA == null || stateB == null) {
            return false;
        }

        return !seamCut(stateA, a.getRotation() % 4,
                stateB, b.getRotation() % 4, horizontal);
    }

    /**
     * True when the border between two tiles looks like an art boundary: the
     * color jump across the seam is far larger than the jump between each
     * tile's own last two pixel rows/columns (its local texture).
     */
    private static boolean seamCut(
            MapItemSavedData a, int rotA, MapItemSavedData b, int rotB, boolean horizontal) {
        double seam = 0;
        double internalA = 0;
        double internalB = 0;

        for (int i = 0; i < TILE; i++) {
            int edgeA;
            int innerA;
            int edgeB;
            int innerB;

            if (horizontal) {
                // a sits to the left of b.
                edgeA = rotatedPacked(a, rotA, TILE - 1, i);
                innerA = rotatedPacked(a, rotA, TILE - 2, i);
                edgeB = rotatedPacked(b, rotB, 0, i);
                innerB = rotatedPacked(b, rotB, 1, i);
            } else {
                // a sits above b.
                edgeA = rotatedPacked(a, rotA, i, TILE - 1);
                innerA = rotatedPacked(a, rotA, i, TILE - 2);
                edgeB = rotatedPacked(b, rotB, i, 0);
                innerB = rotatedPacked(b, rotB, i, 1);
            }

            seam += colorDistance(edgeA, edgeB);
            internalA += colorDistance(edgeA, innerA);
            internalB += colorDistance(edgeB, innerB);
        }

        seam /= TILE;
        internalA /= TILE;
        internalB /= TILE;

        double internal = Math.max(Math.max(internalA, internalB), SEAM_NOISE_FLOOR);
        return seam > internal * SEAM_FACTOR;
    }

    /** Squared RGB distance between two packed map colors. */
    private static double colorDistance(int packedA, int packedB) {
        boolean unsetA = packedA >> 2 == 0;
        boolean unsetB = packedB >> 2 == 0;

        if (unsetA || unsetB) {
            // Shared emptiness continues; empty against drawn is a hard edge.
            return unsetA == unsetB ? 0 : UNSET_MISMATCH_DISTANCE;
        }

        int argbA = MapColor.getColorFromPackedId(packedA);
        int argbB = MapColor.getColorFromPackedId(packedB);
        int dr = ((argbA >> 16) & 0xFF) - ((argbB >> 16) & 0xFF);
        int dg = ((argbA >> 8) & 0xFF) - ((argbB >> 8) & 0xFF);
        int db = (argbA & 0xFF) - (argbB & 0xFF);
        return dr * dr + dg * dg + db * db;
    }

    private static Result assemble(
            Minecraft client,
            Map<Long, ItemFrame> cells,
            int minCol, int maxCol, int minRow, int maxRow) {
        int mapsWide = maxCol - minCol + 1;
        int mapsTall = maxRow - minRow + 1;

        if (mapsWide > MAX_GRID || mapsTall > MAX_GRID) {
            return Result.error(
                    "This mapart is larger than " + MAX_GRID + "x" + MAX_GRID
                            + " maps, which is the upload limit.");
        }

        if (cells.size() != mapsWide * mapsTall) {
            return Result.error(
                    "The selection isn't a complete rectangle of maps ("
                            + cells.size() + " maps found for a "
                            + mapsWide + "x" + mapsTall
                            + " grid). Fill the gaps and try again.");
        }

        BufferedImage image = new BufferedImage(
                mapsWide * TILE, mapsTall * TILE, BufferedImage.TYPE_INT_ARGB);
        List<TileInfo> tiles = new ArrayList<>(cells.size());

        for (Map.Entry<Long, ItemFrame> entry : cells.entrySet()) {
            int col = (int) (entry.getKey() >> 32) - minCol;
            int row = (int) entry.getKey().longValue() - minRow;
            ItemFrame frame = entry.getValue();
            MapItemSavedData state = MapItemSavedData(client, frame);

            if (state == null) {
                return Result.error("A map unloaded mid-capture. Try again.");
            }

            int rotation = frame.getRotation() % 4;

            if (rotation != 0) {
                return Result.error(
                        "This mapart has a rotated map tile — right-click each frame"
                                + " until it faces upright, then try again.");
            }

            drawTile(image, col * TILE, row * TILE, state, rotation);
            tiles.add(tileInfo(frame, col, row));
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            return Result.error("Encoding the capture as PNG failed.");
        }

        return Result.success(out.toByteArray(), mapsWide, mapsTall, tiles);
    }

    /** Reads the vanilla map metadata off a captured frame. */
    private static TileInfo tileInfo(ItemFrame frame, int col, int row) {
        ItemStack stack = frame.getItem();
        MapId mapId = stack.get(DataComponents.MAP_ID);
        Component customName = stack.get(DataComponents.CUSTOM_NAME);

        return new TileInfo(
                col,
                row,
                mapId != null ? mapId.id() : -1,
                customName != null ? customName.getString() : null);
    }

    /**
     * Whether this frame holds a filled map at all — synced the instant the
     * entity itself is tracked, unlike the map's actual pixel content.
     */
    private static boolean holdsFilledMap(ItemFrame frame) {
        return frame.getItem().getItem() instanceof MapItem;
    }

    private static Map<Long, ItemFrame> collectCells(
            Minecraft client,
            ItemFrame base,
            Direction facing,
            Direction right,
            double radius) {
        List<ItemFrame> candidates = client.level.getEntitiesOfClass(
                ItemFrame.class,
                base.getBoundingBox().inflate(radius),
                frame -> frame.getDirection() == facing
                        && holdsFilledMap(frame)
                        && onSamePlane(base, frame, facing));

        Map<Long, ItemFrame> byCell = new HashMap<>();

        for (ItemFrame frame : candidates) {
            int[] cell = cellOf(base, frame, facing, right);
            byCell.put(cellKey(cell[0], cell[1]), frame);
        }

        return byCell;
    }

    /** Flood-fills the connected component containing (startCol, startRow). */
    private static Map<Long, ItemFrame> floodFillFrom(
            Map<Long, ItemFrame> byCell, int startCol, int startRow) {
        Map<Long, ItemFrame> component = new HashMap<>();
        ArrayDeque<long[]> queue = new ArrayDeque<>();
        queue.add(new long[] {startCol, startRow});

        while (!queue.isEmpty()) {
            long[] cell = queue.poll();
            int col = (int) cell[0];
            int row = (int) cell[1];
            long key = cellKey(col, row);

            if (component.containsKey(key) || !byCell.containsKey(key)) {
                continue;
            }

            component.put(key, byCell.get(key));
            queue.add(new long[] {col + 1, row});
            queue.add(new long[] {col - 1, row});
            queue.add(new long[] {col, row + 1});
            queue.add(new long[] {col, row - 1});
        }

        return component;
    }

    /** {minCol, maxCol, minRow, maxRow} spanned by the given cell keys. */
    private static int[] boundsOf(Set<Long> keys) {
        int minCol = Integer.MAX_VALUE;
        int maxCol = Integer.MIN_VALUE;
        int minRow = Integer.MAX_VALUE;
        int maxRow = Integer.MIN_VALUE;

        for (long key : keys) {
            int col = (int) (key >> 32);
            int row = (int) key;
            minCol = Math.min(minCol, col);
            maxCol = Math.max(maxCol, col);
            minRow = Math.min(minRow, row);
            maxRow = Math.max(maxRow, row);
        }

        return new int[] {minCol, maxCol, minRow, maxRow};
    }

    /**
     * Grid cell of a frame relative to a base frame: {col right, row down}.
     * Wall grids use the wall's own vertical axis for row; floor grids
     * (facing UP) have no vertical axis in-plane, so row instead runs along
     * "right" rotated a further quarter-turn — south, under the fixed
     * east-is-right convention {@link #rightOf} uses for floors.
     */
    private static int[] cellOf(
            ItemFrame base, ItemFrame frame, Direction facing, Direction right) {
        int col = (int) Math.round(
                (frame.getX() - base.getX()) * right.getStepX()
                        + (frame.getZ() - base.getZ()) * right.getStepZ());

        if (facing == Direction.UP) {
            Direction down = right.getClockWise();
            int row = (int) Math.round(
                    (frame.getX() - base.getX()) * down.getStepX()
                            + (frame.getZ() - base.getZ()) * down.getStepZ());
            return new int[] {col, row};
        }

        int row = (int) Math.round(base.getY() - frame.getY());
        return new int[] {col, row};
    }

    private static long cellKey(int col, int row) {
        return ((long) col << 32) | (row & 0xFFFFFFFFL);
    }

    /** Wall frames share a plane when equidistant from the wall; floor
     * frames share a plane when they're at the same Y level. */
    private static boolean onSamePlane(
            ItemFrame base, ItemFrame other, Direction facing) {
        if (facing == Direction.UP) {
            return Math.abs(other.getY() - base.getY()) < 0.01;
        }

        double delta = (other.getX() - base.getX()) * facing.getStepX()
                + (other.getZ() - base.getZ()) * facing.getStepZ();
        return Math.abs(delta) < 0.01;
    }

    private static MapItemSavedData MapItemSavedData(Minecraft client, ItemFrame frame) {
        ItemStack stack = frame.getItem();

        if (client.level == null || !(stack.getItem() instanceof MapItem)) {
            return null;
        }

        // The stack-taking overload avoids the data-components API, whose
        // accessor methods have shifted between 1.21.x releases.
        return MapItem.getSavedData(stack, client.level);
    }

    /**
     * The packed map color displayed at (x, y) once the frame's quarter-turn
     * rotation is applied — i.e. what the wall actually shows there.
     */
    private static int rotatedPacked(MapItemSavedData state, int rotation, int x, int y) {
        int srcX;
        int srcY;

        switch (rotation) {
            case 1 -> {
                srcX = y;
                srcY = TILE - 1 - x;
            }
            case 2 -> {
                srcX = TILE - 1 - x;
                srcY = TILE - 1 - y;
            }
            case 3 -> {
                srcX = TILE - 1 - y;
                srcY = x;
            }
            default -> {
                srcX = x;
                srcY = y;
            }
        }

        return state.colors[srcX + srcY * TILE] & 0xFF;
    }

    /**
     * Draws one 128x128 map into the stitched image, applying the frame's
     * quarter-turn rotation so the capture matches what the wall shows.
     */
    private static void drawTile(
            BufferedImage image, int destX, int destY, MapItemSavedData state, int rotation) {
        for (int y = 0; y < TILE; y++) {
            for (int x = 0; x < TILE; x++) {
                int packed = rotatedPacked(state, rotation, x, y);

                if (packed >> 2 == 0) {
                    // Color 0 is "unset"; leave the pixel transparent.
                    continue;
                }

                // Since 1.21.2 Minecraft standardized on ARGB, so
                // getRenderColor's value maps straight onto BufferedImage.
                // (Pre-1.21.2 it was ABGR — swizzling here caused a blue
                // tint on captures.)
                image.setRGB(destX + x, destY + y, MapColor.getColorFromPackedId(packed));
            }
        }
    }
}



