package dev.recipeforge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Picture editor for ANY mod's machine recipe (Create, Applied Energistics 2, Ars Nouveau ...).
 * It does not need to know the mod: it reads the recipe's own structure and shows item slots, fluid slots,
 * numbers, switches and lists, and changes only what you touch. Everything else in the recipe stays exactly as it was.
 */
public class ModRecipeScreen extends ForgeScreen {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int ROW = 22;
    private static final Set<String> HIDDEN = Set.of("type", "neoforge:conditions", "category", "group", "show_notification");
    private static final Map<String, List<String>> ENUMS = Map.of(
            "heat_requirement", List.of("none", "heated", "superheated"),
            "mode", List.of("inscribe", "press"));

    private sealed interface Row permits Header, SlotRow, NumRow, ToggleRow, CycleRow, TextRow {}

    private record Header(String label, Runnable add, Runnable addFluid) implements Row {}

    private record SlotRow(String label, JsonObject obj, Runnable remove) implements Row {}

    private record NumRow(String label, JsonObject owner, String key, boolean isInt) implements Row {}

    private record ToggleRow(String label, JsonObject owner, String key) implements Row {}

    private record CycleRow(String label, JsonObject owner, String key, List<String> options) implements Row {}

    private record TextRow(String label, String value) implements Row {}

    private final String recipeId;      // null when this is a new recipe
    private final JsonObject json;
    private final String typeId;
    private final List<Row> rows = new ArrayList<>();
    private int page = 0;
    private int perPage = 1;
    private int left;
    private int boxWidth;
    private final int top = 44;
    private JsonArray pendingFluidList = null;

    public ModRecipeScreen(Screen parent, String recipeId, JsonObject json, String presetResult) {
        super(Component.literal("World Forge"), parent);
        this.recipeId = recipeId;
        this.json = json;
        this.typeId = json.has("type") && json.get("type").isJsonPrimitive() ? json.get("type").getAsString() : "?";
        buildRows();
        if (presetResult != null) {
            for (Row r : rows) {
                if (r instanceof SlotRow s && isResultLabel(s.label())) {
                    setItem(s.obj(), presetResult);
                    if (s.obj().has("count")) {
                        s.obj().addProperty("count", 1);
                    }
                    break;
                }
            }
        }
    }

    // ------------------------------------------------------------ reading the recipe's structure

    private static boolean isResultLabel(String label) {
        String l = label.toLowerCase();
        return l.contains("result") || l.contains("output");
    }

    private static boolean isStack(JsonObject o) {
        return (o.has("item") && o.get("item").isJsonPrimitive())
                || (o.has("id") && o.get("id").isJsonPrimitive() && !o.has("fluid"))
                || (o.has("tag") && o.get("tag").isJsonPrimitive())
                || (o.has("fluid") && o.get("fluid").isJsonPrimitive())
                || (o.has("fluid_tag") && o.get("fluid_tag").isJsonPrimitive());
    }

    /** Some mods wrap an ingredient as {"item": {"item": "..."}}. */
    private static JsonObject unwrap(JsonObject o) {
        if (o.has("item") && o.get("item").isJsonObject() && isStack(o.getAsJsonObject("item"))) {
            return o.getAsJsonObject("item");
        }
        return o;
    }

    private static String pretty(String key) {
        String s = key.replaceAll("([a-z])([A-Z])", "$1 $2").replace('_', ' ');
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void buildRows() {
        rows.clear();
        walkObject(json, "");
    }

    private void walkObject(JsonObject obj, String prefix) {
        for (Map.Entry<String, JsonElement> e : new ArrayList<>(obj.entrySet())) {
            String key = e.getKey();
            JsonElement v = e.getValue();
            if (HIDDEN.contains(key)) {
                continue;
            }
            String label = prefix.isEmpty() ? pretty(key) : prefix + " / " + pretty(key);
            if (v.isJsonArray()) {
                walkArray(obj, key, v.getAsJsonArray(), label);
            } else if (v.isJsonObject()) {
                JsonObject inner = unwrap(v.getAsJsonObject());
                if (isStack(inner)) {
                    rows.add(new SlotRow(label, inner, null));
                } else {
                    walkObject(v.getAsJsonObject(), label);
                }
            } else if (v.isJsonPrimitive()) {
                JsonPrimitive p = v.getAsJsonPrimitive();
                if (p.isBoolean()) {
                    rows.add(new ToggleRow(label, obj, key));
                } else if (p.isNumber()) {
                    String text = p.getAsString();
                    rows.add(new NumRow(label, obj, key, !text.contains(".") && !text.toLowerCase().contains("e")));
                } else if (ENUMS.containsKey(key) && ENUMS.get(key).contains(p.getAsString())) {
                    rows.add(new CycleRow(label, obj, key, ENUMS.get(key)));
                } else {
                    rows.add(new TextRow(label, p.getAsString()));
                }
            }
        }
    }

    private static boolean looksLikeStackList(String key) {
        String k = key.toLowerCase();
        return k.contains("ingredient") || k.contains("input") || k.contains("result") || k.contains("output")
                || k.contains("item") || k.contains("pedestal") || k.contains("reagent");
    }

    private void walkArray(JsonObject owner, String key, JsonArray arr, String label) {
        boolean stacks = arr.isEmpty() ? looksLikeStackList(key) : true;
        for (JsonElement el : arr) {
            if (!el.isJsonObject() || !isStack(unwrap(el.getAsJsonObject()))) {
                stacks = false;
            }
        }
        if (!stacks) {
            rows.add(new TextRow(label, arr.size() + " entries (use the Text button to change these)"));
            return;
        }
        final boolean result = isResultLabel(label);
        rows.add(new Header(label, () -> {
            if (arr.size() > 0) {
                arr.add(arr.get(0).deepCopy());
            } else {
                JsonObject fresh = new JsonObject();
                if (result) {
                    fresh.addProperty("id", "minecraft:stone");
                    fresh.addProperty("count", 1);
                } else {
                    fresh.addProperty("item", "minecraft:stone");
                }
                arr.add(fresh);
            }
            changed();
        }, () -> requestFluid(arr)));
        int n = 1;
        for (JsonElement el : new ArrayList<>(arr.asList())) {
            final JsonElement element = el;
            rows.add(new SlotRow(label + " " + n++, unwrap(el.getAsJsonObject()), () -> {
                arr.remove(element);
                changed();
            }));
        }
    }

    private void requestFluid(JsonArray list) {
        pendingFluidList = list;
        PacketDistributor.sendToServer(new RecipeEditPayload("FLUID_TEMPLATE", typeId, ""));
        status = "Looking at this machine's other recipes to see how it writes a fluid...";
    }

    @Override
    public void onData(String kind, String id, String data) {
        if (!kind.equals("FLUID_TEMPLATE") || !id.equals(typeId) || pendingFluidList == null) {
            return;
        }
        JsonObject entry;
        try {
            entry = data.isEmpty() ? null : com.google.gson.JsonParser.parseString(data).getAsJsonObject();
        } catch (Exception e) {
            entry = null;
        }
        if (entry == null) {
            entry = new JsonObject();
            entry.addProperty("fluid", "minecraft:water");
            entry.addProperty("amount", 1000);
            status = "No example found, so the standard fluid format was used. If Save complains, use the Text button.";
        } else {
            if (entry.has("fluid")) {
                entry.addProperty("fluid", "minecraft:water");
            }
            if (entry.has("amount")) {
                entry.addProperty("amount", 1000);
            }
            status = "";
        }
        pendingFluidList.add(entry);
        pendingFluidList = null;
        changed();
    }

    private void changed() {
        buildRows();
        this.rebuildWidgets();
    }

    // ------------------------------------------------------------ small edits

    private static void setItem(JsonObject o, String id) {
        if (o.has("tag")) {
            o.remove("tag");
            o.addProperty("item", id);
        } else if (o.has("id")) {
            o.addProperty("id", id);
        } else {
            o.addProperty("item", id);
        }
    }

    private static void setTag(JsonObject o, String tag) {
        o.remove("item");
        o.addProperty("tag", tag);
    }

    private static void setFluid(JsonObject o, String fluid) {
        o.remove("fluid_tag");
        o.addProperty("fluid", fluid);
    }

    private static List<String> fluidIds() {
        List<String> ids = new ArrayList<>();
        BuiltInRegistries.FLUID.keySet().forEach(k -> {
            if (!k.getPath().startsWith("flowing_") && !k.getPath().equals("empty")) {
                ids.add(k.toString());
            }
        });
        ids.sort(String::compareTo);
        return ids;
    }

    private static List<String> itemTagIds() {
        List<String> ids = new ArrayList<>();
        BuiltInRegistries.ITEM.getTagNames().forEach(t -> ids.add(t.location().toString()));
        ids.sort(String::compareTo);
        return ids;
    }

    // ------------------------------------------------------------ pictures of a slot

    private static Item itemById(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl == null ? null : BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
    }

    private static ItemStack tagIcon(String tag) {
        ResourceLocation rl = ResourceLocation.tryParse(tag);
        if (rl == null) {
            return ItemStack.EMPTY;
        }
        Optional<HolderSet.Named<Item>> named = BuiltInRegistries.ITEM.getTag(TagKey.create(Registries.ITEM, rl));
        if (named.isPresent()) {
            for (Holder<Item> h : named.get()) {
                return new ItemStack(h.value());
            }
        }
        return new ItemStack(Items.BARRIER);
    }

    private static ItemStack iconOf(JsonObject o) {
        try {
            if (o.has("fluid") && o.get("fluid").isJsonPrimitive()) {
                ResourceLocation rl = ResourceLocation.tryParse(o.get("fluid").getAsString());
                if (rl != null) {
                    return BuiltInRegistries.FLUID.getOptional(rl).map(f -> new ItemStack(f.getBucket())).orElse(ItemStack.EMPTY);
                }
            }
            if (o.has("item") && o.get("item").isJsonPrimitive()) {
                Item i = itemById(o.get("item").getAsString());
                return i == null ? new ItemStack(Items.BARRIER) : new ItemStack(i);
            }
            if (o.has("id") && o.get("id").isJsonPrimitive()) {
                Item i = itemById(o.get("id").getAsString());
                return i == null ? new ItemStack(Items.BARRIER) : new ItemStack(i);
            }
            if (o.has("tag") && o.get("tag").isJsonPrimitive()) {
                return tagIcon(o.get("tag").getAsString());
            }
        } catch (Exception ignored) {
            // fall through
        }
        return new ItemStack(Items.WATER_BUCKET);
    }

    private static String textOf(JsonObject o) {
        try {
            if (o.has("fluid") && o.get("fluid").isJsonPrimitive()) {
                return o.get("fluid").getAsString();
            }
            if (o.has("fluid_tag") && o.get("fluid_tag").isJsonPrimitive()) {
                return "#" + o.get("fluid_tag").getAsString();
            }
            if (o.has("tag") && o.get("tag").isJsonPrimitive()) {
                return "#" + o.get("tag").getAsString();
            }
            String id = o.has("item") ? o.get("item").getAsString() : o.get("id").getAsString();
            Item i = itemById(id);
            return i == null ? id : new ItemStack(i).getHoverName().getString();
        } catch (Exception e) {
            return "?";
        }
    }

    // ------------------------------------------------------------ widgets

    private static Tooltip tip(String s) {
        return Tooltip.create(Component.literal(s));
    }

    @Override
    protected void init() {
        boxWidth = Math.min(this.width - 20, 460);
        left = (this.width - boxWidth) / 2;
        perPage = Math.max(1, (this.height - top - 60) / ROW);
        page = Math.max(0, Math.min(page, (rows.size() - 1) / perPage));

        for (int i = 0; i < perPage; i++) {
            int index = page * perPage + i;
            if (index >= rows.size()) {
                break;
            }
            int y = top + i * ROW;
            switch (rows.get(index)) {
                case Header h -> {
                    if (h.add() != null) {
                        this.addRenderableWidget(Button.builder(Component.literal("Add"), b -> h.add().run())
                                .bounds(left + 200, y + 2, 50, 16).tooltip(tip("Add another item to this list.")).build());
                    }
                    if (h.addFluid() != null) {
                        this.addRenderableWidget(Button.builder(Component.literal("Add fluid"), b -> h.addFluid().run())
                                .bounds(left + 254, y + 2, 64, 16)
                                .tooltip(tip("Add a fluid, like water or lava. Then pick the fluid and set how many mB.")).build());
                    }
                }
                case SlotRow s -> addSlotButtons(s, y);
                case NumRow n -> addNumButtons(n, y);
                case ToggleRow t -> this.addRenderableWidget(Button.builder(
                        Component.literal(t.owner().get(t.key()).getAsBoolean() ? "On" : "Off"), b -> {
                            boolean now = t.owner().get(t.key()).getAsBoolean();
                            t.owner().addProperty(t.key(), !now);
                            b.setMessage(Component.literal(!now ? "On" : "Off"));
                        }).bounds(left + 200, y + 2, 60, 16).build());
                case CycleRow c -> this.addRenderableWidget(Button.builder(
                        Component.literal(c.owner().get(c.key()).getAsString()), b -> {
                            int at = c.options().indexOf(c.owner().get(c.key()).getAsString());
                            String next = c.options().get((at + 1) % c.options().size());
                            c.owner().addProperty(c.key(), next);
                            b.setMessage(Component.literal(next));
                        }).bounds(left + 200, y + 2, 100, 16).tooltip(tip("Click to switch to the next choice.")).build());
                case TextRow t -> { }
            }
        }

        int by = this.height - 28;
        this.addRenderableWidget(Button.builder(Component.literal("Save"), b -> save())
                .bounds(left, by, 70, 20).tooltip(tip("Apply your changes to the game.")).build());
        this.addRenderableWidget(Button.builder(Component.literal("Text"), b -> openText())
                .bounds(left + 74, by, 70, 20)
                .tooltip(tip("Advanced: edit the recipe as text.")).build());
        this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.onClose())
                .bounds(this.width - 78, by, 70, 20).build());
        addPager(this.width - 144, by, () -> changePage(-1), () -> changePage(1));
    }

    private void addSlotButtons(SlotRow s, int y) {
        final JsonObject o = s.obj();
        boolean fluid = o.has("fluid") || o.has("fluid_tag");
        boolean ingredientLike = (o.has("item") || o.has("tag")) && !fluid;
        if (fluid) {
            this.addRenderableWidget(Button.builder(Component.literal("Fluid"), b ->
                    this.minecraft.setScreen(new IdPickerScreen(this, "Pick a fluid", fluidIds(), id -> setFluid(o, id))))
                    .bounds(left + 140, y + 2, 38, 16).tooltip(tip("Change the fluid.")).build());
        } else {
            this.addRenderableWidget(Button.builder(Component.literal("Item"), b ->
                    this.minecraft.setScreen(new ItemPickerScreen(this, "Pick the item", false, id -> setItem(o, id))))
                    .bounds(left + 140, y + 2, 38, 16).tooltip(tip("Change the item.")).build());
        }
        if (ingredientLike) {
            this.addRenderableWidget(Button.builder(Component.literal("Tag"), b ->
                    this.minecraft.setScreen(new IdPickerScreen(this, "Pick an item tag", itemTagIds(), id -> setTag(o, id))))
                    .bounds(left + 180, y + 2, 28, 16)
                    .tooltip(tip("Use a tag (like planks) so any item in the tag works.")).build());
        }
        final String countKey = o.has("count") ? "count" : (o.has("amount") ? "amount" : null);
        if (countKey != null) {
            final int step = countKey.equals("amount") ? 50 : 1;
            this.addRenderableWidget(Button.builder(Component.literal("-"),
                    b -> changeNumber(o, countKey, -step * (Screen.hasShiftDown() ? 10 : 1)))
                    .bounds(left + 246, y + 2, 14, 16)
                    .tooltip(tip(countKey.equals("amount") ? "Less fluid (50 mB). Hold Shift for 500." : "Fewer. Hold Shift for 10.")).build());
            this.addRenderableWidget(Button.builder(Component.literal("+"),
                    b -> changeNumber(o, countKey, step * (Screen.hasShiftDown() ? 10 : 1)))
                    .bounds(left + 262, y + 2, 14, 16)
                    .tooltip(tip(countKey.equals("amount") ? "More fluid (50 mB). Hold Shift for 500." : "More. Hold Shift for 10.")).build());
        }
        if (o.has("chance")) {
            this.addRenderableWidget(Button.builder(Component.literal("-"), b -> changeChance(o, -0.05))
                    .bounds(left + 316, y + 2, 14, 16).tooltip(tip("Lower the chance of this output.")).build());
            this.addRenderableWidget(Button.builder(Component.literal("+"), b -> changeChance(o, 0.05))
                    .bounds(left + 332, y + 2, 14, 16).tooltip(tip("Raise the chance of this output.")).build());
        }
        if (s.remove() != null) {
            this.addRenderableWidget(Button.builder(Component.literal("X"), b -> s.remove().run())
                    .bounds(left + 352, y + 2, 18, 16).tooltip(tip("Remove this one from the list.")).build());
        }
    }

    private void addNumButtons(NumRow n, int y) {
        double[] steps = n.isInt() ? new double[] {-10, -1, 1, 10} : new double[] {-0.1, -0.01, 0.01, 0.1};
        String[] names = n.isInt() ? new String[] {"-10", "-1", "+1", "+10"} : new String[] {"-.1", "-.01", "+.01", "+.1"};
        for (int s = 0; s < 4; s++) {
            final double step = steps[s];
            this.addRenderableWidget(Button.builder(Component.literal(names[s]), b -> {
                double now = n.owner().get(n.key()).getAsDouble();
                double next = Math.max(0, now + step);
                if (n.isInt()) {
                    n.owner().addProperty(n.key(), (int) Math.round(next));
                } else {
                    n.owner().addProperty(n.key(), Math.round(next * 100.0) / 100.0);
                }
            }).bounds(left + 250 + s * 32, y + 2, 30, 16).build());
        }
    }

    private static void changeNumber(JsonObject o, String key, int delta) {
        int now = o.get(key).getAsInt();
        o.addProperty(key, Math.max(key.equals("count") ? 1 : 0, Math.min(1_000_000, now + delta)));
    }

    private static void changeChance(JsonObject o, double delta) {
        double now = o.get("chance").getAsDouble();
        o.addProperty("chance", Math.round(Math.max(0.0, Math.min(1.0, now + delta)) * 100.0) / 100.0);
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

    // ------------------------------------------------------------ saving

    private String autoId() {
        String name = typeId.replace(':', '_');
        for (Row r : rows) {
            if (r instanceof SlotRow s && isResultLabel(s.label()) && !s.obj().has("fluid")) {
                String item = s.obj().has("id") ? s.obj().get("id").getAsString()
                        : (s.obj().has("item") ? s.obj().get("item").getAsString() : null);
                if (item != null) {
                    name = item.replace(':', '_').replace('/', '_');
                }
                break;
            }
        }
        return "worldforge:custom_" + name + "_" + (System.currentTimeMillis() / 1000L % 1000000L);
    }

    private void save() {
        String text = GSON.toJson(json);
        if (text.length() > 29000) {
            status = "This recipe is too big to send.";
            return;
        }
        PacketDistributor.sendToServer(new RecipeEditPayload("SAVE", recipeId != null ? recipeId : autoId(), text));
        status = "Saving...";
    }

    private void openText() {
        this.minecraft.setScreen(new EditorScreen(this, recipeId != null ? recipeId : autoId(), GSON.toJson(json)));
    }

    @Override
    public void onServerResult(boolean ok, String message) {
        status = message;
        if (ok && this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    // ------------------------------------------------------------ drawing

    @Override
    protected void drawUnderWidgets(GuiGraphics g, int mouseX, int mouseY) {
        for (int i = 0; i < perPage; i++) {
            int index = page * perPage + i;
            if (index >= rows.size()) {
                break;
            }
            if (rows.get(index) instanceof Header) {
                g.fill(left, top + i * ROW, left + boxWidth, top + i * ROW + ROW - 2, 0x60FFFFFF);
            }
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        StationInfo.banner(g, this.font, this.width, StationInfo.forType(typeId),
                recipeId != null ? "Editing: " + recipeId : "New (a copy of an existing recipe)");
        g.drawString(this.font, "Change what you need and press Save. Hover buttons for help.", left, 28, 0x888888);

        if (rows.isEmpty()) {
            g.drawCenteredString(this.font, "Nothing in this recipe can be changed with pictures.", this.width / 2, 80, 0xFF8888);
            g.drawCenteredString(this.font, "Press Text to edit it as text.", this.width / 2, 94, 0xAAAAAA);
        }
        for (int i = 0; i < perPage; i++) {
            int index = page * perPage + i;
            if (index >= rows.size()) {
                break;
            }
            int y = top + i * ROW;
            switch (rows.get(index)) {
                case Header h -> g.drawString(this.font, h.label(), left + 4, y + 7, 0xFFFFFF);
                case SlotRow s -> {
                    JsonObject o = s.obj();
                    ItemStack icon = iconOf(o);
                    if (!icon.isEmpty()) {
                        g.renderItem(icon, left + 4, y + 3);
                    }
                    g.drawString(this.font, this.font.plainSubstrByWidth(textOf(o), 112), left + 24, y + 7, 0xFFFFFF);
                    if (o.has("count")) {
                        g.drawString(this.font, "x" + o.get("count").getAsInt(), left + 212, y + 7, 0xAAAAAA);
                    } else if (o.has("amount")) {
                        g.drawString(this.font, o.get("amount").getAsInt() + " mB", left + 212, y + 7, 0xAAAAAA);
                    }
                    if (o.has("chance")) {
                        g.drawString(this.font, String.format(java.util.Locale.ROOT, "%.2f", o.get("chance").getAsDouble()),
                                left + 282, y + 7, 0xAAAAAA);
                    }
                }
                case NumRow n -> {
                    g.drawString(this.font, this.font.plainSubstrByWidth(n.label(), 190), left + 4, y + 7, 0xFFFFFF);
                    g.drawString(this.font, n.owner().get(n.key()).getAsString(), left + 200, y + 7, 0xFFFF55);
                }
                case ToggleRow t -> g.drawString(this.font, this.font.plainSubstrByWidth(t.label(), 190), left + 4, y + 7, 0xFFFFFF);
                case CycleRow c -> g.drawString(this.font, this.font.plainSubstrByWidth(c.label(), 190), left + 4, y + 7, 0xFFFFFF);
                case TextRow t -> {
                    g.drawString(this.font, this.font.plainSubstrByWidth(t.label(), 190), left + 4, y + 7, 0xAAAAAA);
                    g.drawString(this.font, this.font.plainSubstrByWidth(t.value(), boxWidth - 210), left + 200, y + 7, 0x888888);
                }
            }
        }
        drawPager(g, this.width - 144, this.height - 28, page, (rows.size() - 1) / perPage + 1);
        drawStatus(g, left, this.height - 52, boxWidth);
    }
}
