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

    private static final List<FieldMethodPair> candidates = new ArrayList<>();
    private static boolean reflectionInitialized = false;

    private record FieldMethodPair(Field field, Method method, String desc) {}

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

        if (!reflectionInitialized) {
            initReflection(vault);
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
        for (FieldMethodPair pair : candidates) {
            try {
                Object fieldValue = pair.field().get(vault);
                if (fieldValue == null) continue;
                Object result = pair.method().invoke(fieldValue);
                if (result instanceof ItemStack stack && !stack.isEmpty()) {
                    return stack;
                }
            } catch (Exception ignored) {}
        }
        return ItemStack.EMPTY;
    }

    private void initReflection(VaultBlockEntity vault) {
        reflectionInitialized = true;
        LOGGER.info("AutoVault: Scanning ALL VaultBlockEntity fields for ItemStack getters...");

        Field[] fields = VaultBlockEntity.class.getDeclaredFields();
        LOGGER.info("AutoVault: {} declared fields total", fields.length);

        for (Field field : fields) {
            field.setAccessible(true);
            Object value = null;
            try { value = field.get(vault); } catch (Exception ignored) {}

            LOGGER.info("AutoVault: Checking field='{}' type='{}' value='{}'",
                    field.getName(), field.getType().getSimpleName(),
                    value == null ? "null" : value.getClass().getSimpleName());

            if (value == null) continue;

            for (Method method : value.getClass().getDeclaredMethods()) {
                method.setAccessible(true);
                if (method.getParameterCount() == 0 && method.getReturnType() == ItemStack.class) {
                    LOGGER.info("AutoVault: + declared method '{}' on field '{}'", method.getName(), field.getName());
                    candidates.add(new FieldMethodPair(field, method, field.getName() + "." + method.getName()));
                }
            }
            for (Method method : value.getClass().getMethods()) {
                if (method.getParameterCount() == 0 && method.getReturnType() == ItemStack.class) {
                    boolean alreadyAdded = candidates.stream().anyMatch(
                            p -> p.field() == field && p.method().getName().equals(method.getName()));
                    if (!alreadyAdded) {
                        LOGGER.info("AutoVault: + public method '{}' on field '{}'", method.getName(), field.getName());
                        candidates.add(new FieldMethodPair(field, method, field.getName() + "." + method.getName()));
                    }
                }
            }

            for (Field innerField : value.getClass().getDeclaredFields()) {
                innerField.setAccessible(true);
                Object innerValue = null;
                try { innerValue = innerField.get(value); } catch (Exception ignored) {}
                if (innerValue == null) continue;
                LOGGER.info("AutoVault:   inner field='{}' type='{}' value='{}'",
                        innerField.getName(), innerField.getType().getSimpleName(),
                        innerValue.getClass().getSimpleName());
                if (innerValue instanceof ItemStack) {
                    LOGGER.info("AutoVault:   -> direct ItemStack field: {}.{}", field.getName(), innerField.getName());
                }
            }
        }
        LOGGER.info("AutoVault: Found {} total candidates", candidates.size());
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
