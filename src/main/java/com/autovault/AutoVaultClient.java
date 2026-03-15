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

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

@Environment(EnvType.CLIENT)
public class AutoVaultClient implements ClientModInitializer {

    public static final String MOD_ID = "autovault";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static KeyBinding toggleKey;
    private static boolean modEnabled = false;
    private static boolean hasInteractedThisCycle = false;
    private static String lastPreviewItemId = "";

    // Each entry: [outerField, innerField] — we try all of them
    private static final List<Field[]> fieldPairs = new ArrayList<>();
    private static boolean reflectionInitialized = false;

    @Override
    public void onInitializeClient() {
        LOGGER.info("AutoVault initializing...");
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
                LOGGER.info("AutoVault enabled.");
            } else {
                client.player.sendMessage(Text.literal("AutoVault: OFF").formatted(Formatting.RED), true);
                LOGGER.info("AutoVault disabled.");
            }
        }

        if (!modEnabled) return;

        HitResult hitResult = client.crosshairTarget;
        if (hitResult == null || hitResult.getType() != HitResult.Type.BLOCK) {
            resetCycleIfNeeded(""); return;
        }

        BlockHitResult blockHit = (BlockHitResult) hitResult;
        BlockPos targetPos = blockHit.getBlockPos();
        World world = client.world;

        BlockEntity blockEntity = world.getBlockEntity(targetPos);
        if (!(blockEntity instanceof VaultBlockEntity vault)) {
            resetCycleIfNeeded(""); return;
        }

        if (!reflectionInitialized) initReflection(vault);

        ItemStack previewItem = getVaultDisplayItem(vault);

        // Log every non-empty item we find (only once per change)
        String currentId = previewItem == null || previewItem.isEmpty() ? "" : previewItem.getItem().toString();
        if (!currentId.isEmpty() && !currentId.equals(lastPreviewItemId)) {
            LOGGER.info("AutoVault: Vault preview changed to: {}", currentId);
        }

        if (previewItem == null || previewItem.isEmpty()) {
            resetCycleIfNeeded(""); return;
        }

        if (!previewItem.isOf(Items.HEAVY_CORE)) {
            resetCycleIfNeeded(currentId); return;
        }

        if (hasInteractedThisCycle && currentId.equals(lastPreviewItemId)) return;
        if (!playerHasTrialKey(client)) {
            LOGGER.info("AutoVault: Heavy Core detected but no Trial Key!");
            return;
        }

        LOGGER.info("AutoVault: Heavy Core at {}! Interacting.", targetPos);
        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, blockHit);
        hasInteractedThisCycle = true;
        lastPreviewItemId = currentId;
    }

    private ItemStack getVaultDisplayItem(VaultBlockEntity vault) {
        for (Field[] pair : fieldPairs) {
            try {
                Object outer = pair[0].get(vault);
                if (outer == null) continue;
                Object item = pair[1].get(outer);
                if (item instanceof ItemStack stack && !stack.isEmpty()) {
                    return stack;
                }
            } catch (Exception ignored) {}
        }
        return ItemStack.EMPTY;
    }

    private void initReflection(VaultBlockEntity vault) {
        reflectionInitialized = true;
        LOGGER.info("AutoVault: Initializing reflection - trying ALL field pairs...");

        // Try every known field on VaultBlockEntity
        Field[] outerFields = VaultBlockEntity.class.getDeclaredFields();
        for (Field outer : outerFields) {
            outer.setAccessible(true);
            Object outerVal = null;
            try { outerVal = outer.get(vault); } catch (Exception ignored) {}
            if (outerVal == null) continue;
            if (outerVal.getClass().getSimpleName().contains("Logger")) continue;

            // Try every field on that object
            for (Field inner : outerVal.getClass().getDeclaredFields()) {
                inner.setAccessible(true);
                // Check if this field IS an ItemStack type
                if (inner.getType().getSimpleName().equals("class_1799")
                        || inner.getType() == ItemStack.class) {
                    fieldPairs.add(new Field[]{outer, inner});
                    LOGGER.info("AutoVault: Registered pair: {}.{} -> {}.{}",
                            outer.getDeclaringClass().getSimpleName(), outer.getName(),
                            outerVal.getClass().getSimpleName(), inner.getName());
                }
            }
        }

        LOGGER.info("AutoVault: {} field pairs registered. Will try all of them each tick.", fieldPairs.size());
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
