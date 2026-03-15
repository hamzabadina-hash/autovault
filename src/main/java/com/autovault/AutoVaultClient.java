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

    private static Field sharedDataField = null;
    private static Method getDisplayItemMethod = null;
    private static boolean reflectionInitialized = false;
    private static List<Method> directItemStackMethods = null;

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

        ItemStack previewItem = getVaultDisplayItem(vault);
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

    private ItemStack getVaultDisplayItem(VaultBlockEntity vault) {
        if (!reflectionInitialized) initReflection(vault);

        if (sharedDataField != null && getDisplayItemMethod != null) {
            try {
                Object sharedData = sharedDataField.get(vault);
                if (sharedData != null) {
                    Object result = getDisplayItemMethod.invoke(sharedData);
                    if (result instanceof ItemStack stack && !stack.isEmpty()) return stack;
                }
            } catch (Exception ignored) {}
        }

        if (directItemStackMethods != null) {
            for (Method m : directItemStackMethods) {
                try {
                    Object result = m.invoke(vault);
                    if (result instanceof ItemStack stack && !stack.isEmpty()) return stack;
                } catch (Exception ignored) {}
            }
        }

        return ItemStack.EMPTY;
    }

    private void initReflection(VaultBlockEntity vault) {
        reflectionInitialized = true;
        LOGGER.info("AutoVault: Scanning VaultBlockEntity fields...");

        Field[] fields = VaultBlockEntity.class.getDeclaredFields();
        LOGGER.info("AutoVault: {} declared fields found", fields.length);

        for (Field field : fields) {
            field.setAccessible(true);
            Object value = null;
            try { value = field.get(vault); } catch (Exception ignored) {}
            LOGGER.info("AutoVault: field='{}' type='{}' value='{}'",
                    field.getName(), field.getType().getSimpleName(),
                    value == null ? "null" : value.getClass().getSimpleName());
            if (value == null) continue;

            // Check declared methods (catches obfuscated names like method_XXXXX)
            for (Method method : value.getClass().getDeclaredMethods()) {
                m
