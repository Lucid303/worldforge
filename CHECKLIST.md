# World Forge 0.8.0 - things to check

## Fixes from last round
1. Biome Forge: Apply really changes the colour in the world and it stays after leaving the screen / restarting.
   (If not, the screen shows the reason in yellow.)
2. Biome Forge: the first letter typed in the search box shows up (also in Loot, Structure and the other lists).
3. Structure Forge biome screen: buttons Enable all / Disable all / Save / Restore do not overlap Back; no "Never spawn";
   check boxes line up on the left.
4. Custom anvil / grindstone recipes show in EMI (and JEI) - needs the build line "everything included".
5. History: right-click a change jumps to the item / loot table / biome / structure / tag...; Esc returns to History.
6. History buttons are called Undo and Redo and are fully on screen.
7. Loot Forge: no yellow "change things" text, more rows per page; no yellow counts in Loot / Structure lists.
8. World Forge hub: no "creative mode only" text; Pack Export button at the top right.
9. History: filter buttons (All, Recipe, Loot, Struct., Biome, Tags, Enchant, WorldGen); pick one or several.
10. No yellow "pick a change" text in History.

## New
11. Pack Export: press it, a zip appears in <game folder>/worldforge-exports (the folder opens in single player).
12. Tag Forge: Items / Blocks / Mobs tabs; open a tag; click an icon to take it out (red); Add; Apply; tag really changes
    (check with JEI/EMI or a recipe that uses the tag); Restore.
13. Enchantment Forge: pick an enchantment; change level / weight / costs / items; Save; re-enter the world; it changed.
14. World Gen Forge: pick an ore feature; change count / rarity / height / vein size; Save; re-enter; new chunks differ.
15. History shows pictures for tags (icon grids) and number cards for enchantments / world gen.

## Known limits
- Tag Forge: removing an item that is only in the tag through ANOTHER tag it includes may not work.
- Enchantment / World Gen: need leaving and re-entering the world; only new chunks change for world generation.
- World Gen Forge only edits what has a number (count, rarity, height, vein size); trees and lakes are listed but their
  shapes cannot be edited here.
- Not done yet: Entity Forge (spawns, health, damage, speed), Trade Forge, Advancement Forge.


## Other mods (Create, Applied Energistics 2, Ars Nouveau) - new in 0.8.0
16. Click an item made by one of these machines: its recipe row shows the machine's icon and name (for example
    "Create: mixing"). Edit opens a picture editor (not just text).
17. Create: change an ingredient item / tag / fluid and amount, an output item, count and chance, processing time
    and the heat requirement (click to cycle none / heated / superheated); Save; JEI/EMI show the change.
18. Applied Energistics 2: inscriber recipe shows Top / Middle / Bottom slots and a Result; mode switches inscribe / press.
19. Ars Nouveau: enchanting apparatus recipe shows reagent, pedestal items and output, plus the source cost number.
20. Add / remove ingredients with the Add button and X on a list; the rest of the recipe stays exactly as it was.
21. New recipe -> "Other mods": pick a machine type; you get a copy of one of its recipes to change, then Save.
22. The Text button still opens the raw JSON for anything the picture editor cannot show.
23. Loot Forge, Tag Forge and History also work with these mods' items and recipes.

## Known limits for other mods
- Recipes whose only output is a fluid do not appear in the item list (you can still reach them via the machine picker).
- Lists that are not made of items or fluids (for example Create sequenced assembly steps) are shown as "N entries":
  use the Text button for those.
- Text values (like a spell glyph name) are shown but not changed with buttons; "mode" and "heat_requirement" are the
  only text values with a switch.
- Recipes a mod builds in code (not from files) can be viewed but not always saved.
