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

@Environment(EnvType.CLIENT)
public class AutoVaultClient implements ClientModInitializer {

    public static final String MOD_ID = "autovault";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static KeyBinding toggleKey;
    private static boolean modEnabled = false;
    private static boolean hasInteractedThisCycle = false;
    private static String lastPreviewItemId = "";

    private static Field sharedDataField = null;
    private static Field displayItemField = null;
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

        if (previewItem == null || previewItem.isEmpty()) {
            resetCycleIfNeeded(""); return;
        }

        if (!previewItem.isOf(Items.HEAVY_CORE)) {
            resetCycleIfNeeded(previewItem.getItem().toString()); return;
        }

        String currentItemId = previewItem.getItem().toString();
        if (hasInteractedThisCycle && currentItemId.equals(lastPreviewItemId)) return;
        if (!playerHasTrialKey(client)) {
            LOGGER.info("AutoVault: Heavy Core detected but no Trial Key!");
            return;
        }

        LOGGER.info("AutoVault: Heavy Core at {}! Interacting.", targetPos);
        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, blockHit);
        hasInteractedThisCycle = true;
        lastPreviewItemId = currentItemId;
    }

    private ItemStack getVaultDisplayItem(VaultBlockEntity vault) {
        try {
            if (sharedDataField == null || displayItemField == null) return ItemStack.EMPTY;
            Object sharedData = sharedDataField.get(vault);
            if (sharedData == null) return ItemStack.EMPTY;
            Object item = displayItemField.get(sharedData);
            if (item instanceof ItemStack stack) return stack;
        } catch (Exception e) {
            LOGGER.warn("AutoVault: Failed to read display item: {}", e.getMessage());
        }
        return ItemStack.EMPTY;
    }

    private void initReflection(VaultBlockEntity vault) {
        reflectionInitialized = true;
        try {
            sharedDataField = VaultBlockEntity.class.getDeclaredField("field_48867");
            sharedDataField.setAccessible(true);
            Object sharedData = sharedDataField.get(vault);
            if (sharedData == null) {
                LOGGER.warn("AutoVault: sharedData is null, trying fallback...");
                initReflectionFallback(vault);
                return;
            }
            displayItemField = sharedData.getClass().getDeclaredField("field_48896");
            displayItemField.setAccessible(true);
            LOGGER.info("AutoVault: Reflection ready! Watching {}.field_48896 for Heavy Core.",
                    sharedData.getClass().getSimpleName());
        } catch (NoSuchFieldException e) {
            LOGGER.warn("AutoVault: Hardcoded field not found ({}), trying fallback...", e.getMessage());
            initReflectionFallback(vault);
        } catch (Exception e) {
            LOGGER.warn("AutoVault: Reflection init failed: {}", e.getMessage());
        }
    }

    private void initReflectionFallback(VaultBlockEntity vault) {
        try {
            for (Field field : VaultBlockEntity.class.getDeclaredFields()) {
                field.setAccessible(true);
                Object value = null;
                try { value = field.get(vault); } catch (Exception ignored) {}
                if (value == null) continue;
                if (value.getClass().getSimpleName().contains("Logger")) continue;
                for (Field inner : value.getClass().getDeclaredFields()) {
                    inner.setAccessible(true);
                    if (inner.getType().getSimpleName().equals("class_1799")
                            || inner.getType() == ItemStack.class) {
                        sharedDataField = field;
                        displayItemField = inner;
                        LOGGER.info("AutoVault: Fallback resolved: {}.{}", field.getName(), inner.getName());
                        return;
                    }
                }
            }
            LOGGER.warn("AutoVault: Could not find display item field. Vault detection disabled.");
        } catch (Exception e) {
            LOGGER.warn("AutoVault: Fallback error: {}", e.getMessage());
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
