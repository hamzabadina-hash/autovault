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

    // Action bar animation
    private static int animTick = 0;
    private static final String[] DOTS = {"   ", ".  ", ".. ", "..."};

    // One-time chat flags
    private static boolean sentVaultMessage = false;
    private static boolean sentHeavyCoreMessage = false;
    private static BlockPos lastVaultPos = null;

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
        if (client.player != null) {
            client.player.sendMessage(Text.literal(msg).formatted(color), false);
        }
    }

    private void actionBar(MinecraftClient client, String msg, Formatting color) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal(msg).formatted(color), true);
        }
    }

    private void onClientTick(MinecraftClient client) {
        if (client.player == null || client.world == null) return;

        animTick++;

        while (toggleKey.wasPressed()) {
            modEnabled = !modEnabled;
            hasInteractedThisCycle = false;
            lastPreviewItemId = "";
            sentVaultMessage = false;
            sentHeavyCoreMessage = false;
            lastVaultPos = null;
            if (modEnabled) {
                chat(client, "━━━━━━━━━━━━━━━━━━━━━━━", Formatting.DARK_AQUA);
                chat(client, "  AutoVault » ON", Formatting.GREEN);
                chat(client, "  Look at an Ominous Vault!", Formatting.GRAY);
                chat(client, "━━━━━━━━━━━━━━━━━━━━━━━", Formatting.DARK_AQUA);
            } else {
                chat(client, "━━━━━━━━━━━━━━━━━━━━━━━", Formatting.DARK_AQUA);
                chat(client, "  AutoVault » OFF", Formatting.RED);
                chat(client, "━━━━━━━━━━━━━━━━━━━━━━━", Formatting.DARK_AQUA);
            }
        }

        if (!modEnabled) return;

        HitResult hitResult = client.crosshairTarget;
        if (hitResult == null || hitResult.getType() != HitResult.Type.BLOCK) {
            // Reset when not looking at vault
            if (sentVaultMessage) {
                sentVaultMessage = false;
                sentHeavyCoreMessage = false;
                lastVaultPos = null;
            }
            resetCycleIfNeeded("");
            return;
        }

        BlockHitResult blockHit = (BlockHitResult) hitResult;
        BlockPos targetPos = blockHit.getBlockPos();
        World world = client.world;

        BlockEntity blockEntity = world.getBlockEntity(targetPos);
        if (!(blockEntity instanceof VaultBlockEntity vault)) {
            if (sentVaultMessage) {
                sentVaultMessage = false;
                sentHeavyCoreMessage = false;
                lastVaultPos = null;
            }
            resetCycleIfNeeded("");
            return;
        }

        // Send "Looking at vault" message only once per new vault
        if (!sentVaultMessage || !targetPos.equals(lastVaultPos)) {
            sentVaultMessage = true;
            sentHeavyCoreMessage = false;
            lastVaultPos = targetPos;
            chat(client, "━━━━━━━━━━━━━━━━━━━━━━━", Formatting.DARK_GRAY);
            chat(client, "  AutoVault » Looking at vault" , Formatting.AQUA);
            chat(client, "  Scanning preview items" + DOTS[animTick / 5 % 4], Formatting.GRAY);
            chat(client, "━━━━━━━━━━━━━━━━━━━━━━━", Formatting.DARK_GRAY);
        }

        if (!reflectionInitialized) initReflection(vault);

        ItemStack previewItem = getVaultDisplayItem(vault);
        String currentId = (previewItem == null || previewItem.isEmpty()) ? "" : previewItem.getItem().toString();

        // Animated action bar while scanning
        String dot = DOTS[animTick / 5 % 4];
        actionBar(client, "⬡ AutoVault » Scanning" + dot, Formatting.AQUA);

        if (previewItem == null || previewItem.isEmpty()) {
            resetCycleIfNeeded("");
            return;
        }

        if (!previewItem.isOf(Items.HEAVY_CORE)) {
            resetCycleIfNeeded(currentId);
            return;
        }

        // Heavy Core — send chat once
        if (!sentHeavyCoreMessage) {
            sentHeavyCoreMessage = true;
            chat(client, "━━━━━━━━━━━━━━━━━━━━━━━", Formatting.GOLD);
            chat(client, "  ⚠ HEAVY CORE DETECTED!", Formatting.GOLD);
            chat(client, "━━━━━━━━━━━━━━━━━━━━━━━", Formatting.GOLD);
        }

        if (hasInteractedThisCycle && currentId.equals(lastPreviewItemId)) {
            actionBar(client, "⬡ AutoVault » Already clicked this cycle!", Formatting.GOLD);
            return;
        }

        if (!playerHasTrialKey(client)) {
            actionBar(client, "⬡ AutoVault » No Ominous Key!", Formatting.RED);
            return;
        }

        // Fire!
        actionBar(client, "⬡ AutoVault » Clicking vault!", Formatting.GREEN);
        chat(client, "  ✔ Vault clicked!", Formatting.GREEN);
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
                }
            }
        }
    }

    private boolean playerHasTrialKey(MinecraftClient client) {
        if (client.player == null) return false;
        PlayerInventory inventory = client.player.getInventory();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty() && (stack.isOf(Items.TRIAL_KEY) || stack.isOf(Items.OMINOUS_TRIAL_KEY))) {
                return true;
            }
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
```

Commit → ✅ → download jar.

Here's what you'll see:

**When you press G:**
```
━━━━━━━━━━━━━━━━━━━━━━━
  AutoVault » ON
  Look at an Ominous Vault!
━━━━━━━━━━━━━━━━━━━━━━━
```

**When you look at a vault (once):**
```
━━━━━━━━━━━━━━━━━━━━━━━
  AutoVault » Looking at vault
  Scanning preview items...
━━━━━━━━━━━━━━━━━━━━━━━
```

**Action bar animates:** `⬡ AutoVault » Scanning...`

**When Heavy Core appears (once):**
```
━━━━━━━━━━━━━━━━━━━━━━━
  ⚠ HEAVY CORE DETECTED!
━━━━━━━━━━━━━━━━━━━━━━━
  ✔ Vault clicked!
