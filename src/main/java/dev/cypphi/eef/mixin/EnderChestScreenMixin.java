package dev.cypphi.eef.mixin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.DataResult;
import net.minecraft.nbt.NbtElement;
import net.minecraft.registry.RegistryOps;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.text.Text;
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

import dev.cypphi.eef.EchestExporterFabric;

@Mixin(HandledScreen.class)
public abstract class EnderChestScreenMixin<T extends ScreenHandler> extends Screen {
        @Shadow @Final protected T handler;
        @Shadow protected int x;
        @Shadow protected int y;
        @Shadow protected int backgroundWidth;

        protected EnderChestScreenMixin(Text title) {
                super(title);
        }

        @Unique
        private static final Text ENDER_CHEST_TITLE = Text.translatable("container.enderchest");

        @Inject(method = "init", at = @At("TAIL"))
        private void addExportButton(CallbackInfo ci) {
                EchestExporterFabric.LOGGER.debug("HandledScreen init for {}", this.getTitle().getString());
                if ((Object) this instanceof GenericContainerScreen) {
                        MinecraftClient client = MinecraftClient.getInstance();
                        Inventory inv = ((GenericContainerScreenHandler) this.handler).getInventory();
                        EchestExporterFabric.LOGGER.debug("Container inventory: {}", inv.getClass().getName());
                        if (client.player != null && ENDER_CHEST_TITLE.equals(this.getTitle())) {
                                EchestExporterFabric.LOGGER.debug("Adding export button for ender chest");
                                ButtonWidget button = ButtonWidget.builder(Text.literal("Export"), b -> exportEnderChest())
                                                .dimensions(0, 0, 56, 20)
                                                .build();
                                this.addDrawableChild(button);
                        } else {
                                EchestExporterFabric.LOGGER.debug("Not an ender chest screen: player={}, title={}", client.player, this.getTitle().getString());
                        }
                }
        }

	@Unique
	private void exportEnderChest() {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client.player == null) return;
                EchestExporterFabric.LOGGER.debug("Exporting ender chest for {}", client.getSession().getUsername());
                var inv = client.player.getEnderChestInventory();

		JsonArray items = new JsonArray();
        assert client.world != null;
        RegistryWrapper.WrapperLookup lookup = client.world.getRegistryManager();
		var ops = RegistryOps.of(NbtOps.INSTANCE, lookup);
		for (int i = 0; i < inv.size(); i++) {
			ItemStack stack = inv.getStack(i);
			if (!stack.isEmpty()) {
				DataResult<NbtElement> result = ItemStack.CODEC.encodeStart(ops, stack);
				result.result().ifPresentOrElse(el -> {
					JsonElement json = (JsonElement) NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, el);
					items.add(json);
				}, () -> EchestExporterFabric.LOGGER.error("Failed to encode stack {}", stack));
			} else {
				items.add(JsonNull.INSTANCE);
			}
		}
		JsonObject root = new JsonObject();
		root.add("items", items);
		root.addProperty("username", client.getSession().getUsername());

                String home = System.getProperty("user.home");
                String filename = client.getSession().getUsername() + "_enderchest.json";
                Path path = Paths.get(home, "Documents", filename);
                EchestExporterFabric.LOGGER.debug("Saving ender chest to {}", path);
		try {
			Files.createDirectories(path.getParent());
			try (BufferedWriter writer = Files.newBufferedWriter(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
				Gson gson = new GsonBuilder().setPrettyPrinting().create();
				gson.toJson(root, writer);
			}
			EchestExporterFabric.LOGGER.info("Ender chest exported to {}", path);
		} catch (IOException e) {
			EchestExporterFabric.LOGGER.error("Failed to export ender chest", e);
		}
	}
}