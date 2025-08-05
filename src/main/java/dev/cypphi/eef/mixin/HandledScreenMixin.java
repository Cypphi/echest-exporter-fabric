package dev.cypphi.eef.mixin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.text.Text;
import net.minecraft.nbt.*;
import net.minecraft.registry.RegistryWrapper;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.DynamicOps;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;

import dev.cypphi.eef.EchestExporterFabric;

@Mixin(HandledScreen.class)
public abstract class HandledScreenMixin<T extends ScreenHandler> extends Screen {
    @Shadow @Final protected T handler;
    @Shadow protected int x;
    @Shadow protected int y;
    @Shadow protected int backgroundWidth;

    protected HandledScreenMixin(Text title) {
        super(title);
    }

    @Unique
    private static final Text ENDER_CHEST_TITLE = Text.translatable("container.enderchest");

    @Inject(method = "init", at = @At("TAIL"))
    private void addExportButton(CallbackInfo ci) {
        if ((Object) this instanceof GenericContainerScreen) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null && ENDER_CHEST_TITLE.equals(this.getTitle())) {
                ButtonWidget button = ButtonWidget.builder(Text.literal("Export"), b -> exportEnderChest())
                        .dimensions(0, 0, 56, 20)
                        .build();
                this.addDrawableChild(button);
            }
        }
    }

    @Unique
    private void exportEnderChest() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        JsonArray items = new JsonArray();

        for (int i = 0; i < 27; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            JsonObject slotObj = new JsonObject();
            slotObj.addProperty("slot", i);
            if (!stack.isEmpty()) {
                JsonObject itemObj = new JsonObject();
                String id = net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).toString();
                if (id.startsWith("minecraft:")) id = id.substring("minecraft:".length());
                itemObj.addProperty("id", id);
                itemObj.addProperty("count", stack.getCount());
                if (client.world != null) {
                    RegistryWrapper.WrapperLookup lookup = client.world.getRegistryManager();
                    DynamicOps<NbtElement> nbtOps = lookup.getOps(NbtOps.INSTANCE);
                    DynamicOps<JsonElement> jsonOps = lookup.getOps(JsonOps.INSTANCE);
                    ItemStack.CODEC.encodeStart(nbtOps, stack).result().ifPresent(nbt -> {
                        if (nbt instanceof NbtCompound compound) {
                            compound.remove("id");
                            compound.remove("count");
                            if (!compound.isEmpty()) {
                                JsonElement nbtJson = nbtOps.convertTo(jsonOps, compound);
                                itemObj.add("nbt", stripMinecraftNamespace(nbtJson));
                            }
                        }
                    });
                }
                slotObj.add("item", itemObj);
            } else {
                slotObj.add("item", JsonNull.INSTANCE);
            }
            items.add(slotObj);
        }
        JsonObject root = new JsonObject();
        root.add("items", items);
        root.addProperty("username", client.getSession().getUsername());

        String home = System.getProperty("user.home");
        String filename = client.getSession().getUsername() + "_enderchest.json";
        Path path = Paths.get(home, "Documents", filename);
        try {
            Files.createDirectories(path.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                Gson gson = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
                gson.toJson(root, writer);
            }
        } catch (IOException e) {
            EchestExporterFabric.LOGGER.error("Failed to export ender chest", e);
        }
    }

    @Unique
    private JsonElement stripMinecraftNamespace(JsonElement element) {
        if (element == null || element.isJsonNull()) return element;
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            JsonObject stripped = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                String key = entry.getKey();
                if (key.startsWith("minecraft:")) key = key.substring("minecraft:".length());
                stripped.add(key, stripMinecraftNamespace(entry.getValue()));
            }
            return stripped;
        } else if (element.isJsonArray()) {
            JsonArray arr = element.getAsJsonArray();
            JsonArray stripped = new JsonArray();
            for (JsonElement e : arr) {
                stripped.add(stripMinecraftNamespace(e));
            }
            return stripped;
        } else if (element.isJsonPrimitive()) {
            JsonPrimitive prim = element.getAsJsonPrimitive();
            if (prim.isString()) {
                String value = prim.getAsString();
                if (value.startsWith("minecraft:")) {
                    return new JsonPrimitive(value.substring("minecraft:".length()));
                }
            }
            return prim;
        }
        return element;
    }
}