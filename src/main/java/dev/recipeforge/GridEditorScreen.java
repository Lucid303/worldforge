package dev.recipeforge;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The picture editor for crafting table, furnace, blast furnace, smoker, campfire, stonecutter,
 * smithing table, anvil and grindstone. Click a square, then click an item on the right.
 * Type @tag in the search box to pick an item TAG (like minecraft:planks) instead of one item.
 */
public class GridEditorScreen extends ForgeScreen {
    private enum Layout { CRAFTING, COOKING, STONECUTTING, SMITHING, ANVIL, GRINDSTONE, MECHANICAL }

    private record Pick(ItemStack stack, String id, boolean tag) {}

    private static final int STEP = 22;   // distance between squares
    private static final int BOX = 20;    // size of a square
    private static final int CELL = 18;   // size of a picker cell
    private static final int RESULT = 100; // "selected" value meaning the result square
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String[] SMITHING_LABELS = {"Template", "Base item", "Addition"};
    private static List<ItemStack> allItems = null;
    private static Set<String> allNamespaces = null;

    private final JsonObject original;     // null when making something new
    private final String recipeId;         // null when making something new
    private final String typeId;
    private final Layout layout;
    private final JsonElement[] slotJson = new JsonElement[36];
    private final Ingredient[] slotIng = new Ingredient[36];
    private final boolean[] keep = new boolean[36];
    private String resultItem = null;
    private ItemStack resultStack = ItemStack.EMPTY;
    private int resultCount = 1;
    private boolean shaped = true;
    private int gridSize = 3;            // Create mechanical crafter: 3x3 up to 6x6
    private boolean mirror = true;       // Create mechanical crafter: also accept the mirrored shape
    private String mirrorKey = null;     // the name the recipe uses for that setting
    private boolean blockMode = false;
    private int cookTime;
    private float experience = 0.1f;
    private int levelCost = 5;
    private int xpReward = 0;
    private int selected = 0;

    private EditBox searchBox;
    private Button shapeButton;
    private List<Pick> tagPicks = null;
    private final List<Pick> filtered = new ArrayList<>();
    private int page = 0;
    private String lastSearch = "";
    private int gridX;
    private int gridY;
    private int resX;
    private int resY;
    private int pickX;
    private int pickY;
    private int pickCols;
    private int pickRows;

    /** True for recipe types this screen can edit. */
    public static boolean supports(String type) {
        return layoutFor(type) != null;
    }

    private static Layout layoutFor(String type) {
        if (type == null) {
            return null;
        }
        if (type.startsWith("recipeforge:anvil")) {
            return Layout.ANVIL;
        }
        if (type.startsWith("recipeforge:grindstone")) {
            return Layout.GRINDSTONE;
        }
        return switch (type) {
            case "minecraft:crafting_shaped", "minecraft:crafting_shapeless",
                 "worldforge:keep_shaped", "worldforge:keep_shapeless" -> Layout.CRAFTING;
            case "minecraft:smelting", "minecraft:blasting", "minecraft:smoking", "minecraft:campfire_cooking" ->
                    Layout.COOKING;
            case "minecraft:stonecutting" -> Layout.STONECUTTING;
            case "create:mechanical_crafting" -> Layout.MECHANICAL;
            case "minecraft:smithing_transform" -> Layout.SMITHING;
            default -> null;
        };
    }

    private static int defaultCookTime(String type) {
        return switch (type) {
            case "minecraft:blasting", "minecraft:smoking" -> 100;
            case "minecraft:campfire_cooking" -> 600;
            default -> 200;
        };
    }

    public GridEditorScreen(Screen parent, JsonObject original, String recipeId, String presetResult, String newType) {
        super(Component.literal("World Forge"), parent);
        this.original = original;
        this.recipeId = recipeId;
        String type = newType;
        if (original != null && original.has("type")) {
            type = original.get("type").getAsString();
        }
        if (layoutFor(type) == null) {
            type = "minecraft:crafting_shaped";
        }
        this.typeId = type;
        this.layout = layoutFor(type);
        this.shaped = !type.endsWith("shapeless");
        this.blockMode = type.endsWith("_block");
        this.cookTime = defaultCookTime(type);
        if (original != null) {
            loadFromJson(original);
            if (recipeId == null) {
                // a copy of an existing recipe used only to learn the format: start with an empty grid
                clearForTemplate();
                if (presetResult != null) {
                    setResult(presetResult);
                }
            }
        } else if (presetResult != null) {
            setResult(presetResult);
        }
    }

    private void clearForTemplate() {
        for (int i = 0; i < slotJson.length; i++) {
            slotJson[i] = null;
            slotIng[i] = null;
            keep[i] = false;
        }
        resultItem = null;
        resultStack = ItemStack.EMPTY;
        resultCount = 1;
    }

    /** Changes the size of the Create mechanical crafter grid and keeps every item where it was. */
    private void resizeGrid(int newSize) {
        JsonElement[] oldJson = slotJson.clone();
        Ingredient[] oldIng = slotIng.clone();
        int old = gridSize;
        for (int i = 0; i < slotJson.length; i++) {
            slotJson[i] = null;
            slotIng[i] = null;
        }
        for (int i = 0; i < old * old; i++) {
            int r = i / old;
            int c = i % old;
            if (r < newSize && c < newSize) {
                slotJson[r * newSize + c] = oldJson[i];
                slotIng[r * newSize + c] = oldIng[i];
            }
        }
        gridSize = newSize;
        selected = 0;
        this.rebuildWidgets();
    }

    // ------------------------------------------------------------ layout helpers

    private boolean isPair() {
        return layout == Layout.ANVIL || layout == Layout.GRINDSTONE;
    }

    private int slotCount() {
        return switch (layout) {
            case CRAFTING -> 9;
            case MECHANICAL -> gridSize * gridSize;
            case COOKING, STONECUTTING -> 1;
            case SMITHING -> 3;
            case ANVIL, GRINDSTONE -> 2;
        };
    }

    private int sx(int i) {
        return switch (layout) {
            case CRAFTING -> gridX + (i % 3) * STEP;
            case MECHANICAL -> gridX + (i % gridSize) * STEP;
            case COOKING, STONECUTTING -> gridX + STEP;
            case SMITHING, ANVIL, GRINDSTONE -> gridX;
        };
    }

    private int sy(int i) {
        return switch (layout) {
            case CRAFTING -> gridY + (i / 3) * STEP;
            case MECHANICAL -> gridY + (i / gridSize) * STEP;
            case COOKING, STONECUTTING -> gridY + STEP;
            case SMITHING, ANVIL, GRINDSTONE -> gridY + i * STEP;
        };
    }

    private String slotLabel(int i) {
        return switch (layout) {
            case SMITHING -> SMITHING_LABELS[i];
            case ANVIL -> i == 0 ? "First item" : "Second item";
            case GRINDSTONE -> i == 0 ? "Top item" : "Bottom item";
            default -> null;
        };
    }

    private boolean resultVisible() {
        return !(isPair() && blockMode);
    }

    private boolean showCount() {
        return layout == Layout.CRAFTING || layout == Layout.MECHANICAL || layout == Layout.STONECUTTING || (isPair() && !blockMode);
    }

    private boolean anyKeep() {
        for (boolean b : keep) {
            if (b) {
                return true;
            }
        }
        return false;
    }

    private String currentType() {
        if (layout == Layout.MECHANICAL) {
            return "create:mechanical_crafting";
        }
        if (layout == Layout.CRAFTING) {
            if (anyKeep()) {
                return shaped ? "worldforge:keep_shaped" : "worldforge:keep_shapeless";
            }
            return shaped ? "minecraft:crafting_shaped" : "minecraft:crafting_shapeless";
        }
        return typeId;
    }

    // ------------------------------------------------------------ loading

    private static JsonElement copyOf(JsonObject json, String key) {
        return json.has(key) ? json.get(key).deepCopy() : null;
    }

    private static String str(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : null;
    }

    private static JsonElement itemIng(String id) {
        if (id == null) {
            return null;
        }
        JsonObject o = new JsonObject();
        o.addProperty("item", id);
        return o;
    }

    private void loadFromJson(JsonObject json) {
        try {
            switch (layout) {
                case CRAFTING, MECHANICAL -> {
                    if (layout == Layout.MECHANICAL) {
                        mirrorKey = json.has("acceptMirrored") ? "acceptMirrored"
                                : (json.has("accept_mirrored") ? "accept_mirrored" : null);
                        mirror = mirrorKey == null || json.get(mirrorKey).getAsBoolean();
                    }
                    if (shaped) {
                        JsonArray pattern = json.getAsJsonArray("pattern");
                        JsonObject key = json.getAsJsonObject("key");
                        int cols = 3;
                        if (layout == Layout.MECHANICAL) {
                            int biggest = pattern.size();
                            for (JsonElement pr : pattern) {
                                biggest = Math.max(biggest, pr.getAsString().length());
                            }
                            gridSize = Math.max(3, Math.min(6, biggest));
                            cols = gridSize;
                            if (biggest > 6) {
                                status = "This recipe is bigger than 6x6: only the top left part is shown. Do not save it here.";
                            }
                        }
                        for (int r = 0; r < pattern.size() && r < cols; r++) {
                            String row = pattern.get(r).getAsString();
                            for (int c = 0; c < row.length() && c < cols; c++) {
                                char ch = row.charAt(c);
                                if (ch == ' ') {
                                    continue;
                                }
                                JsonElement ing = key.get(String.valueOf(ch));
                                if (ing != null) {
                                    setSlot(r * cols + c, ing.deepCopy());
                                }
                            }
                        }
                    } else {
                        JsonArray list = json.getAsJsonArray("ingredients");
                        for (int i = 0; i < list.size() && i < 9; i++) {
                            setSlot(i, list.get(i).deepCopy());
                        }
                    }
                    if (json.has("keep") && json.get("keep").isJsonArray()) {
                        for (JsonElement k : json.getAsJsonArray("keep")) {
                            for (int i = 0; i < 9; i++) {
                                if (slotJson[i] != null && slotJson[i].toString().equals(k.toString())) {
                                    keep[i] = true;
                                }
                            }
                        }
                    }
                }
                case COOKING -> {
                    setSlot(0, copyOf(json, "ingredient"));
                    if (json.has("cookingtime")) {
                        cookTime = Math.max(1, json.get("cookingtime").getAsInt());
                    }
                    if (json.has("experience")) {
                        experience = json.get("experience").getAsFloat();
                    }
                }
                case STONECUTTING -> setSlot(0, copyOf(json, "ingredient"));
                case SMITHING -> {
                    setSlot(0, copyOf(json, "template"));
                    setSlot(1, copyOf(json, "base"));
                    setSlot(2, copyOf(json, "addition"));
                }
                case ANVIL, GRINDSTONE -> {
                    setSlot(0, itemIng(str(json, "left")));
                    setSlot(1, itemIng(str(json, "right")));
                    String res = str(json, "result");
                    if (res != null && !res.isEmpty()) {
                        setResult(res);
                    }
                    if (json.has("count")) {
                        resultCount = Math.max(1, Math.min(64, json.get("count").getAsInt()));
                    }
                    if (json.has("cost")) {
                        levelCost = Math.max(1, json.get("cost").getAsInt());
                    }
                    if (json.has("xp")) {
                        xpReward = Math.max(0, json.get("xp").getAsInt());
                    }
                    blockMode = "BLOCK".equals(str(json, "mode"));
                }
            }
            if (!isPair()) {
                JsonElement res = json.get("result");
                if (res != null && res.isJsonObject()) {
                    JsonObject o = res.getAsJsonObject();
                    setResult(o.get("id").getAsString());
                    if (o.has("count")) {
                        resultCount = Math.max(1, Math.min(64, o.get("count").getAsInt()));
                    }
                } else if (res != null && res.isJsonPrimitive()) {
                    setResult(res.getAsString());
                }
            }
        } catch (Exception e) {
            status = "Could not read this completely: " + e.getMessage();
        }
    }

    private Ingredient parseIngredient(JsonElement json) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return null;
        }
        RegistryOps<JsonElement> ops = mc.level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        return Ingredient.CODEC.parse(ops, json).result().orElse(null);
    }

    private void setSlot(int index, JsonElement json) {
        slotJson[index] = json;
        slotIng[index] = json == null ? null : parseIngredient(json);
        if (json == null) {
            keep[index] = false;
        }
    }

    private void setResult(String id) {
        resultItem = id;
        resultStack = ItemStack.EMPTY;
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl != null) {
            Optional<Item> item = BuiltInRegistries.ITEM.getOptional(rl);
            if (item.isPresent()) {
                resultStack = new ItemStack(item.get());
            }
        }
    }

    public static String keyOf(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    public static List<ItemStack> allItems() {
        if (allItems == null) {
            List<ItemStack> list = new ArrayList<>();
            BuiltInRegistries.ITEM.stream().forEach(item -> {
                if (item != Items.AIR) {
                    list.add(new ItemStack(item));
                }
            });
            list.sort((a, b) -> keyOf(a).compareTo(keyOf(b)));
            allItems = list;
        }
        return allItems;
    }

    public static Set<String> namespaces() {
        if (allNamespaces == null) {
            Set<String> set = new HashSet<>();
            for (ItemStack s : allItems()) {
                set.add(BuiltInRegistries.ITEM.getKey(s.getItem()).getNamespace());
            }
            allNamespaces = set;
        }
        return allNamespaces;
    }

    private static ItemStack iconForTag(TagKey<Item> tag) {
        Optional<HolderSet.Named<Item>> named = BuiltInRegistries.ITEM.getTag(tag);
        if (named.isPresent()) {
            for (Holder<Item> holder : named.get()) {
                return new ItemStack(holder.value());
            }
        }
        return new ItemStack(Items.BARRIER);
    }

    private List<Pick> tagPicks() {
        if (tagPicks == null) {
            List<Pick> list = new ArrayList<>();
            BuiltInRegistries.ITEM.getTagNames().forEach(tag ->
                    list.add(new Pick(iconForTag(tag), tag.location().toString(), true)));
            list.sort((a, b) -> a.id().compareTo(b.id()));
            tagPicks = list;
        }
        return tagPicks;
    }

    // ------------------------------------------------------------ layout

    @Override
    protected void init() {
        String prev = searchBox != null ? searchBox.getValue() : lastSearch;

        int gridW = (layout == Layout.MECHANICAL ? gridSize : 3) * STEP;
        int leftW = layout == Layout.MECHANICAL ? Math.max(138, gridW + 84) : 138;
        pickCols = Math.max(5, Math.min(12, (this.width - 20 - leftW - 12) / CELL));
        int total = leftW + pickCols * CELL;
        gridX = Math.max(8, (this.width - total) / 2);
        gridY = 66;
        resX = gridX + gridW + 28;
        resY = layout == Layout.MECHANICAL ? gridY + (gridSize * STEP - BOX) / 2 : gridY + STEP;
        pickX = gridX + leftW;
        pickY = 50;
        pickRows = Math.max(2, (this.height - pickY - 66) / CELL);

        searchBox = new EditBox(this.font, pickX, 24, pickCols * CELL, 20, Component.literal("Search items"));
        searchBox.setMaxLength(100);
        searchBox.setHint(Component.literal("Search (@minecraft, @tag)"));
        searchBox.setValue(prev);
        searchBox.setResponder(text -> {
            page = 0;
            applyFilter(text);
        });
        this.addRenderableWidget(searchBox);
        this.setInitialFocus(searchBox);

        int belowY = gridY + 3 * STEP + 6;
        shapeButton = null;
        if (layout == Layout.CRAFTING) {
            shapeButton = Button.builder(Component.literal(shaped ? "Shaped" : "Shapeless"), b -> toggleShape())
                    .bounds(gridX, belowY, 90, 20)
                    .tooltip(Tooltip.create(Component.literal(
                            "Shaped: the items must be in this exact layout. Shapeless: any layout works."))).build();
            this.addRenderableWidget(shapeButton);
            this.addRenderableWidget(Button.builder(Component.literal("Keep"), b -> toggleKeep())
                    .bounds(gridX + 94, belowY, 40, 20)
                    .tooltip(Tooltip.create(Component.literal(
                            "Select a square, then press Keep: that item is NOT used up when crafting "
                                    + "(for example an axe). A green K marks it."))).build());
        }
        if (layout == Layout.MECHANICAL) {
            this.addRenderableWidget(Button.builder(Component.literal("Size " + gridSize + "x" + gridSize),
                    b -> resizeGrid(gridSize >= 6 ? 3 : gridSize + 1))
                    .bounds(resX - 10, resY + 44, 60, 18)
                    .tooltip(Tooltip.create(Component.literal("Change how big the crafting grid is (3x3 up to 6x6)."))).build());
            this.addRenderableWidget(Button.builder(Component.literal(mirror ? "Mirror on" : "Mirror off"), b -> {
                mirror = !mirror;
                b.setMessage(Component.literal(mirror ? "Mirror on" : "Mirror off"));
            }).bounds(resX - 10, resY + 64, 60, 18)
                    .tooltip(Tooltip.create(Component.literal("On: the recipe also works with the shape flipped left to right."))).build());
        }
        if (layout == Layout.COOKING) {
            this.addRenderableWidget(Button.builder(Component.literal("-"), b -> changeCook(-20))
                    .bounds(gridX + 96, belowY, 14, 14).build());
            this.addRenderableWidget(Button.builder(Component.literal("+"), b -> changeCook(20))
                    .bounds(gridX + 112, belowY, 14, 14).build());
            this.addRenderableWidget(Button.builder(Component.literal("-"), b -> changeXp(-0.1f))
                    .bounds(gridX + 96, belowY + 18, 14, 14).build());
            this.addRenderableWidget(Button.builder(Component.literal("+"), b -> changeXp(0.1f))
                    .bounds(gridX + 112, belowY + 18, 14, 14).build());
        }
        if (isPair()) {
            this.addRenderableWidget(Button.builder(
                    Component.literal(blockMode ? "Mode: Block it" : "Mode: Make an item"), b -> {
                        blockMode = !blockMode;
                        this.rebuildWidgets();
                    }).bounds(gridX, belowY, 126, 20)
                    .tooltip(Tooltip.create(Component.literal("Switch between making a new item and blocking the combination."))).build());
            if (!blockMode) {
                int step = layout == Layout.ANVIL ? 1 : 5;
                this.addRenderableWidget(Button.builder(Component.literal("-"), b -> changePairNumber(-step))
                        .bounds(gridX + 96, belowY + 26, 14, 14).build());
                this.addRenderableWidget(Button.builder(Component.literal("+"), b -> changePairNumber(step))
                        .bounds(gridX + 112, belowY + 26, 14, 14).build());
            }
        }
        if (showCount()) {
            this.addRenderableWidget(Button.builder(Component.literal("-"), b -> changeCount(-1))
                    .bounds(resX - 10, resY + BOX + 4, 14, 14).build());
            this.addRenderableWidget(Button.builder(Component.literal("+"), b -> changeCount(1))
                    .bounds(resX + BOX - 4, resY + BOX + 4, 14, 14).build());
        }

        this.addRenderableWidget(Button.builder(Component.literal("Save"), b -> save())
                .bounds(gridX, this.height - 28, 64, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.onClose())
                .bounds(this.width - 78, this.height - 28, 70, 20).build());
        addPager(Math.max(pickX + 62, pickX + pickCols * CELL / 2), this.height - 28,
                () -> changePage(-1), () -> changePage(1));

        applyFilter(prev);
    }

    private void applyFilter(String text) {
        lastSearch = text == null ? "" : text;
        String lower = lastSearch.trim().toLowerCase(Locale.ROOT);
        filtered.clear();
        if (lower.startsWith("@tag")) {
            String f = lower.substring(4).trim();
            for (Pick p : tagPicks()) {
                if (f.isEmpty() || p.id().contains(f)) {
                    filtered.add(p);
                }
            }
        } else {
            SearchFilter f = SearchFilter.parse(lastSearch, namespaces());
            for (ItemStack s : allItems()) {
                ResourceLocation key = BuiltInRegistries.ITEM.getKey(s.getItem());
                if (f.matches(key, s.getHoverName().getString())) {
                    filtered.add(new Pick(s, key.toString(), false));
                }
            }
        }
        page = Math.min(page, maxPage());
    }

    private int perPage() {
        return Math.max(1, pickCols * pickRows);
    }

    private int maxPage() {
        return Math.max(0, (filtered.size() - 1) / perPage());
    }

    private void changePage(int delta) {
        page = Math.max(0, Math.min(maxPage(), page + delta));
    }

    @Override
    protected void scrollPage(int delta) {
        changePage(delta);
    }

    private void toggleShape() {
        shaped = !shaped;
        shapeButton.setMessage(Component.literal(shaped ? "Shaped" : "Shapeless"));
    }

    private void toggleKeep() {
        if (selected < 0 || selected >= 9 || slotJson[selected] == null) {
            status = "Click a square that has an item in it first, then press Keep.";
            return;
        }
        keep[selected] = !keep[selected];
        status = keep[selected] ? "That item will NOT be used up." : "That item will be used up again.";
    }

    private void changeCount(int delta) {
        resultCount = Math.max(1, Math.min(64, resultCount + delta));
    }

    private void changeCook(int delta) {
        cookTime = Math.max(20, Math.min(72000, cookTime + delta));
    }

    private void changeXp(float delta) {
        experience = Math.max(0f, Math.min(100f, Math.round((experience + delta) * 100f) / 100f));
    }

    private void changePairNumber(int delta) {
        if (layout == Layout.ANVIL) {
            levelCost = Math.max(1, Math.min(100, levelCost + delta));
        } else {
            xpReward = Math.max(0, Math.min(1000, xpReward + delta));
        }
    }

    // ------------------------------------------------------------ saving

    private String autoId() {
        String path = resultItem == null ? "recipe" : resultItem.replace(':', '_').replace('/', '_');
        return "worldforge:custom_" + path + "_" + (System.currentTimeMillis() / 1000L % 1000000L);
    }

    private void buildCrafting(JsonObject out) {
        int cols = layout == Layout.MECHANICAL ? gridSize : 3;
        int cells = layout == Layout.MECHANICAL ? gridSize * gridSize : 9;
        if (!out.has("category") && layout == Layout.CRAFTING) {
            out.addProperty("category", "misc");
        }
        out.remove("pattern");
        out.remove("key");
        out.remove("ingredients");
        out.remove("keep");
        if (shaped) {
            int minR = cols;
            int maxR = -1;
            int minC = cols;
            int maxC = -1;
            for (int i = 0; i < cells; i++) {
                if (slotJson[i] != null) {
                    minR = Math.min(minR, i / cols);
                    maxR = Math.max(maxR, i / cols);
                    minC = Math.min(minC, i % cols);
                    maxC = Math.max(maxC, i % cols);
                }
            }
            Map<String, Character> letters = new LinkedHashMap<>();
            JsonObject key = new JsonObject();
            JsonArray pattern = new JsonArray();
            char next = 'A';
            for (int r = minR; r <= maxR; r++) {
                StringBuilder row = new StringBuilder();
                for (int c = minC; c <= maxC; c++) {
                    JsonElement ing = slotJson[r * cols + c];
                    if (ing == null) {
                        row.append(' ');
                    } else {
                        String asText = ing.toString();
                        Character letter = letters.get(asText);
                        if (letter == null) {
                            letter = next++;
                            letters.put(asText, letter);
                            key.add(String.valueOf(letter), ing.deepCopy());
                        }
                        row.append(letter.charValue());
                    }
                }
                pattern.add(row.toString());
            }
            out.add("pattern", pattern);
            out.add("key", key);
        } else {
            JsonArray list = new JsonArray();
            for (int i = 0; i < cells; i++) {
                if (slotJson[i] != null) {
                    list.add(slotJson[i].deepCopy());
                }
            }
            out.add("ingredients", list);
        }
        if (layout == Layout.MECHANICAL) {
            out.addProperty(mirrorKey != null ? mirrorKey : "acceptMirrored", mirror);
        }
        if (anyKeep() && layout == Layout.CRAFTING) {
            JsonArray keepList = new JsonArray();
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < 9; i++) {
                if (keep[i] && slotJson[i] != null && seen.add(slotJson[i].toString())) {
                    keepList.add(slotJson[i].deepCopy());
                }
            }
            out.add("keep", keepList);
        }
    }

    private JsonObject buildResult() {
        JsonObject res = new JsonObject();
        if (original != null && original.has("result") && original.get("result").isJsonObject()) {
            res = original.getAsJsonObject("result").deepCopy();
            String oldId = res.has("id") ? res.get("id").getAsString() : null;
            if (oldId == null || !oldId.equals(resultItem)) {
                res.remove("components");
            }
        }
        res.addProperty("id", resultItem);
        if (showCount()) {
            res.addProperty("count", resultCount);
        }
        return res;
    }

    private JsonObject buildJson() {
        JsonObject out = original != null ? original.deepCopy() : new JsonObject();
        out.addProperty("type", currentType());
        switch (layout) {
            case CRAFTING, MECHANICAL -> buildCrafting(out);
            case COOKING -> {
                out.add("ingredient", slotJson[0].deepCopy());
                out.addProperty("cookingtime", cookTime);
                out.addProperty("experience", experience);
                if (!out.has("category")) {
                    out.addProperty("category", "misc");
                }
            }
            case STONECUTTING -> out.add("ingredient", slotJson[0].deepCopy());
            case SMITHING -> {
                out.add("template", slotJson[0].deepCopy());
                out.add("base", slotJson[1].deepCopy());
                out.add("addition", slotJson[2].deepCopy());
            }
            default -> { }
        }
        out.add("result", buildResult());
        return out;
    }

    private static String itemOf(JsonElement slot) {
        try {
            return slot.getAsJsonObject().get("item").getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    private void saveStation() {
        if (slotJson[0] == null || slotJson[1] == null) {
            status = "Fill both squares on the left first.";
            return;
        }
        String left = itemOf(slotJson[0]);
        String right = itemOf(slotJson[1]);
        if (left == null || right == null) {
            status = "Anvil and grindstone only accept plain items (not tags).";
            return;
        }
        if (!blockMode && (resultItem == null || resultStack.isEmpty())) {
            status = "Click the square next to the arrow, then click the item this makes.";
            return;
        }
        JsonObject entry = new JsonObject();
        if (recipeId != null) {
            entry.addProperty("id", recipeId);
        }
        entry.addProperty("mode", blockMode ? "BLOCK" : "ADD");
        entry.addProperty("left", left);
        entry.addProperty("right", right);
        if (!blockMode) {
            entry.addProperty("result", resultItem);
            entry.addProperty("count", resultCount);
            entry.addProperty("cost", levelCost);
            entry.addProperty("xp", xpReward);
        }
        String station = layout == Layout.ANVIL ? "anvil" : "grindstone";
        PacketDistributor.sendToServer(new RecipeEditPayload("STATION_SAVE", station, GSON.toJson(entry)));
        status = "Saving...";
    }

    private void save() {
        if (isPair()) {
            saveStation();
            return;
        }
        if (resultItem == null || resultStack.isEmpty()) {
            status = "Click the square next to the arrow, then click the item this recipe makes.";
            return;
        }
        int filled = 0;
        for (int i = 0; i < slotCount(); i++) {
            if (slotJson[i] != null) {
                filled++;
            }
        }
        if ((layout == Layout.CRAFTING || layout == Layout.MECHANICAL) && filled == 0) {
            status = "Put at least one item in the grid first.";
            return;
        }
        if (layout != Layout.CRAFTING && layout != Layout.MECHANICAL && filled < slotCount()) {
            status = "Fill every square on the left first.";
            return;
        }
        String id = recipeId != null ? recipeId : autoId();
        String json = GSON.toJson(buildJson());
        if (json.length() > 29000) {
            status = "This recipe is too big to send.";
            return;
        }
        PacketDistributor.sendToServer(new RecipeEditPayload("SAVE", id, json));
        status = "Saving...";
    }

    @Override
    public void onServerResult(boolean ok, String message) {
        status = message;
        if (ok && this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    // ------------------------------------------------------------ input

    private void pick(Pick p) {
        if (selected == RESULT) {
            if (p.tag()) {
                status = "The result must be a single item, not a tag.";
                return;
            }
            setResult(p.id());
            return;
        }
        if (p.tag() && isPair()) {
            status = "Anvil and grindstone only accept plain items (not tags).";
            return;
        }
        JsonObject ing = new JsonObject();
        ing.addProperty(p.tag() ? "tag" : "item", p.id());
        setSlot(selected, ing);
        if (selected < slotCount() - 1) {
            selected++;
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        for (int i = 0; i < slotCount(); i++) {
            if (Ui.inside(mouseX, mouseY, sx(i), sy(i), BOX, BOX)) {
                selected = i;
                if (button == 1) {
                    setSlot(i, null);
                }
                return true;
            }
        }
        if (resultVisible() && Ui.inside(mouseX, mouseY, resX, resY, BOX, BOX)) {
            selected = RESULT;
            if (button == 1) {
                resultItem = null;
                resultStack = ItemStack.EMPTY;
            }
            return true;
        }
        if (button == 0) {
            int start = page * perPage();
            for (int i = 0; i < perPage() && start + i < filtered.size(); i++) {
                int x = pickX + (i % pickCols) * CELL;
                int y = pickY + (i / pickCols) * CELL;
                if (Ui.inside(mouseX, mouseY, x, y, CELL - 2, CELL - 2)) {
                    pick(filtered.get(start + i));
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------ drawing

    private String cookText() {
        String time = cookTime % 20 == 0 ? (cookTime / 20) + " s"
                : String.format(Locale.ROOT, "%.1f s", cookTime / 20.0);
        return "Cook time: " + time;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        StationInfo.banner(g, this.font, this.width, StationInfo.forType(typeId),
                recipeId != null ? "Editing: " + recipeId : "New");
        g.drawString(this.font, "1) Click a square", gridX, 24, 0xFFFFFF);
        g.drawString(this.font, "2) Click an item ->", gridX, 35, 0xFFFFFF);
        g.drawString(this.font, "Right-click = empty it", gridX, 46, 0xAAAAAA);

        Component hoverText = null;

        for (int i = 0; i < slotCount(); i++) {
            int x = sx(i);
            int y = sy(i);
            Ui.slot(g, x, y, BOX);
            ItemStack shown = slotIng[i] == null ? ItemStack.EMPTY : Ui.cycle(slotIng[i].getItems());
            if (!shown.isEmpty()) {
                g.renderItem(shown, x + 2, y + 2);
            }
            if (keep[i]) {
                g.drawString(this.font, "K", x + BOX - 8, y + BOX - 10, 0xFF55FF55);
            }
            if (selected == i) {
                Ui.outline(g, x - 1, y - 1, BOX + 2, BOX + 2, 0xFFFFFF55);
            }
            String label = slotLabel(i);
            if (label != null) {
                g.drawString(this.font, label, x + BOX + 4, y + 6, 0xAAAAAA);
            }
            if (Ui.inside(mouseX, mouseY, x, y, BOX, BOX) && slotJson[i] != null) {
                hoverText = describe(slotJson[i], shown, keep[i]);
            }
        }

        if (resultVisible()) {
            g.drawString(this.font, "->", resX - 22, resY + 6, 0xFFFFFF);
            g.drawString(this.font, "Makes", resX - 2, resY - 11, 0xFFFFFF);
            Ui.slot(g, resX, resY, BOX);
            if (!resultStack.isEmpty()) {
                ItemStack withCount = resultStack.copyWithCount(showCount() ? resultCount : 1);
                g.renderItem(withCount, resX + 2, resY + 2);
                g.renderItemDecorations(this.font, withCount, resX + 2, resY + 2);
            }
            if (selected == RESULT) {
                Ui.outline(g, resX - 1, resY - 1, BOX + 2, BOX + 2, 0xFFFFFF55);
            }
            if (showCount()) {
                g.drawCenteredString(this.font, String.valueOf(resultCount), resX + BOX / 2, resY + BOX + 7, 0xFFFFFF);
            }
            if (Ui.inside(mouseX, mouseY, resX, resY, BOX, BOX) && !resultStack.isEmpty()) {
                hoverText = resultStack.getHoverName();
            }
        } else {
            g.drawString(this.font, "This combination", gridX + 3 * STEP - 6, resY, 0xFF5555);
            g.drawString(this.font, "will be BLOCKED", gridX + 3 * STEP - 6, resY + 11, 0xFF5555);
        }

        int belowY = gridY + 3 * STEP + 6;
        switch (layout) {
            case CRAFTING -> g.drawString(this.font, shaped ? "Shaped: exact layout" : "Shapeless: any order",
                    gridX, belowY + 24, 0xAAAAAA);
            case COOKING -> {
                g.drawString(this.font, cookText(), gridX, belowY + 3, 0xFFFFFF);
                g.drawString(this.font, "XP: " + String.format(Locale.ROOT, "%.2f", experience),
                        gridX, belowY + 21, 0xFFFFFF);
            }
            case STONECUTTING -> g.drawString(this.font, "1 item in, items out", gridX, belowY + 3, 0xAAAAAA);
            case SMITHING -> g.drawString(this.font, "All 3 squares are needed", gridX, belowY + 3, 0xAAAAAA);
            case ANVIL -> {
                if (!blockMode) {
                    g.drawString(this.font, "XP levels: " + levelCost, gridX, belowY + 29, 0xFFFFFF);
                }
            }
            case GRINDSTONE -> {
                if (!blockMode) {
                    g.drawString(this.font, "XP given: " + xpReward, gridX, belowY + 29, 0xFFFFFF);
                }
            }
        }

        int start = page * perPage();
        for (int i = 0; i < perPage() && start + i < filtered.size(); i++) {
            Pick p = filtered.get(start + i);
            int x = pickX + (i % pickCols) * CELL;
            int y = pickY + (i / pickCols) * CELL;
            Ui.slot(g, x, y, CELL - 2);
            g.renderItem(p.stack(), x + 1, y + 1);
            if (p.tag()) {
                g.drawString(this.font, "#", x + 9, y + 7, 0xFFFFFF55);
            }
            if (Ui.inside(mouseX, mouseY, x, y, CELL - 2, CELL - 2)) {
                hoverText = p.tag() ? Component.literal("Tag: " + p.id()) : p.stack().getHoverName();
            }
        }
        drawPager(g, Math.max(pickX + 62, pickX + pickCols * CELL / 2), this.height - 28, page, maxPage() + 1);

        g.drawString(this.font, recipeId != null ? "Saving replaces the existing one."
                : "Saving adds a new one.", gridX, this.height - 64, 0x888888);
        drawStatus(g, gridX, this.height - 54, this.width - 2 * gridX);
        if (hoverText != null) {
            g.renderTooltip(this.font, hoverText, mouseX, mouseY);
        }
    }

    private Component describe(JsonElement ingredient, ItemStack shown, boolean kept) {
        String suffix = kept ? "  (kept, not used up)" : "";
        if (ingredient.isJsonObject() && ingredient.getAsJsonObject().has("tag")) {
            return Component.literal("Any item with tag " + ingredient.getAsJsonObject().get("tag").getAsString() + suffix);
        }
        if (!shown.isEmpty()) {
            return Component.literal(shown.getHoverName().getString() + suffix);
        }
        return Component.literal("Special ingredient" + suffix);
    }
}
