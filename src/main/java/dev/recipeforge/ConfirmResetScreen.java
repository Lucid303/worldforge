package dev.recipeforge;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

/** "Are you sure?" before everything is put back to normal. */
public class ConfirmResetScreen extends ForgeScreen {

    public ConfirmResetScreen(Screen parent) {
        super(Component.literal("Reset all"), parent);
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        this.addRenderableWidget(Button.builder(Component.literal("Yes, put everything back to normal"), b -> {
            PacketDistributor.sendToServer(new RecipeEditPayload("RESET_ALL", "", ""));
            status = "Resetting...";
        }).bounds(cx - 120, 110, 240, 20).tooltip(Tooltip.create(Component.literal(
                "Removes every change made with World Forge. A backup zip is saved first."))).build());
        this.addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> this.onClose())
                .bounds(cx - 120, 136, 240, 20).build());
    }

    @Override
    public void onServerResult(boolean ok, String message) {
        status = message;
        if (ok && this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        StationInfo.banner(g, this.font, this.width, new StationInfo.Info("Reset all", Items.TNT, 0xFF8A2A2A), null);
        int cx = this.width / 2;
        g.drawCenteredString(this.font, "Put EVERYTHING back to normal?", cx, 44, 0xFFFFFF);
        g.drawCenteredString(this.font, "This removes every change you made: recipes, loot, tags, colours,", cx, 60, 0xAAAAAA);
        g.drawCenteredString(this.font, "structures, enchantments and world generation.", cx, 72, 0xAAAAAA);
        g.drawCenteredString(this.font, "A backup zip is saved first in the worldforge-exports folder.", cx, 88, 0xAAAAAA);
        drawStatus(g, cx - 120, 166, 240);
    }
}
