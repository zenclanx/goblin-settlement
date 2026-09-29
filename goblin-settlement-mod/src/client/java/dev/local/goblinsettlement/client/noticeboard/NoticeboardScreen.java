package dev.local.goblinsettlement.client.noticeboard;

import dev.local.goblinsettlement.noticeboard.SettlementReport;
import java.util.Optional;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Draws the settlement snapshot. Layout only: every number here was computed on the server. */
public final class NoticeboardScreen extends Screen {
    private final Optional<SettlementReport> report;

    public NoticeboardScreen(Optional<SettlementReport> report) {
        super(Component.translatable("block.goblin_settlement.noticeboard"));
        this.report = report;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
