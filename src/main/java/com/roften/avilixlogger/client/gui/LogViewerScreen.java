package com.roften.avilixlogger.client.gui;

import com.roften.avilixlogger.net.C2SRequestPagePayload;
import com.roften.avilixlogger.net.C2SRequestDetailsPayload;
import com.roften.avilixlogger.net.GuiFilters;
import com.roften.avilixlogger.net.LogRow;
import com.roften.avilixlogger.net.S2CLogDetailsPayload;
import com.roften.avilixlogger.net.S2CLogPagePayload;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.ClickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * Optional client GUI for viewing logs.
 *
 * Note: this is intentionally lightweight and works even if the server is headless.
 */
public final class LogViewerScreen extends Screen {

    /** Row height for the main list. 18px matches vanilla list widgets and avoids cramped/overlapping text. */
    private static final int LIST_ROW_H = 18;

    private final List<LogRow> rows = new ArrayList<>();
    private int pageIndex = 1;
    private boolean hasPrev;
    private boolean hasNext;
    private String title = "";

    private int selected = -1;

    private boolean aggregatedMode = true;
    // Button-driven filters (GUI-only; /log remains raw and unchanged)
    private int timePresetIdx = GuiFilters.DEFAULT.timePresetIdx();
    private int radiusPresetIdx = GuiFilters.DEFAULT.radiusPresetIdx();
    private int typePresetIdx = GuiFilters.DEFAULT.typePresetIdx();
    private String actorFilter = GuiFilters.DEFAULT.actor();
    private String trainFilter = GuiFilters.DEFAULT.train();
    private String planeNameFilter = GuiFilters.DEFAULT.planeName();
    private String blockIdFilter = GuiFilters.DEFAULT.blockId();

    private boolean showRawTab = false;
    private long selectedEntryId = -1L;

    private List<Component> detailLines = List.of();
    private List<Component> rawLines = List.of();
    private int detailsScroll = 0;

    private String lastJson = "";
    private boolean pendingCopyJson = false;

    private Button btnPrev;
    private Button btnNext;
    private Button btnRefresh;
    private Button btnCopy;
    private Button btnTp;

    private Button btnCopyFull;
    private Button btnCopyJson;

    private Button btnAgg;
    private Button btnRaw;

    private Button btnTime;
    private Button btnRadius;
    private Button btnType;
    private Button btnActor;
    private Button btnTrain;
    private Button btnPlaneName;
    private Button btnClear;
    private Button btnTabDetails;
    private Button btnTabRaw;
    private Button btnShowRaw;

    private EditBox searchBox;
    private EditBox timeBox;
    private EditBox radiusBox;
    private EditBox actorBox;
    private EditBox trainBox;
    private EditBox planeNameBox;
    private EditBox blockIdBox;
    private Button btnApply;
    private Button btnBlockId;

    private boolean typeDropdownOpen = false;
    private StringWidget titleWidget;

    private int detailsPanelTop = 0;

    private int rightPanelW() {
        return Math.min(190, Math.max(150, this.width / 5));
    }

    private int rightPanelX() {
        return this.width - 10 - rightPanelW();
    }

    private int rowsPerPage() {
        // Leave space for the selected-row preview bar.
        int available = (this.height - 30) - listTop() - 26;
        // Allow more rows on tall screens; server-side pageSize is configured separately.
        return Math.max(6, Math.min(80, available / LIST_ROW_H));
    }

    private int leftPanelW() {
        return Math.min(260, Math.max(190, this.width / 4));
    }

    private int listLeftX() {
        return 10 + leftPanelW() + 10;
    }

    public LogViewerScreen() {
        super(Component.translatable("gui.avilixlogger.title"));
    }

    private int listTop() {
        // Only the pager row lives at the top. Filters/inputs are in the left sidebar.
        return 18 + 20 + 8;
    }

    @Override
    protected void init() {
        super.init();
        int cx = this.width / 2;
        int top = 18;

        // Layout constants
        final int leftPad = 10;
        final int leftPanelW = Math.min(260, Math.max(190, this.width / 4));
        final int leftX = leftPad;
        final int leftTop = top + 22;
        final int leftRowH = 20;
        final int leftGap = 2;

        this.titleWidget = this.addRenderableWidget(new StringWidget(0, 6, this.width, 10,
                (title.isEmpty() ? Component.translatable("gui.avilixlogger.title") : Component.literal(title))
                        .withStyle(ChatFormatting.GOLD),
                this.font));
        // 1.21.1 StringWidget has no setCentered(); we center by positioning the widget.

        this.btnPrev = this.addRenderableWidget(Button.builder(Component.literal("<"), b -> sendPage(C2SRequestPagePayload.Nav.PREV))
                .bounds(cx - 60, top, 20, 20).build());

        this.btnNext = this.addRenderableWidget(Button.builder(Component.literal(">"), b -> sendPage(C2SRequestPagePayload.Nav.NEXT))
                .bounds(cx + 40, top, 20, 20).build());

        this.btnRefresh = this.addRenderableWidget(Button.builder(Component.literal("⟳"), b -> sendPage(C2SRequestPagePayload.Nav.SAME))
                .bounds(cx - 20, top, 40, 20).build());

        // Mode toggles moved to the right sidebar (people confuse them with Details/Raw).
        // We'll create them later after we know rightX.
        this.btnAgg = null;
        this.btnRaw = null;

        // Left sidebar: compact rows (button + input on the same line)
        int y = leftTop;
        final int inputGap = 6;
        final int btnW = Math.min(120, Math.max(92, (int) (leftPanelW * 0.42f)));
        final int boxW = Math.max(60, leftPanelW - btnW - inputGap);

        // Time
        this.btnTime = this.addRenderableWidget(Button.builder(Component.translatable("gui.avilixlogger.filter.time"), b -> cycleTime())
                .bounds(leftX, y, btnW, leftRowH).build());
        this.timeBox = new EditBox(this.font, leftX + btnW + inputGap, y, boxW, leftRowH, Component.translatable("gui.avilixlogger.input.time"));
        this.timeBox.setHint(Component.translatable("gui.avilixlogger.hint.time"));
        this.addRenderableWidget(this.timeBox);
        y += leftRowH + leftGap;

        // Radius
        this.btnRadius = this.addRenderableWidget(Button.builder(Component.translatable("gui.avilixlogger.filter.radius"), b -> cycleRadius())
                .bounds(leftX, y, btnW, leftRowH).build());
        this.radiusBox = new EditBox(this.font, leftX + btnW + inputGap, y, boxW, leftRowH, Component.translatable("gui.avilixlogger.input.radius"));
        this.radiusBox.setHint(Component.translatable("gui.avilixlogger.hint.radius"));
        this.addRenderableWidget(this.radiusBox);
        y += leftRowH + leftGap;

        // Type dropdown (full width)
        this.btnType = this.addRenderableWidget(Button.builder(Component.translatable("gui.avilixlogger.filter.type"), b -> toggleTypeDropdown())
                .bounds(leftX, y, leftPanelW, leftRowH).build());
        y += leftRowH + leftGap;

        // Actor
        this.btnActor = this.addRenderableWidget(Button.builder(Component.translatable("gui.avilixlogger.filter.actor"), b -> cycleActor())
                .bounds(leftX, y, btnW, leftRowH).build());
        this.actorBox = new EditBox(this.font, leftX + btnW + inputGap, y, boxW, leftRowH, Component.translatable("gui.avilixlogger.input.actor"));
        this.actorBox.setHint(Component.translatable("gui.avilixlogger.hint.actor"));
        this.addRenderableWidget(this.actorBox);
        y += leftRowH + leftGap;

        // Train
        this.btnTrain = this.addRenderableWidget(Button.builder(Component.translatable("gui.avilixlogger.filter.train"), b -> cycleTrain())
                .bounds(leftX, y, btnW, leftRowH).build());
        this.trainBox = new EditBox(this.font, leftX + btnW + inputGap, y, boxW, leftRowH, Component.translatable("gui.avilixlogger.input.train"));
        this.trainBox.setHint(Component.translatable("gui.avilixlogger.hint.train"));
        this.addRenderableWidget(this.trainBox);
        y += leftRowH + leftGap;

        // Plane name (only relevant for Type=PLANES). Kept hidden otherwise.
        this.btnPlaneName = this.addRenderableWidget(Button.builder(Component.literal("Самолёт"), b -> {})
                .bounds(leftX, y, btnW, leftRowH).build());
        this.btnPlaneName.active = false;
        this.planeNameBox = new EditBox(this.font, leftX + btnW + inputGap, y, boxW, leftRowH, Component.literal("Самолёт"));
        this.planeNameBox.setHint(Component.literal("имя/тип самолёта"));
        this.addRenderableWidget(this.planeNameBox);
        y += leftRowH + leftGap;

        // Block id filter
        this.btnBlockId = this.addRenderableWidget(Button.builder(Component.literal("Блок"), b -> {})
                .bounds(leftX, y, btnW, leftRowH).build());
        this.btnBlockId.active = false;
        this.blockIdBox = new EditBox(this.font, leftX + btnW + inputGap, y, boxW, leftRowH, Component.literal("block id"));
        this.blockIdBox.setHint(Component.literal("minecraft:chest"));
        this.addRenderableWidget(this.blockIdBox);
        y += leftRowH + leftGap;

        // Search (label-style button + box)
        Button btnSearch = this.addRenderableWidget(Button.builder(Component.translatable("gui.avilixlogger.filter.search"), b -> {})
                .bounds(leftX, y, btnW, leftRowH).build());
        btnSearch.active = false;
        this.searchBox = new EditBox(this.font, leftX + btnW + inputGap, y, boxW, leftRowH, Component.translatable("gui.avilixlogger.input.search"));
        this.searchBox.setHint(Component.translatable("gui.avilixlogger.hint.search"));
        this.searchBox.setValue("");
        this.addRenderableWidget(this.searchBox);
        y += leftRowH + leftGap;

        // Clear + Apply
        this.btnClear = this.addRenderableWidget(Button.builder(Component.translatable("gui.avilixlogger.filter.clear"), b -> clearFilters())
                .bounds(leftX, y, leftPanelW, leftRowH).build());
        y += leftRowH + leftGap;

        this.btnApply = this.addRenderableWidget(Button.builder(Component.translatable("gui.avilixlogger.apply"), b -> applyCustomInputs())
                .bounds(leftX, y, leftPanelW, leftRowH).build());

        // Row action buttons (operate on selected row) - right sidebar
        int rightX = rightPanelX();
        int rightW = rightPanelW();
        int actionTop = top + 24;

        // Mode toggles first row (no overlap)
        int half = (rightW - 2) / 2;
        this.btnAgg = this.addRenderableWidget(Button.builder(Component.translatable("gui.avilixlogger.mode.agg"), b -> setAggregatedMode(true))
                .bounds(rightX, actionTop, half, 20).build());
        this.btnRaw = this.addRenderableWidget(Button.builder(Component.translatable("gui.avilixlogger.mode.raw"), b -> setAggregatedMode(false))
                .bounds(rightX + half + 2, actionTop, rightW - half - 2, 20).build());

        // Actions
        int y2 = actionTop + 22;
        this.btnCopy = this.addRenderableWidget(Button.builder(Component.translatable("gui.avilixlogger.copy_xyz"), b -> copySelectedXYZ())
                .bounds(rightX, y2, rightW, 20).build());
        y2 += 22;
        this.btnTp = this.addRenderableWidget(Button.builder(Component.translatable("gui.avilixlogger.copy_tp"), b -> runSelectedTpCmd())
                .bounds(rightX, y2, rightW, 20).build());
        y2 += 22;
        this.btnCopyFull = this.addRenderableWidget(Button.builder(Component.translatable("gui.avilixlogger.copy_full"), b -> copyCurrentTabText())
                .bounds(rightX, y2, rightW, 20).build());
        y2 += 22;
        this.btnCopyJson = this.addRenderableWidget(Button.builder(Component.translatable("gui.avilixlogger.copy_json"), b -> requestJsonAndCopy())
                .bounds(rightX, y2, rightW, 20).build());
        y2 += 26;

        // Tabs
        this.btnTabDetails = this.addRenderableWidget(Button.builder(Component.translatable("gui.avilixlogger.tab.details"), b -> { showRawTab = false; })
                .bounds(rightX, y2, half, 20).build());
        this.btnTabRaw = this.addRenderableWidget(Button.builder(Component.translatable("gui.avilixlogger.tab.raw"), b -> { showRawTab = true; requestRawIfNeeded(); })
                .bounds(rightX + half + 2, y2, rightW - half - 2, 20).build());
        y2 += 22;
        this.btnShowRaw = this.addRenderableWidget(Button.builder(Component.translatable("gui.avilixlogger.show_raw"), b -> requestRawIfNeeded())
                .bounds(rightX, y2, rightW, 20).build());

        // Details panel must start BELOW the right-side controls to avoid overlap.
        this.detailsPanelTop = y2 + 26;

        updateButtons();
        refreshFilterButtonLabels();
    }

    private GuiFilters currentFilters() {
        int customTime = parseTimeMinutes(timeBox != null ? timeBox.getValue() : "");
        int customRadius = parseRadius(radiusBox != null ? radiusBox.getValue() : "");
        String actor = (actorBox != null && !actorBox.getValue().isBlank()) ? actorBox.getValue().trim() : (actorFilter == null ? "" : actorFilter);
        String train = (trainBox != null && !trainBox.getValue().isBlank()) ? trainBox.getValue().trim() : (trainFilter == null ? "" : trainFilter);
        String planeName = (planeNameBox != null && !planeNameBox.getValue().isBlank()) ? planeNameBox.getValue().trim() : (planeNameFilter == null ? "" : planeNameFilter);
        String blockId = (blockIdBox != null && !blockIdBox.getValue().isBlank()) ? blockIdBox.getValue().trim() : (blockIdFilter == null ? "" : blockIdFilter);
        return new GuiFilters(timePresetIdx, radiusPresetIdx, typePresetIdx, customTime, customRadius, actor, train, planeName, blockId);
    }

    private void sendPage(C2SRequestPagePayload.Nav nav) {
        PacketDistributor.sendToServer(new C2SRequestPagePayload(nav, aggregatedMode, currentFilters()));
    }

    public void apply(S2CLogPagePayload payload) {
        this.title = payload.title() == null ? "" : payload.title();
        this.pageIndex = payload.pageIndex();
        this.hasPrev = payload.hasPrev();
        this.hasNext = payload.hasNext();
        this.rows.clear();
        if (payload.rows() != null) this.rows.addAll(payload.rows());
        this.selected = -1;
        this.selectedEntryId = -1L;
        this.detailLines = List.of();
        this.rawLines = List.of();
        this.detailsScroll = 0;
        this.lastJson = "";
        this.pendingCopyJson = false;

        if (this.titleWidget != null) {
            this.titleWidget.setMessage((title.isEmpty() ? Component.translatable("gui.avilixlogger.title") : Component.literal(title))
                    .withStyle(ChatFormatting.GOLD));
        }
        updateButtons();
    }

    public void applyDetails(S2CLogDetailsPayload payload) {
        if (payload == null) return;
        if (payload.entryId() != this.selectedEntryId) return;
        if (payload.mode() == C2SRequestDetailsPayload.Mode.DETAILS) {
            this.detailLines = payload.lines() == null ? List.of() : payload.lines();
        } else if (payload.mode() == C2SRequestDetailsPayload.Mode.RAW) {
            this.rawLines = payload.lines() == null ? List.of() : payload.lines();
        } else {
            // JSON
            String json = "";
            if (payload.lines() != null && !payload.lines().isEmpty()) {
                json = payload.lines().get(0).getString();
            }
            this.lastJson = json;
            if (pendingCopyJson) {
                pendingCopyJson = false;
                copyToClipboard(json, "JSON");
            }
        }
    }

    private void updateButtons() {
        if (btnPrev != null) btnPrev.active = hasPrev;
        if (btnNext != null) btnNext.active = hasNext;
        if (btnCopy != null) btnCopy.active = selected >= 0 && selected < rows.size();
        if (btnTp != null) btnTp.active = selected >= 0 && selected < rows.size();
        if (btnCopyFull != null) btnCopyFull.active = selectedEntryId > 0;
        if (btnCopyJson != null) btnCopyJson.active = selectedEntryId > 0;

        if (btnAgg != null) btnAgg.active = !aggregatedMode;
        if (btnRaw != null) btnRaw.active = aggregatedMode;
        if (btnShowRaw != null) {
            boolean can = selected >= 0 && selected < rows.size() && rows.get(selected).rawIds() != null && rows.get(selected).rawIds().length > 0;
            btnShowRaw.active = can;
        }
    }

    private void setAggregatedMode(boolean aggregated) {
        if (this.aggregatedMode == aggregated) return;
        this.aggregatedMode = aggregated;
        this.selected = -1;
        this.selectedEntryId = -1L;
        this.detailLines = List.of();
        this.rawLines = List.of();
        this.detailsScroll = 0;
        this.lastJson = "";
        this.pendingCopyJson = false;
        updateButtons();
        sendPage(C2SRequestPagePayload.Nav.FIRST);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Type dropdown is modal: it must intercept input.
        if (typeDropdownOpen) {
            if (handleTypeDropdownClick(mouseX, mouseY)) return true;
            typeDropdownOpen = false;
            return true; // swallow clicks outside
        }

        // Row selection
        int listLeft = listLeftX();
        int listTop = listTop();
        int rowH = LIST_ROW_H;
        int listWidth = Math.max(60, (rightPanelX() - 10) - listLeft); // leave room for right panel
        int rpp = rowsPerPage();

        if (mouseX >= listLeft && mouseX <= listLeft + listWidth && mouseY >= listTop && mouseY <= listTop + rpp * rowH) {
            int idx = (int) ((mouseY - listTop) / rowH);
            if (idx >= 0 && idx < rows.size()) {
                // Quick owner fill: when viewing Plane logs, clicking the actor name auto-fills the Owner filter.
                if (button == 0 && this.typePresetIdx == 8) {
                    LogRow rr = rows.get(idx);
                    Component line = rr.line();
                    int relX = (int) (mouseX - listLeft);
                    try {
                        Style st = this.font.getSplitter().componentStyleAtWidth(line, relX);
                        if (st != null && st.getClickEvent() != null) {
                            String who = extractWhoFromClick(st.getClickEvent());
                            if (who != null && !who.isBlank() && !"Сервер".equalsIgnoreCase(who)) {
                                this.trainFilter = who;
                                if (this.trainBox != null) this.trainBox.setValue(who);
                                // Keep current selection behavior, but refresh immediately so the list narrows.
                                sendPage(C2SRequestPagePayload.Nav.FIRST);
                            }
                        }
                    } catch (Throwable ignored) {
                        // If style lookup fails, fall back to normal selection behavior.
                    }
                }

                this.selected = idx;
                LogRow r = rows.get(idx);
                this.selectedEntryId = r.id();
                this.detailLines = List.of(Component.literal("Loading…").withStyle(ChatFormatting.DARK_GRAY));
                this.rawLines = List.of();
                this.detailsScroll = 0;
                PacketDistributor.sendToServer(new C2SRequestDetailsPayload(r.id(), C2SRequestDetailsPayload.Mode.DETAILS, r.rawIds(), r.dim()));
                updateButtons();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (typeDropdownOpen) return true; // modal
        int rightX = rightPanelX();
        int detailsTop = listTop() + 8;
        int detailsBottom = this.height - 36;
        if (mouseX >= rightX && mouseX <= this.width - 10 && mouseY >= detailsTop && mouseY <= detailsBottom) {
            int step = (int) Math.signum(deltaY);
            List<Component> lines = showRawTab ? rawLines : detailLines;
            if (lines == null) lines = List.of();
            int headerH = 14;
            int y = detailsTop + headerH;
            int maxLines = Math.max(0, (detailsBottom - y) / 10);
            int maxStart = Math.max(0, lines.size() - maxLines);
            this.detailsScroll = Math.max(0, Math.min(maxStart, this.detailsScroll - step));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g, mouseX, mouseY, partialTick);
        super.render(g, mouseX, mouseY, partialTick);

        int listLeft = listLeftX();
        int listTop = listTop();
        int rowH = LIST_ROW_H;
        int rpp = rowsPerPage();
        int listWidth = Math.max(60, (rightPanelX() - 10) - listLeft);

        // Small page indicator
        g.drawString(this.font, Component.literal("page " + pageIndex).withStyle(ChatFormatting.DARK_GRAY),
                this.width - 10 - 90, 6, 0xFFFFFF, false);

        // Render rows
        String filter = this.searchBox == null ? "" : this.searchBox.getValue().trim().toLowerCase();
        int drawn = 0;
        for (int i = 0; i < rows.size() && drawn < rpp; i++) {
            LogRow r = rows.get(i);
            Component line = r.line();
            if (!filter.isEmpty()) {
                String flat = line.getString().toLowerCase();
                if (!flat.contains(filter)) continue;
            }
            int y = listTop + drawn * rowH;
            if (i == selected) {
                g.fill(listLeft - 2, y - 1, listLeft + listWidth + 2, y + rowH, 0x55222222);
            }
            // Keep formatting/colors; clip by width.
            g.enableScissor(listLeft, y, listLeft + listWidth, y + rowH);
            g.drawString(this.font, line, listLeft, y + 4, 0xFFFFFF, false);
            g.disableScissor();
            drawn++;
        }

        // Selected row preview (full line) at bottom
        if (selected >= 0 && selected < rows.size()) {
            LogRow r = rows.get(selected);
            Component full = r.line();
            int y = this.height - 24;
            g.fill(8, y - 2, this.width - 8, y + 12, 0x66000000);
            g.drawString(this.font, full, 10, y, 0xFFFFFF, false);
        }

        // Right-side details panel
        renderDetailsPanel(g);

        // Dropdown overlays (modal)
        if (typeDropdownOpen) {
            // Render popup on top Z.
            // Note: we intentionally DO NOT dim the whole screen; the dropdown itself has an opaque background,
            // and input is intercepted while it's open.
            g.pose().pushPose();
            g.pose().translate(0, 0, 500);
            renderTypeDropdown(g, mouseX, mouseY);
            g.pose().popPose();
        }
    }

    private void renderDetailsPanel(GuiGraphics g) {
        int rightX = rightPanelX();
        int panelLeft = rightX;
        int panelRight = this.width - 10;
        int panelTop = Math.max(detailsPanelTop, listTop());
        int panelBottom = this.height - 30;

        g.fill(panelLeft, panelTop, panelRight, panelBottom, 0x44000000);

        List<Component> base = showRawTab ? rawLines : detailLines;
        if (base == null) base = List.of();

        // Wrap lines to fit the panel width.
        int wrapW = Math.max(10, (panelRight - panelLeft) - 8);
        java.util.ArrayList<net.minecraft.util.FormattedCharSequence> lines = new java.util.ArrayList<>();
        for (Component c : base) {
            if (c == null) continue;
            var split = this.font.split(c, wrapW);
            if (split == null || split.isEmpty()) {
                lines.add(net.minecraft.util.FormattedCharSequence.EMPTY);
            } else {
                lines.addAll(split);
            }
        }

        // Fixed header inside the panel
        Component hdr = Component.translatable(showRawTab ? "gui.avilixlogger.hdr.raw" : "gui.avilixlogger.hdr.details")
                .withStyle(ChatFormatting.GOLD);
        g.drawString(this.font, hdr, panelLeft + 4, panelTop + 3, 0xFFFFFF, false);
        g.drawString(this.font,
                Component.translatable("gui.avilixlogger.hdr.lines", lines.size()).withStyle(ChatFormatting.DARK_GRAY),
                panelLeft + 54, panelTop + 3, 0xFFFFFF, false);

        int headerH = 14;
        int y = panelTop + headerH;
        int maxLines = Math.max(0, (panelBottom - y) / 10);
        int maxStart = Math.max(0, lines.size() - maxLines);
        if (detailsScroll > maxStart) detailsScroll = maxStart;
        int start = detailsScroll;
        for (int i = 0; i < maxLines && (start + i) < lines.size(); i++) {
            net.minecraft.util.FormattedCharSequence c = lines.get(start + i);
            int yy = y + i * 10;
            g.enableScissor(panelLeft + 2, yy, panelRight - 6, yy + 10);
            g.drawString(this.font, c, panelLeft + 3, yy, 0xFFFFFF, false);
            g.disableScissor();
        }

        // Scrollbar
        if (lines.size() > maxLines && maxLines > 0) {
            int barX = panelRight - 4;
            int barTop = y;
            int barBottom = panelBottom - 4;
            g.fill(barX, barTop, barX + 2, barBottom, 0x55222222);

            float frac = maxStart == 0 ? 0f : (detailsScroll / (float) maxStart);
            int thumbH = Math.max(10, (int) ((barBottom - barTop) * (maxLines / (float) lines.size())));
            int thumbY = barTop + (int) ((barBottom - barTop - thumbH) * frac);
            g.fill(barX, thumbY, barX + 2, thumbY + thumbH, 0x99AAAAAA);
        }
    }

    private void requestRawIfNeeded() {
        if (selected < 0 || selected >= rows.size()) return;
        LogRow r = rows.get(selected);
        if (r.rawIds() == null || r.rawIds().length == 0) return;
        if (rawLines != null && !rawLines.isEmpty()) return;
        this.rawLines = List.of(Component.literal("Loading raw…").withStyle(ChatFormatting.DARK_GRAY));
        PacketDistributor.sendToServer(new C2SRequestDetailsPayload(r.id(), C2SRequestDetailsPayload.Mode.RAW, r.rawIds(), r.dim()));
    }

    private void copySelectedXYZ() {
        if (selected < 0 || selected >= rows.size()) return;
        LogRow r = rows.get(selected);
        String xyz = r.x() + " " + r.y() + " " + r.z();
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.keyboardHandler != null) {
            mc.keyboardHandler.setClipboard(xyz);
            if (mc.player != null) mc.player.displayClientMessage(Component.literal("Copied: " + xyz).withStyle(ChatFormatting.GRAY), true);
        }
    }

    private void runSelectedTpCmd() {
        if (selected < 0 || selected >= rows.size()) return;
        LogRow r = rows.get(selected);
        String cmd = "tp " + r.x() + " " + r.y() + " " + r.z();
        Minecraft mc = Minecraft.getInstance();

        // Run immediately. If we can't (no connection), fall back to clipboard.
        try {
            if (mc != null && mc.player != null && mc.player.connection != null) {
                mc.player.connection.sendCommand(cmd);
                mc.player.displayClientMessage(Component.literal("TP: /" + cmd).withStyle(ChatFormatting.GRAY), true);
                return;
            }
        } catch (Throwable ignored) {}

        if (mc != null && mc.keyboardHandler != null) {
            mc.keyboardHandler.setClipboard("/" + cmd);
            if (mc.player != null) mc.player.displayClientMessage(Component.literal("Copied: /" + cmd).withStyle(ChatFormatting.GRAY), true);
        }
    }

    private void copyCurrentTabText() {
        List<Component> lines = showRawTab ? rawLines : detailLines;
        if (lines == null || lines.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (Component c : lines) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(c.getString());
        }
        copyToClipboard(sb.toString(), "Full");
    }

    private void requestJsonAndCopy() {
        if (selected < 0 || selected >= rows.size()) return;
        LogRow r = rows.get(selected);
        this.pendingCopyJson = true;
        PacketDistributor.sendToServer(new C2SRequestDetailsPayload(r.id(), C2SRequestDetailsPayload.Mode.JSON, new long[0], r.dim()));
    }

    // -------- filter buttons (button-driven args) --------

    private void cycleTime() {
        int[] mins = new int[] { 5, 30, 120, 1440, 10080 };
        timePresetIdx = (timePresetIdx + 1) % mins.length;
        refreshFilterButtonLabels();
        sendPage(C2SRequestPagePayload.Nav.FIRST);
    }

    private void cycleRadius() {
        // 0: 5b, 1: 20b, 2: 50b, 3: WORLD
        radiusPresetIdx = (radiusPresetIdx + 1) % 4;
        refreshFilterButtonLabels();
        sendPage(C2SRequestPagePayload.Nav.FIRST);
    }

    private void cycleType() {
        // 0: ALL, 1: BLOCKS, 2: CONTAINERS, 3: ENTITIES, 4: ITEMS, 5: TRAINS, 6: CANNON, 7: CHAT, 8: PLANES
        typePresetIdx = (typePresetIdx + 1) % TYPE_COUNT;
        refreshFilterButtonLabels();
        sendPage(C2SRequestPagePayload.Nav.FIRST);
    }

    private void toggleTypeDropdown() {
        typeDropdownOpen = !typeDropdownOpen;
    }

    private static final int TYPE_COUNT = 9;

    private boolean handleTypeDropdownClick(double mouseX, double mouseY) {
        if (btnType == null) return false;
        int x = btnType.getX();
        int y = btnType.getY() + btnType.getHeight();
        int w = btnType.getWidth();
        int itemH = 18;
        int h = TYPE_COUNT * itemH;
        if (mouseX < x || mouseX > x + w || mouseY < y || mouseY > y + h) return false;
        int idx = (int) ((mouseY - y) / itemH);
        if (idx < 0 || idx >= TYPE_COUNT) return false;
        this.typePresetIdx = idx;
        this.typeDropdownOpen = false;
        refreshFilterButtonLabels();
        sendPage(C2SRequestPagePayload.Nav.FIRST);
        return true;
    }

    private void renderTypeDropdown(GuiGraphics g, int mouseX, int mouseY) {
        if (!typeDropdownOpen || btnType == null) return;
        int x = btnType.getX();
        int y = btnType.getY() + btnType.getHeight();
        int w = btnType.getWidth();
        int itemH = 18;
        int h = TYPE_COUNT * itemH;
        g.fill(x, y, x + w, y + h, 0xFF0A0A0A);
        for (int i = 0; i < TYPE_COUNT; i++) {
            int yy = y + i * itemH;
            boolean hover = mouseX >= x && mouseX <= x + w && mouseY >= yy && mouseY <= yy + itemH;
            if (i == typePresetIdx) g.fill(x, yy, x + w, yy + itemH, 0x55333333);
            if (hover) g.fill(x, yy, x + w, yy + itemH, 0x55222222);
            Component label = Component.translatable("gui.avilixlogger.type." + typeKey(i));
            g.enableScissor(x + 2, yy + 2, x + w - 2, yy + itemH - 2);
            g.drawString(this.font, label, x + 4, yy + 5, 0xFFFFFF, false);
            g.disableScissor();
        }
    }

    private static String typeKey(int idx) {
        return switch (idx) {
            case 0 -> "all";
            case 1 -> "block";
            case 2 -> "container";
            case 3 -> "entity";
            case 4 -> "item";
            case 5 -> "train";
            case 6 -> "cannon";
            case 7 -> "chat";
            case 8 -> "plane";
            default -> "all";
        };
    }


    private static String extractWhoFromClick(ClickEvent ev) {
        if (ev == null) return null;
        String v = ev.getValue();
        if (v == null) return null;
        v = v.trim();
        // Most rows use a suggest command like: /log lookup 10m <who>
        if (v.startsWith("/log lookup")) {
            String[] parts = v.split("\\s+");
            if (parts.length >= 4) return parts[parts.length - 1];
        }
        // AirplanesLogger-style: /log plane owner <who>
        if (v.startsWith("/log plane owner")) {
            String[] parts = v.split("\\s+");
            if (parts.length >= 4) return parts[parts.length - 1];
        }
        return null;
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (typeDropdownOpen) return true;
        return super.charTyped(codePoint, modifiers);
    }

    private void applyCustomInputs() {
        // Just refresh labels & request page; currentFilters() reads input boxes.
        refreshFilterButtonLabels();
        sendPage(C2SRequestPagePayload.Nav.FIRST);
    }

    private static int parseTimeMinutes(String s) {
        if (s == null) return -1;
        s = s.trim().toLowerCase();
        if (s.isEmpty()) return -1;
        try {
            // Accept: 30m / 2h / 1d / 7d, or just number = minutes.
            if (s.endsWith("m")) return Math.max(0, Integer.parseInt(s.substring(0, s.length() - 1)));
            if (s.endsWith("h")) return Math.max(0, Integer.parseInt(s.substring(0, s.length() - 1)) * 60);
            if (s.endsWith("d")) return Math.max(0, Integer.parseInt(s.substring(0, s.length() - 1)) * 1440);
            return Math.max(0, Integer.parseInt(s));
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static int parseRadius(String s) {
        if (s == null) return -1;
        s = s.trim().toLowerCase();
        if (s.isEmpty()) return -1;
        if (s.equals("world") || s.equals("w") || s.equals("*")) return 0;
        try {
            return Math.max(0, Integer.parseInt(s));
        } catch (Exception ignored) {
            return -1;
        }
    }

    private void cycleActor() {
        // Cycle: empty -> self -> (selected row guess) -> empty ...
        String self = Minecraft.getInstance() != null && Minecraft.getInstance().player != null
                ? Minecraft.getInstance().player.getName().getString()
                : "";
        String guessed = guessActorFromSelectedRow();

        if (actorFilter == null) actorFilter = "";
        if (actorFilter.isBlank()) {
            actorFilter = self;
        } else if (!guessed.isBlank() && !actorFilter.equalsIgnoreCase(guessed)) {
            actorFilter = guessed;
        } else {
            actorFilter = "";
        }
        refreshFilterButtonLabels();
        sendPage(C2SRequestPagePayload.Nav.FIRST);
    }

    private void cycleTrain() {
        // Cycle: empty -> (selected row guess) -> empty
        String guessed = guessTrainFromSelectedRow();
        if (trainFilter == null) trainFilter = "";
        if (trainFilter.isBlank()) {
            trainFilter = guessed;
        } else {
            trainFilter = "";
        }
        refreshFilterButtonLabels();
        sendPage(C2SRequestPagePayload.Nav.FIRST);
    }

    private void clearFilters() {
        this.timePresetIdx = GuiFilters.DEFAULT.timePresetIdx();
        this.radiusPresetIdx = GuiFilters.DEFAULT.radiusPresetIdx();
        this.typePresetIdx = GuiFilters.DEFAULT.typePresetIdx();
        this.actorFilter = "";
        this.trainFilter = "";
        this.planeNameFilter = "";
        this.blockIdFilter = "";
        if (timeBox != null) timeBox.setValue("");
        if (radiusBox != null) radiusBox.setValue("");
        if (actorBox != null) actorBox.setValue("");
        if (trainBox != null) trainBox.setValue("");
        if (planeNameBox != null) planeNameBox.setValue("");
        if (blockIdBox != null) blockIdBox.setValue("");
        this.searchBox.setValue("");
        this.typeDropdownOpen = false;
        refreshFilterButtonLabels();
        sendPage(C2SRequestPagePayload.Nav.FIRST);
    }

    private void refreshFilterButtonLabels() {
        String t = timeLabel();
        String r = radiusLabel();
        Component ty = Component.translatable("gui.avilixlogger.type." + typeKey(typePresetIdx));
        if (btnTime != null) btnTime.setMessage(Component.translatable("gui.avilixlogger.filter.time.v", t));
        if (btnRadius != null) btnRadius.setMessage(Component.translatable("gui.avilixlogger.filter.radius.v", r));
        if (btnType != null) btnType.setMessage(Component.translatable("gui.avilixlogger.filter.type.v", ty));

        String actor = (actorBox != null && !actorBox.getValue().isBlank()) ? actorBox.getValue().trim() : (actorFilter == null ? "" : actorFilter);
        String train = (trainBox != null && !trainBox.getValue().isBlank()) ? trainBox.getValue().trim() : (trainFilter == null ? "" : trainFilter);

        if (btnActor != null) btnActor.setMessage(Component.translatable("gui.avilixlogger.filter.actor.v",
                actor.isBlank() ? Component.translatable("gui.avilixlogger.value.any") : Component.literal(actor)));
        // Reuse the existing "train" input as a context-sensitive filter:
        // - Type=TRAINS: train name/needle
        // - Type=PLANES: owner name/needle
        boolean planes = typePresetIdx == 8;
        if (trainBox != null) {
            trainBox.setHint(Component.translatable(planes ? "gui.avilixlogger.hint.owner" : "gui.avilixlogger.hint.train"));
        }
        if (btnTrain != null) {
            String key = planes ? "gui.avilixlogger.filter.owner.v" : "gui.avilixlogger.filter.train.v";
            btnTrain.setMessage(Component.translatable(key,
                    train.isBlank() ? Component.translatable("gui.avilixlogger.value.any") : Component.literal(trimLabel(train, 16))));
        }

        // Plane name filter widgets are only meaningful in planes preset.
        if (btnPlaneName != null) btnPlaneName.visible = planes;
        if (planeNameBox != null) {
            planeNameBox.visible = planes;
            planeNameBox.active = planes;
        }
    }

    private String timeLabel() {
        int custom = parseTimeMinutes(timeBox != null ? timeBox.getValue() : "");
        if (custom >= 0) {
            if (custom % 1440 == 0 && custom >= 1440) return (custom / 1440) + "d";
            if (custom % 60 == 0 && custom >= 60) return (custom / 60) + "h";
            return custom + "m";
        }
        return switch (timePresetIdx) {
            case 0 -> "5m";
            case 1 -> "30m";
            case 2 -> "2h";
            case 3 -> "1d";
            case 4 -> "7d";
            default -> "?";
        };
    }

    private String radiusLabel() {
        int custom = parseRadius(radiusBox != null ? radiusBox.getValue() : "");
        if (custom >= 0) return custom == 0 ? "WORLD" : String.valueOf(custom);
        return switch (radiusPresetIdx) {
            case 0 -> "5";
            case 1 -> "20";
            case 2 -> "50";
            case 3 -> "WORLD";
            default -> "?";
        };
    }

    private String typeLabel() {
        return typeKey(typePresetIdx).toUpperCase();
    }

    private static String trimLabel(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(0, max - 1)) + "…";
    }

    private String guessActorFromSelectedRow() {
        if (selected < 0 || selected >= rows.size()) return "";
        String s = rows.get(selected).line() == null ? "" : rows.get(selected).line().getString();
        if (s.isBlank()) return "";
        // Heuristic: many of our lines start with player name.
        int sp = s.indexOf(' ');
        String first = sp > 0 ? s.substring(0, sp) : s;
        first = first.replace("[", "").replace("]", "").replace("<", "").replace(">", "");
        // Protect against timestamps like "12:34".
        if (first.contains(":")) return "";
        return first;
    }

    private String guessTrainFromSelectedRow() {
        if (selected < 0 || selected >= rows.size()) return "";
        String s = rows.get(selected).line() == null ? "" : rows.get(selected).line().getString();
        if (s.isBlank()) return "";
        // Try «name»
        int l = s.indexOf('«');
        int r = s.indexOf('»', l + 1);
        if (l >= 0 && r > l) return s.substring(l + 1, r).trim();
        // Try "name"
        int q1 = s.indexOf('"');
        int q2 = q1 >= 0 ? s.indexOf('"', q1 + 1) : -1;
        if (q1 >= 0 && q2 > q1) return s.substring(q1 + 1, q2).trim();
        return "";
    }

    private void copyToClipboard(String text, String label) {
        if (text == null) text = "";
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.keyboardHandler != null) {
            mc.keyboardHandler.setClipboard(text);
            if (mc.player != null) mc.player.displayClientMessage(Component.literal("Copied: " + label).withStyle(ChatFormatting.GRAY), true);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Modal dropdown: swallow all input while open. ESC closes it.
        if (typeDropdownOpen) {
            if (keyCode == 256 /* GLFW_KEY_ESCAPE */) typeDropdownOpen = false;
            return true;
        }

        // Enter applies custom inputs.
        if (keyCode == 257 /* GLFW_KEY_ENTER */ || keyCode == 335 /* GLFW_KEY_KP_ENTER */) {
            if ((timeBox != null && timeBox.isFocused())
                    || (radiusBox != null && radiusBox.isFocused())
                    || (actorBox != null && actorBox.isFocused())
                    || (trainBox != null && trainBox.isFocused())
                    || (searchBox != null && searchBox.isFocused())) {
                applyCustomInputs();
                return true;
            }
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
