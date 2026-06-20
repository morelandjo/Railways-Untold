package com.vodmordia.railwaysuntold.client;

import com.vodmordia.railwaysuntold.RailwaysUntold;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Copies targeted block coordinates to clipboard when Ctrl+C is pressed with the F3 debug screen open.
 */
@EventBusSubscriber(modid = RailwaysUntold.MODID, value = Dist.CLIENT)
public class CopyCoordinatesHandler {

    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        if (event.getKey() != GLFW.GLFW_KEY_C || event.getAction() != GLFW.GLFW_PRESS) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();

        // Only when Ctrl is held and F3 debug overlay is showing
        boolean ctrlHeld = (event.getModifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
        if (!ctrlHeld || !mc.getDebugOverlay().showDebugScreen() || mc.player == null) {
            return;
        }

        // Use block-only raycast so we get coordinates even when an entity (e.g. fake track) is in the way
        double reach = mc.player.blockInteractionRange();
        HitResult hitResult = mc.player.pick(reach, 1.0f, false);
        if (hitResult.getType() != HitResult.Type.BLOCK) {
            mc.player.displayClientMessage(Component.literal("No block targeted"), true);
            return;
        }

        BlockPos pos = ((BlockHitResult) hitResult).getBlockPos();
        String coords = pos.getX() + " " + pos.getY() + " " + pos.getZ();
        mc.keyboardHandler.setClipboard(coords);
        mc.player.displayClientMessage(Component.literal("Copied: " + coords), true);
    }
}
