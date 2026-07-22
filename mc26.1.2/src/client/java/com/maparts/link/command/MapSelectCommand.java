package com.maparts.link.command;

import com.maparts.link.capture.MapGridCapture;
import com.maparts.link.capture.SelectionState;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

/**
 * /mapselect — run it looking at one corner frame of a mapart, then again
 * looking at the opposite corner; /mapupload then captures exactly that
 * rectangle. Running it again after a completed selection starts a new one.
 */
public final class MapSelectCommand {
    private MapSelectCommand() {}

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
                ClientCommands.literal("mapselect")
                        .executes(MapSelectCommand::run));
    }

    private static int run(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        Minecraft client = source.getClient();

        ItemFrame frame = MapGridCapture.targetedMapFrame(client);
        String invalid = MapGridCapture.validateWallFrame(client, frame);

        if (invalid != null) {
            source.sendError(Component.literal(invalid));
            return 0;
        }

        if (!SelectionState.hasPendingFirst()) {
            SelectionState.setFirst(frame.getId());
            source.sendFeedback(Component.literal(
                    "Corner 1 set. Look at the opposite corner frame and run"
                            + " /mapselect again.").withStyle(ChatFormatting.GRAY));
            return Command.SINGLE_SUCCESS;
        }

        Entity first = client.level.getEntity(SelectionState.firstId());

        if (!(first instanceof ItemFrame firstFrame)
                || MapGridCapture.validateWallFrame(client, firstFrame) != null) {
            SelectionState.setFirst(frame.getId());
            source.sendFeedback(Component.literal(
                    "The first corner is gone — starting over. Corner 1 set;"
                            + " look at the opposite corner and run /mapselect"
                            + " again.").withStyle(ChatFormatting.GRAY));
            return Command.SINGLE_SUCCESS;
        }

        // The same frame twice is a valid 1x1 selection.
        String mismatch = MapGridCapture.validateSameWall(firstFrame, frame);

        if (mismatch != null) {
            source.sendError(Component.literal(mismatch));
            return 0;
        }

        SelectionState.setSecond(frame.getId());
        int[] size = MapGridCapture.gridSize(firstFrame, frame);
        source.sendFeedback(Component.literal(
                "Selected a " + size[0] + "x" + size[1]
                        + " mapart — run /mapupload [title] to upload it.")
                .withStyle(ChatFormatting.GREEN));

        return Command.SINGLE_SUCCESS;
    }
}



