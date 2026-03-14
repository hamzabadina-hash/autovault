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
import java.lang.reflect.Method;

@Environment(EnvType.CLIENT)
public class AutoVaultClient implements ClientModInitializer {

    public static final String MOD_ID = "autovault";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static KeyBinding toggleKey;
    private static boolean modEnabled = false;
    private static boolean hasInteractedThisCycle = false;
    private static String lastPreviewItemId = "";

    private static Field sharedDataField = null;
    private static Method getDisplayItemMethod = null;
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

        ItemStack previewItem = getVaultDisplayItemSafely(vault);
        if (previewItem == null || previewItem.isEmpty()) {
            resetCycleIfNeeded(""); return;
        }

        if (!previewItem.isOf(Items.HEAVY_CORE)) {
            resetCycleIfNeeded(previewItem.getItem().toString()); return;
        }

        String currentItemId = previewItem.getItem().toString();
        if (hasInteractedThisCycle && currentItemId.equals(lastPreviewItemId)) return;
        if (!playerHasTrialKey(client)) return;

        LOGGER.info("AutoVault: Heavy Core at {}! Interacting.", targetPos);
        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, blockHit);
        hasInteractedThisCycle = true;
        lastPreviewItemId = currentItemId;
    }

    private ItemStack getVaultDisplayItemSafely(VaultBlockEntity vault) {
        try {
            if (!reflectionInitialized) initReflection(vault);
            if (sharedDataField == null || getDisplayItemMethod == null) return ItemStack.EMPTY;
            Object sharedData = sharedDataField.get(vault);
            if (sharedData == null) return ItemStack.EMPTY;
            Object result = getDisplayItemMethod.invoke(sharedData);
            if (result instanceof ItemStack stack) return stack;
            return ItemStack.EMPTY;
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    private void initReflection(VaultBlockEntity vault) {
        reflectionInitialized = true;
        try {
            for (Field field : VaultBlockEntity.class.getDeclaredFields()) {
                field.setAccessible(true);
                Object value = null;
                try { value = field.get(vault); } catch (Exception ignored) {}
                if (value == null) continue;
                for (Method method : value.getClass().getMethods()) {
                    if (method.getParameterCount() == 0
                            && method.getReturnType() == ItemStack.class
                            && method.getName().toLowerCase().contains("display")) {
                        sharedDataField = field;
                        getDisplayItemMethod = method;
                        LOGGER.info("AutoVault: Found via {}.{}", value.getClass().getSimpleName(), method.getName());
                        return;
                    }
                }
            }
            for (String name : new String[]{"sharedData", "field_53026", "shared", "data"}) {
                if (tryFieldByName(vault, name)) return;
            }
            LOGGER.warn("AutoVault: Could not locate vault preview field — mod will not crash but won't auto-interact.");
        } catch (Exception e) {
            LOGGER.warn("AutoVault: Reflection init error: {}", e.getMessage());
        }
    }

    private boolean tryFieldByName(VaultBlockEntity vault, String name) {
        try {
            Field field = VaultBlockEntity.class.getDeclaredField(name);
            field.setAccessible(true);
            Object value = field.get(vault);
            if (value == null) return false;
            for (Method method : value.getClass().getMethods()) {
                if (method.getParameterCount() == 0 && method.getReturnType() == ItemStack.class) {
                    sharedDataField = field;
                    getDisplayItemMethod = method;
                    LOGGER.info("AutoVault: Resolved via field '{}' method '{}'", name, method.getName());
                    return true;
                }
            }
        } catch (NoSuchFieldException ignored) {
        } catch (Exception e) {
            LOGGER.warn("AutoVault: tryFieldByName('{}') error: {}", name, e.getMessage());
        }
        return false;
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
