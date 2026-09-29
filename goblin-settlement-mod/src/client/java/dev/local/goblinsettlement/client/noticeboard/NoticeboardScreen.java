package dev.local.goblinsettlement.client.noticeboard;

import dev.local.goblinsettlement.noticeboard.NoticeboardText;
import dev.local.goblinsettlement.noticeboard.SettlementReport;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Draws the settlement snapshot. Layout only: every number here was computed on the server. */
public final class NoticeboardScreen extends Screen {
    private static final int MARGIN = 12;
    private static final int TITLE_Y = 12;
    private static final int PANEL_TOP = 30;
    private static final int PANEL_BOTTOM_MARGIN = 36;
    private static final int PADDING = 8;
    private static final int LABEL_COLUMN = 104;
    private static final int SCROLL_STEP = 18;
    private static final int HEADING_COLOUR = 0xFFFFD070;
    private static final int LABEL_COLOUR = 0xFFBFBFBF;
    private static final int VALUE_COLOUR = 0xFFFFFFFF;
    private static final int PANEL_COLOUR = 0xC0000000;

    /** One drawn line: a heading, a label/value pair, a note with an empty label, or a spacer when both are null. */
    private record Line(Component label, Component value, int colour) {
    }

    private final List<NoticeboardText.Section> sections;
    private int scroll = 0;
    private int maxScroll = 0;

    public NoticeboardScreen(Optional<SettlementReport> report) {
        super(Component.translatable("block.goblin_settlement.noticeboard"));
        // The report is turned into rows of translation keys once, here. Everything below draws the
        // result; it never reads a SettlementReport field itself.
        this.sections = NoticeboardText.sections(report);
    }

    @Override
    protected void init() {
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
                .bounds(this.width / 2 - 60, this.height - 28, 120, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        int left = MARGIN;
        int right = this.width - MARGIN;
        int top = PANEL_TOP;
        int bottom = this.height - PANEL_BOTTOM_MARGIN;
        graphics.fill(left, top, right, bottom, PANEL_COLOUR);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, TITLE_Y, VALUE_COLOUR);

        var lines = lines();
        int lineHeight = this.font.lineHeight + 2;
        int contentLeft = left + PADDING;
        int contentRight = right - PADDING;
        int contentTop = top + PADDING;
        int contentBottom = bottom - PADDING;
        this.maxScroll = Math.max(0, lines.size() * lineHeight - (contentBottom - contentTop));
        this.scroll = Math.max(0, Math.min(this.scroll, this.maxScroll));

        // Clip the body to the panel so a line too wide for the window is cut at the edge rather than
        // spilling past it.
        graphics.enableScissor(contentLeft, contentTop, contentRight, contentBottom);
        int y = contentTop - this.scroll;
        for (Line line : lines) {
            if (line.label() != null) {
                graphics.drawString(this.font, line.label(), contentLeft, y, line.colour());
            }
            if (line.value() != null) {
                graphics.drawString(this.font, line.value(), contentLeft + LABEL_COLUMN, y, VALUE_COLOUR);
            }
            y += lineHeight;
        }
        graphics.disableScissor();
    }

    /** The whole body as flat lines, so its height and its drawing cannot drift apart. */
    private List<Line> lines() {
        var lines = new ArrayList<Line>();
        for (NoticeboardText.Section section : this.sections) {
            if (!lines.isEmpty()) {
                lines.add(new Line(null, null, LABEL_COLOUR));
            }
            lines.add(new Line(Component.translatable(section.titleKey()), null, HEADING_COLOUR));
            for (NoticeboardText.Row row : section.rows()) {
                Component label = row.labelKey().isEmpty() ? null : Component.translatable(row.labelKey());
                Component value = row.valueKey().isEmpty() ? null
                        : Component.translatable(row.valueKey(), row.args().toArray());
                lines.add(new Line(label, value, LABEL_COLOUR));
            }
        }
        return lines;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.maxScroll > 0 && scrollY != 0) {
            this.scroll = (int) Math.max(0, Math.min(this.maxScroll, this.scroll - scrollY * SCROLL_STEP));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
