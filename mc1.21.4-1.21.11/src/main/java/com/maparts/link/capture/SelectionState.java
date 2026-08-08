package com.maparts.link.capture;

/**
 * In-memory two-corner selection made with /mapselect. Entity IDs are only
 * valid for the current world session; resolution happens at upload time and
 * failures simply ask the player to reselect.
 */
public final class SelectionState {
    private static Integer firstCornerId;
    private static Integer secondCornerId;

    private SelectionState() {}

    public static void setFirst(int entityId) {
        firstCornerId = entityId;
        secondCornerId = null;
    }

    public static void setSecond(int entityId) {
        secondCornerId = entityId;
    }

    public static boolean hasPendingFirst() {
        return firstCornerId != null && secondCornerId == null;
    }

    public static boolean hasSelection() {
        return firstCornerId != null && secondCornerId != null;
    }

    public static Integer firstId() {
        return firstCornerId;
    }

    public static Integer secondId() {
        return secondCornerId;
    }

    public static void clear() {
        firstCornerId = null;
        secondCornerId = null;
    }
}
