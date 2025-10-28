package dev.cypphi.eef.mixin;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;

import dev.cypphi.eef.EchestExporterFabric;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.text.Text;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HandledScreen.class)
public abstract class HandledScreenMixin<T extends ScreenHandler> extends Screen {
    @Shadow @Final protected T handler;

    @Unique private static final int ENDER_CHEST_SIZE = 27;
    @Unique private static final int BUTTON_WIDTH = 56;
    @Unique private static final int BUTTON_HEIGHT = 20;
    @Unique private static final String MINECRAFT_PREFIX = "minecraft:";
    @Unique private static final Text ENDER_CHEST_TITLE = Text.translatable("container.enderchest");
    @Unique private static final Gson GSON =
            new GsonBuilder().setPrettyPrinting().serializeNulls().create();

    protected HandledScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void addExportButton(CallbackInfo ci) {
        if (!((Object) this instanceof GenericContainerScreen)) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (!isViewingEnderChest(client)) {
            return;
        }

        addDrawableChild(createExportButton());
    }

    @Unique
    private ButtonWidget createExportButton() {
        return ButtonWidget.builder(Text.literal("Export"), button -> exportEnderChest())
                .dimensions(0, 0, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build();
    }

    @Unique
    private boolean isViewingEnderChest(MinecraftClient client) {
        return client.player != null && ENDER_CHEST_TITLE.equals(getTitle());
    }

    @Unique
    private void exportEnderChest() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return;
        }

        JsonObject payload = buildExportPayload(client);
        writeExportFile(client, payload);
    }

    @Unique
    private JsonObject buildExportPayload(MinecraftClient client) {
        JsonArray slots = new JsonArray();
        RegistryWrapper.WrapperLookup lookup =
                client.world != null ? client.world.getRegistryManager() : null;

        for (int slotIndex = 0; slotIndex < ENDER_CHEST_SIZE; slotIndex++) {
            ItemStack stack = handler.getSlot(slotIndex).getStack();
            slots.add(serializeSlot(stack, slotIndex, lookup));
        }

        JsonObject root = new JsonObject();
        root.add("items", slots);
        root.addProperty("uuid", getSessionUuid(client));
        return root;
    }

    @Unique
    private JsonObject serializeSlot(ItemStack stack, int slotIndex, RegistryWrapper.WrapperLookup lookup) {
        JsonObject slot = new JsonObject();
        slot.addProperty("slot", slotIndex);

        if (stack.isEmpty()) {
            slot.add("item", JsonNull.INSTANCE);
            return slot;
        }

        JsonObject item = new JsonObject();
        item.addProperty("id", withoutMinecraftPrefix(Registries.ITEM.getId(stack.getItem()).toString()));
        item.addProperty("count", stack.getCount());

        if (lookup != null) {
            appendStackNbt(stack, item, lookup);
        }

        slot.add("item", item);
        return slot;
    }

    @Unique
    private void appendStackNbt(ItemStack stack, JsonObject item, RegistryWrapper.WrapperLookup lookup) {
        DynamicOps<NbtElement> nbtOps = lookup.getOps(NbtOps.INSTANCE);
        DynamicOps<JsonElement> jsonOps = lookup.getOps(JsonOps.INSTANCE);

        ItemStack.CODEC.encodeStart(nbtOps, stack).result().ifPresent(nbt -> {
            if (nbt instanceof NbtCompound compound) {
                compound.remove("id");
                compound.remove("count");
                if (!compound.isEmpty()) {
                    JsonElement nbtJson = nbtOps.convertTo(jsonOps, compound);
                    item.add("nbt", stripMinecraftNamespace(nbtJson));
                }
            }
        });
    }

    @Unique
    private void writeExportFile(MinecraftClient client, JsonObject payload) {
        String username = client.getSession().getUsername();
        Path path = Paths.get(System.getProperty("user.home"), "Documents", username + "_enderchest.json");

        try {
            Files.createDirectories(path.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(
                    path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                GSON.toJson(payload, writer);
            }
        } catch (IOException exception) {
            EchestExporterFabric.LOGGER.error("Failed to export ender chest", exception);
        }
    }

    @Unique
    private String getSessionUuid(MinecraftClient client) {
        UUID uuid = client.getSession().getUuidOrNull();
        return uuid != null ? uuid.toString() : null;
    }

    @Unique
    private String withoutMinecraftPrefix(String value) {
        return value.startsWith(MINECRAFT_PREFIX)
                ? value.substring(MINECRAFT_PREFIX.length())
                : value;
    }

    @Unique
    private JsonElement stripMinecraftNamespace(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return element;
        }

        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            JsonObject stripped = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                String key = withoutMinecraftPrefix(entry.getKey());
                stripped.add(key, stripMinecraftNamespace(entry.getValue()));
            }
            return stripped;
        }

        if (element.isJsonArray()) {
            JsonArray arr = element.getAsJsonArray();
            JsonArray stripped = new JsonArray();
            for (JsonElement entry : arr) {
                stripped.add(stripMinecraftNamespace(entry));
            }
            return stripped;
        }

        if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isString()) {
                String value = primitive.getAsString();
                if (value.startsWith(MINECRAFT_PREFIX)) {
                    return new JsonPrimitive(withoutMinecraftPrefix(value));
                }
            }
            return primitive;
        }

        return element;
    }
}
