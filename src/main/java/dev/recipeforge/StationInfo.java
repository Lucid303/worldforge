package dev.recipeforge;

import java.util.Locale;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Name, icon and colour for every kind of station, plus the big coloured banner at the top of editors. */
public final class StationInfo {
    private StationInfo() {}

    public record Info(String name, Item icon, int color) {}

    public static Info forType(String type) {
        if (type == null) {
            return new Info("Other", Items.BOOK, 0xFF2E5F8A);
        }
        if (type.startsWith("recipeforge:anvil")) {
            return new Info("Anvil", Items.ANVIL, 0xFF4A4A4A);
        }
        if (type.startsWith("recipeforge:grindstone")) {
            return new Info("Grindstone", Items.GRINDSTONE, 0xFF6E6E6E);
        }
        return switch (type) {
            case "minecraft:crafting", "minecraft:crafting_shaped", "minecraft:crafting_shapeless", "worldforge:keep_shaped", "worldforge:keep_shapeless" ->
                    new Info("Crafting Table", Items.CRAFTING_TABLE, 0xFF7A4A21);
            case "minecraft:smelting" -> new Info("Furnace", Items.FURNACE, 0xFF8A4B1C);
            case "minecraft:blasting" -> new Info("Blast Furnace", Items.BLAST_FURNACE, 0xFF3F4F5F);
            case "minecraft:smoking" -> new Info("Smoker", Items.SMOKER, 0xFF6B4A2B);
            case "minecraft:campfire_cooking" -> new Info("Campfire", Items.CAMPFIRE, 0xFFB5651D);
            case "minecraft:stonecutting" -> new Info("Stonecutter", Items.STONECUTTER, 0xFF5B5B6B);
            case "minecraft:smithing", "minecraft:smithing_transform", "minecraft:smithing_trim" ->
                    new Info("Smithing Table", Items.SMITHING_TABLE, 0xFF3A3A66);
            default -> new Info(pretty(type), modIcon(type), 0xFF2E5F8A);
        };
    }

    public static Info hub() {
        return new Info("World Forge", Items.NETHER_STAR, 0xFF3A2A66);
    }

    public static Info recipes() {
        return new Info("Recipe Forge", Items.CRAFTING_TABLE, 0xFF7A4A21);
    }

    public static Info loot() {
        return new Info("Loot Forge", Items.CHEST, 0xFF8A6A1C);
    }

    public static Info structure() {
        return new Info("Structure Forge", Items.STRUCTURE_BLOCK, 0xFF3F5F7F);
    }

    public static Info biome() {
        return new Info("Biome Forge", Items.GRASS_BLOCK, 0xFF3F7F3F);
    }

    private static Item lookup(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl == null ? null : BuiltInRegistries.ITEM.getOptional(rl).filter(i -> i != Items.AIR).orElse(null);
    }

    /** An icon for another mod's machine: a known machine item, an item named like the recipe, or the mod's main item. */
    private static Item modIcon(String type) {
        String[][] known = {
                {"create:mixing", "create:mechanical_mixer"}, {"create:compacting", "create:mechanical_press"},
                {"create:pressing", "create:mechanical_press"}, {"create:milling", "create:millstone"},
                {"create:crushing", "create:crushing_wheel"}, {"create:cutting", "create:mechanical_saw"},
                {"create:washing", "create:encased_fan"}, {"create:haunting", "create:encased_fan"},
                {"create:splashing", "create:encased_fan"}, {"create:deploying", "create:deployer"},
                {"create:filling", "create:spout"}, {"create:emptying", "create:item_drain"},
                {"create:mechanical_crafting", "create:mechanical_crafter"},
                {"create:sandpaper_polishing", "create:sand_paper"}, {"create:sequenced_assembly", "create:precision_mechanism"},
                {"ars_nouveau:enchanting_apparatus", "ars_nouveau:enchanting_apparatus"},
                {"ars_nouveau:imbuement", "ars_nouveau:imbuement_chamber"}, {"ars_nouveau:glyph", "ars_nouveau:scribes_table"},
                {"ars_nouveau:crush", "ars_nouveau:novice_spell_book"}, {"ae2:inscriber", "ae2:inscriber"},
                {"ae2:charger", "ae2:charger"}, {"ae2:transform", "ae2:certus_quartz_crystal"}};
        for (String[] k : known) {
            if (k[0].equals(type)) {
                Item item = lookup(k[1]);
                if (item != null) {
                    return item;
                }
            }
        }
        Item same = lookup(type);
        if (same != null) {
            return same;
        }
        int colon = type.indexOf(':');
        String ns = colon > 0 ? type.substring(0, colon) : "";
        String[] fallback = switch (ns) {
            case "create" -> new String[] {"create:cogwheel", "create:andesite_alloy"};
            case "ars_nouveau" -> new String[] {"ars_nouveau:source_gem", "ars_nouveau:novice_spell_book"};
            case "ae2" -> new String[] {"ae2:controller", "ae2:certus_quartz_crystal"};
            default -> new String[0];
        };
        for (String f : fallback) {
            Item item = lookup(f);
            if (item != null) {
                return item;
            }
        }
        return Items.BOOK;
    }

    private static String pretty(String type) {
        int colon = type.indexOf(':');
        if (colon <= 0) {
            return type;
        }
        String ns = type.substring(0, colon);
        String path = type.substring(colon + 1).replace('_', ' ');
        String name = switch (ns) {
            case "create" -> "Create";
            case "ars_nouveau" -> "Ars Nouveau";
            case "ae2" -> "Applied Energistics 2";
            default -> Character.toUpperCase(ns.charAt(0)) + ns.substring(1);
        };
        return name + ": " + path;
    }

    /** Draws the coloured station banner across the top of the screen. */
    public static void banner(GuiGraphics g, Font font, int width, Info info, String subtitle) {
        g.fill(0, 0, width, 20, info.color());
        g.fill(0, 20, width, 21, 0x80000000);
        g.renderItem(new ItemStack(info.icon()), 6, 2);
        g.pose().pushPose();
        g.pose().scale(1.5f, 1.5f, 1.0f);
        g.drawString(font, info.name().toUpperCase(Locale.ROOT), (int) (28 / 1.5f), (int) (6 / 1.5f), 0xFFFFFFFF);
        g.pose().popPose();
        if (subtitle != null && !subtitle.isEmpty()) {
            String text = font.plainSubstrByWidth(subtitle, width / 2);
            g.drawString(font, text, width - font.width(text) - 8, 6, 0xFFFFFFFF);
        }
    }
}
