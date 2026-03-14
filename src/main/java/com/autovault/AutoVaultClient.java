package com.autovault;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.VaultBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Environment(EnvType.CLIENT)
public class AutoVaultClient implements ClientModInitializer {

    public static final String MOD_ID = "autovault";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static KeyBinding toggleKey;
    private static boolean modEnabled = false;
    private static boolean hasInteractedThisCycle = false;
    private static String lastPreviewItemId = "";

    @Override
    public void onInitializeClient() {
        LOGGER.info("AutoVault client initializing...");

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autovault.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                "category.autovault"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
        LOGGER.info("AutoVault ready! Press G to toggle ON/OFF.");
    }

    private void onClientTick(MinecraftClient client) {
        if (client.player == null || client.world == null) return;

        while (toggleKey.wasPressed()) {
            modEnabled = !modEnabled;
            hasInteractedThisCycle = false;
            lastPreviewItemId = "";

            if (modEnabled) {
                client.player.sendMessage(Text.literal("AutoVault: ON").formatted(Formatting.GREEN), true);
            } else {
                client.player.sendMessage(Text.literal("AutoVault: OFF").formatted(Formatting.RED), true);
            }
        }

        if (!modEnabled) return;

        HitResult hitResult = client.crosshairTarget;
        if (hitResult == null || hitResult.getType() != HitResult.Type.BLOCK) {
            resetCycleIfNeeded("");
            return;
        }

        BlockHitResult blockHit = (BlockHitResult) hitResult;
        BlockPos targetPos = blockHit.getBlockPos();
        World world = client.world;

        BlockEntity blockEntity = world.getBlockEntity(targetPos);
        if (!(blockEntity instanceof VaultBlockEntity vault)) {
            resetCycleIfNeeded("");
            return;
        }

        ItemStack previewItem = getVaultPreviewItem(vault);
        if (previewItem == null || previewItem.isEmpty()) {
            resetCycleIfNeeded("");
            return;
        }

        if (!previewItem.isOf(Items.HEAVY_CORE)) {
            resetCycleIfNeeded(previewItem.getItem().toString());
            return;
        }

        String currentItemId = previewItem.getItem().toString();
        if (hasInteractedThisCycle && currentItemId.equals(lastPreviewItemId)) return;

        if (!playerHasTrialKey(client)) return;

        LOGGER.info("AutoVault: Heavy Core detected at {}. Interacting...", targetPos);
        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, blockHit);

        hasInteractedThisCycle = true;
        lastPreviewItemId = currentItemId;
    }

    private ItemStack getVaultPreviewItem(VaultBlockEntity vault) {
        try {
            var sharedData = vault.getSharedData();
            if (sharedData == null) return ItemStack.EMPTY;
            return sharedData.getDisplayItem();
        } catch (Exception e) {
            LOGGER.warn("AutoVault: Could not read vault preview item: {}", e.getMessage());
            return ItemStack.EMPTY;
        }
    }

    private boolean playerHasTrialKey(MinecraftClient client) {
        if (client.player == null) return false;
        PlayerInventory inventory = client.player.getInventory();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty() && stack.isOf(Items.TRIAL_KEY)) return true;
        }
        return false;
    }

    private void resetCycleIfNeeded(String currentItemId) {
        if (!currentItemId.equals(lastPreviewItemId)) {
            hasInteractedThisCycle = false;
            lastPreviewItemId = currentItemId;
        }
    }
}
