package dev.recipeforge;

import java.io.BufferedReader;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.item.crafting.RecipeHolder;

/** Helps the picture editor for other mods' recipes. */
public final class ModRecipeServer {
    private ModRecipeServer() {}

    private static boolean hasFluid(JsonElement el) {
        if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            if (o.has("fluid") && o.get("fluid").isJsonPrimitive()) {
                return true;
            }
            for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                if (hasFluid(e.getValue())) {
                    return true;
                }
            }
        } else if (el.isJsonArray()) {
            for (JsonElement e : el.getAsJsonArray()) {
                if (hasFluid(e)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The first list entry (in any recipe of this machine type) that holds a fluid. */
    private static JsonObject findFluidEntry(JsonElement el) {
        if (el.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
                JsonElement v = e.getValue();
                if (v.isJsonArray()) {
                    for (JsonElement item : v.getAsJsonArray()) {
                        if (item.isJsonObject() && hasFluid(item)) {
                            return item.getAsJsonObject().deepCopy();
                        }
                    }
                }
                JsonObject inner = findFluidEntry(v);
                if (inner != null) {
                    return inner;
                }
            }
        }
        return null;
    }

    /** Looks at the machine's own recipes to learn how that mod writes a fluid, so "Add fluid" always uses the right format. */
    public static void fluidTemplate(ServerPlayer player, MinecraftServer server, String serializerId) {
        for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
            ResourceLocation key = BuiltInRegistries.RECIPE_SERIALIZER.getKey(holder.value().getSerializer());
            if (key == null || !key.toString().equals(serializerId)) {
                continue;
            }
            Optional<Resource> res = server.getResourceManager().getResource(ResourceLocation.fromNamespaceAndPath(
                    holder.id().getNamespace(), "recipe/" + holder.id().getPath() + ".json"));
            if (res.isEmpty()) {
                continue;
            }
            try (BufferedReader reader = res.get().openAsReader()) {
                JsonElement root = JsonParser.parseString(reader.lines().collect(Collectors.joining("\n")));
                JsonObject found = findFluidEntry(root);
                if (found != null) {
                    ServerHandler.send(player, "FLUID_TEMPLATE", serializerId, found.toString());
                    return;
                }
            } catch (Exception ignored) {
                // try the next recipe
            }
        }
        ServerHandler.send(player, "FLUID_TEMPLATE", serializerId, "");
    }
}
