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
    private static String lastChatMessage = "";

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

    private void chat(MinecraftClient client, String msg, Formatting color) {
        if (msg.equals(lastChatMessage)) return; // avoid spam
        lastChatMessage = msg;
        if (client.player != null) {
            client.player.sendMessage(Text.literal("[AV] " + msg).formatted(color), false);
        }
    }

    private void onClientTick(MinecraftClient client) {
        if (client.player == null || client.world == null) return;

        while (toggleKey.wasPressed()) {
            modEnabled = !modEnabled;
            hasInteractedThisCycle = false;
            lastPreviewItemId = "";
            lastChatMessage = "";
            if (modEnabled) {
                chat(client, "AutoVault ON - look at a vault!", Formatting.GREEN);
            } else {
                chat(client, "AutoVault OFF", Formatting.RED);
            }
        }

        if (!modEnabled) return;

        // Step 1: crosshair check
        HitResult hitResult = client.crosshairTarget;
        if (hitResult == null || hitResult.getType() != HitResult.Type.BLOCK) {
            resetCycleIfNeeded("");
            return;
        }

        BlockHitResult blockHit = (BlockHitResult) hitResult;
        BlockPos targetPos = blockHit.getBlockPos();
        World world = client.world;

        // Step 2: vault check
        BlockEntity blockEntity = world.getBlockEntity(targetPos);
        if (!(blockEntity instanceof VaultBlockEntity vault)) {
            resetCycleIfNeeded("");
            return;
        }

        // We're looking at a vault — show it
        chat(client, "Looking at vault at " + targetPos.toShortString(), Formatting.AQUA);

        if (!reflectionInitialized) initReflection(vault);

        // Step 3: read preview item
        ItemStack previewItem = getVaultDisplayItem(vault);
        String currentId = (previewItem == null || previewItem.isEmpty()) ? "EMPTY" : previewItem.getItem().toString();

        chat(client, "Vault preview: " + currentId, Formatting.YELLOW);

        if (previewItem == null || previewItem.isEmpty()) {
            resetCycleIfNeeded("");
            return;
        }

        // Step 4: is it Heavy Core?
        if (!previewItem.isOf(Items.HEAVY_CORE)) {
            chat(client, "Not Heavy Core (" + currentId + ")", Formatting.GRAY);
            resetCycleIfNeeded(currentId);
            return;
        }

        chat(client, "HEAVY CORE DETECTED!", Formatting.GOLD);

        if (hasInteractedThisCycle && currentId.equals(lastPreviewItemId)) {
            chat(client, "Already interacted this cycle", Formatting.DARK_GRAY);
            return;
        }

        // Step 5: trial key check
        if (!playerHasTrialKey(client)) {
            chat(client, "No Trial Key in inventory!", Formatting.RED);
            return;
        }

        // Step 6: interact!
        chat(client, "RIGHT CLICKING VAULT!", Formatting.GREEN);
        LOGGER.info("AutoVault: Interacting with vault at {}", targetPos);
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
        for (Field outer : VaultBlockEntity.class.getDeclaredFields()) {
            outer.setAccessible(true);
            Object outerVal = null;
            try { outerVal = outer.get(vault); } catch (Exception ignored) {}
            if (outerVal == null) continue;
            if (outerVal.getClass().getSimpleName().contains("Logger")) continue;
            for (Field inner : outerVal.getClass().getDeclaredFields()) {
                inner.setAccessible(true);
                if (inner.getType().getSimpleName().equals("class_1799")
                        || inner.getType() == ItemStack.class) {
                    fieldPairs.add(new Field[]{outer, inner});
                    LOGGER.info("AutoVault: Pair: {}.{}", outer.getName(), inner.getName());
                }
            }
        }
        LOGGER.info("AutoVault: {} pairs found", fieldPairs.size());
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
