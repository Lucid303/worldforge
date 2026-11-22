package dev.recipeforge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * "New recipe" for another mod's machine: pick the machine type, and World Forge starts from a copy of one of
 * that machine's existing recipes, so the format is always exactly what the mod expects.
 */
public class ModTypePickerScreen extends ForgeScreen {
    private static final int ROW = 22;

    private record TypeRow(String serializerId, String exampleId, int count) {}

    private final String presetResult;
    private final List<TypeRow> rows = new ArrayList<>();
    private String awaitingId = null;
    private int page = 0;
    private int perPage = 1;
    private int left;
    private int boxWidth;
    private final int top = 34;

    public ModTypePickerScreen(Screen parent, String presetResult) {
        super(Component.literal("Other mods"), parent);
        this.presetResult = presetResult;
    }

    private void loadRows() {
        rows.clear();
        if (this.minecraft == null || this.minecraft.level == null) {
            return;
        }
        Map<String, List<String>> byType = new TreeMap<>();
        for (RecipeHolder<?> holder : this.minecraft.level.getRecipeManager().getRecipes()) {
            ResourceLocation key = BuiltInRegistries.RECIPE_SERIALIZER.getKey(holder.value().getSerializer());
            if (key == null || key.getNamespace().equals("minecraft") || key.getNamespace().equals("worldforge")) {
                continue;
            }
            byType.computeIfAbsent(key.toString(), k -> new ArrayList<>()).add(holder.id().toString());
        }
        for (Map.Entry<String, List<String>> e : byType.entrySet()) {
            List<String> ids = e.getValue();
            ids.sort(String::compareTo);
            rows.add(new TypeRow(e.getKey(), ids.get(0), ids.size()));
        }
    }

    @Override
    protected void init() {
        loadRows();
        boxWidth = Math.min(this.width - 20, 460);
        left = (this.width - boxWidth) / 2;
        perPage = Math.max(1, (this.height - top - 40) / ROW);
        page = Math.max(0, Math.min(page, (rows.size() - 1) / perPage));
        for (int i = 0; i < perPage; i++) {
            int index = page * perPage + i;
            if (index >= rows.size()) {
                break;
            }
            final TypeRow row = rows.get(index);
            this.addRenderableWidget(Button.builder(
                    Component.literal(this.font.plainSubstrByWidth(row.serializerId() + "   (" + row.count() + " recipes)", boxWidth - 40)),
                    b -> {
                        awaitingId = row.exampleId();
                        PacketDistributor.sendToServer(new RecipeEditPayload("GET_RECIPE", awaitingId, ""));
                        status = "Opening...";
                    }).bounds(left + 24, top + i * ROW, boxWidth - 24, ROW - 2).build());
        }
        this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.onClose())
                .bounds(this.width - 78, this.height - 28, 70, 20).build());
        addPager(this.width / 2, this.height - 28, () -> changePage(-1), () -> changePage(1));
    }

    private void changePage(int delta) {
        int max = Math.max(0, (rows.size() - 1) / perPage);
        int newPage = Math.max(0, Math.min(max, page + delta));
        if (newPage != page) {
            page = newPage;
            this.rebuildWidgets();
        }
    }

    @Override
    protected void scrollPage(int delta) {
        changePage(delta);
    }

    @Override
    public void onRecipeJson(String id, String json) {
        if (!id.equals(awaitingId)) {
            return;
        }
        awaitingId = null;
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            this.minecraft.setScreen(new ModRecipeScreen(parent, null, obj, presetResult));
        } catch (Exception e) {
            status = "Could not open that: " + e.getMessage();
        }
    }

    @Override
    public void onRecipeError(String id, String message) {
        awaitingId = null;
        status = message;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        StationInfo.banner(g, this.font, this.width, StationInfo.recipes(), "Pick a machine type");
        for (int i = 0; i < perPage; i++) {
            int index = page * perPage + i;
            if (index >= rows.size()) {
                break;
            }
            g.renderItem(new ItemStack(StationInfo.forType(rows.get(index).serializerId()).icon()), left + 4, top + i * ROW + 2);
        }
        if (rows.isEmpty()) {
            g.drawCenteredString(this.font, "No machine recipes from other mods were found.", this.width / 2, 80, 0xFF8888);
        }
        drawPager(g, this.width / 2, this.height - 28, page, (rows.size() - 1) / perPage + 1);
        drawStatus(g, left, this.height - 52, boxWidth);
    }
}
