package com.roften.avilixlogger.client.gui;

import com.roften.avilixlogger.net.C2SInspectToolPayload;
import com.roften.avilixlogger.net.C2SRequestPagePayload;
import com.roften.avilixlogger.net.C2SRequestDetailsPayload;
import com.roften.avilixlogger.net.GuiFilters;
import com.roften.avilixlogger.net.LogRow;
import com.roften.avilixlogger.net.S2CLogDetailsPayload;
import com.roften.avilixlogger.net.S2CLogPagePayload;
import com.roften.avilixlogger.net.S2CInspectToolPayload;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
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
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Optional client GUI for viewing logs.
 *
 * Note: this is intentionally lightweight and works even if the server is headless.
 */
public final class LogViewerScreen extends Screen {

    // GUI layout is intentionally client-side: admins can shrink controls/text without changing server config.
    private static final int BUTTON_SCALE_MIN = 45;
    private static final int BUTTON_SCALE_MAX = 125;
    private static final int LOG_TEXT_SCALE_MIN = 55;
    private static final int LOG_TEXT_SCALE_MAX = 140;
    private static final int BACKGROUND_DIM_MIN = 0;
    private static final int BACKGROUND_DIM_MAX = 100;

    private static boolean guiStateLoaded = false;
    private static int savedTimePresetIdx = GuiFilters.DEFAULT.timePresetIdx();
    private static int savedRadiusPresetIdx = GuiFilters.DEFAULT.radiusPresetIdx();
    private static int savedTypePresetIdx = GuiFilters.DEFAULT.typePresetIdx();
    private static boolean savedAggregatedMode = true;
    private static String savedActorFilter = GuiFilters.DEFAULT.actor();
    private static String savedTrainFilter = GuiFilters.DEFAULT.train();
    private static String savedPlaneNameFilter = GuiFilters.DEFAULT.planeName();
    private static String savedBlockIdFilter = GuiFilters.DEFAULT.blockId();
    private static String savedTimeInput = "";
    private static String savedRadiusInput = "";
    private static String savedActorInput = "";
    private static String savedTrainInput = "";
    private static String savedPlaneNameInput = "";
    private static String savedBlockIdInput = "";
    private static String savedSearchInput = "";
    private static int savedButtonScalePercent = 100;
    private static int savedLogTextScalePercent = 100;
    private static int savedBackgroundDimPercent = 0;

    private final List<LogRow> rows = new ArrayList<>();
    private int pageIndex = 1;
    private boolean hasPrev;
    private boolean hasNext;
    private String title = "";

    private int selected = -1;
    private final Set<Long> expandedRows = new HashSet<>();
    private int listScroll = 0;

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
    private Button btnButtonsMinus;
    private Button btnButtonsPlus;
    private Button btnTextMinus;
    private Button btnTextPlus;
    private BackgroundDimSlider backgroundDimSlider;

    private EditBox searchBox;
    private EditBox timeBox;
    private EditBox radiusBox;
    private EditBox actorBox;
    private EditBox trainBox;
    private EditBox planeNameBox;
    private EditBox blockIdBox;
    private EditBox inspectToolBox;
    private Button btnApply;
    private Button btnBlockId;
    private Button btnInspectToolLabel;
    private Button btnInspectToolApply;
    private Button btnInspectToolReset;

    private boolean inspectToolSettingsLoaded = false;
    private boolean canEditInspectTool = false;
    private String inspectToolItemId = "";
    private Component inspectToolStatus = Component.empty();
    private int inspectToolStatusY = 0;

    private boolean typeDropdownOpen = false;
    private int typeDropdownScroll = 0;
    private StringWidget titleWidget;

    private int detailsPanelTop = 0;

    private int rightPanelW() {
        // B-/B+ controls both button height and side-panel/input width.
        float wScale = wideControlScale();
        int base = Math.round(190 * wScale);
        int min = Math.max(72, Math.round(105 * wScale));
        int max = Math.max(min, Math.min(220, this.width / 3));
        if (this.width <= 760) max = Math.min(max, Math.max(min, this.width / 4));
        return clampInt(base, min, max);
    }

    private int rightPanelX() {
        return this.width - 10 - rightPanelW();
    }

    private int rowsPerPage() {
        // Leave space for the selected-row preview bar. Smaller log text = more visible log rows.
        int available = (this.height - 30) - listTop() - 24;
        return Math.max(8, Math.min(140, available / listRowH()));
    }

    private record DisplayLine(int rowIndex, Component text, boolean groupHeader, boolean child) {}

    private List<DisplayLine> displayLines() {
        String filter = this.searchBox == null ? "" : this.searchBox.getValue().trim().toLowerCase(java.util.Locale.ROOT);
        List<DisplayLine> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            LogRow row = rows.get(i);
            Component line = row.line() == null ? Component.empty() : row.line();
            if (!filter.isEmpty() && !line.getString().toLowerCase(java.util.Locale.ROOT).contains(filter)) continue;
            boolean expandable = row.aggregated() && row.groupedLines() != null && !row.groupedLines().isEmpty();
            out.add(new DisplayLine(i, line, expandable, false));
            if (expandable && expandedRows.contains(row.id())) {
                for (Component groupedLine : row.groupedLines()) {
                    out.add(new DisplayLine(i, groupedLine == null ? Component.empty() : groupedLine, false, true));
                }
            }
        }
        return out;
    }

    private int maxListScroll(List<DisplayLine> lines) {
        return Math.max(0, (lines == null ? 0 : lines.size()) - rowsPerPage());
    }

    private int leftPanelW() {
        // B-/B+ controls both button height and side-panel/input width.
        float wScale = wideControlScale();
        int base = Math.min(260, Math.max(150, this.width / 4));
        int scaled = Math.round(base * wScale);
        int max = Math.max(92, Math.min(280, this.width / 3));
        int min = Math.min(max, Math.max(74, Math.round(132 * wScale)));
        return clampInt(scaled, min, max);
    }

    private int listLeftX() {
        return 10 + leftPanelW() + 10;
    }

    private int sideInputGap() {
        return Math.max(2, Math.round(6 * sideControlScale()));
    }

    private int sideButtonW(int panelW) {
        int minByScale = Math.round(50 * wideControlScale());
        int target = Math.round(120 * wideControlScale());
        return Math.min(target, Math.max(minByScale, (int) (panelW * 0.42f)));
    }

    private int sideBoxW(int panelW, int buttonW, int gap) {
        return Math.max(Math.round(32 * wideControlScale()), panelW - buttonW - gap);
    }

    public LogViewerScreen() {
        super(Component.translatable("gui.avilixlogger.title"));
        loadGuiState();
        restoreSavedStateToFields();
    }

    private int listTop() {
        // Only the pager row lives at the top. Filters/inputs are in the left sidebar.
        return 18 + controlH() + 8;
    }

    private float autoControlScale() {
        float byHeight;
        if (this.height <= 420) byHeight = 0.62f;
        else if (this.height <= 540) byHeight = 0.72f;
        else if (this.height <= 680) byHeight = 0.86f;
        else byHeight = 1.0f;

        float byWidth = this.width <= 720 ? 0.82f : 1.0f;
        return Math.min(byHeight, byWidth);
    }

    private float controlScale() {
        return clampFloat(autoControlScale() * (savedButtonScalePercent / 100.0f), 0.42f, 1.25f);
    }

    private float wideControlScale() {
        // Width is no longer controlled by a separate W-/W+ pair: B-/B+ changes both height and width.
        return controlScale();
    }

    private boolean compactControlLabels() {
        return controlScale() < 0.82f || this.width <= 760;
    }

    private float sideControlScale() {
        return Math.min(controlScale(), wideControlScale());
    }

    private float logTextScale() {
        float auto = this.height <= 480 ? 0.88f : 1.0f;
        return clampFloat(auto * (savedLogTextScalePercent / 100.0f), 0.55f, 1.40f);
    }

    private int controlH() {
        return Math.max(9, Math.round(20 * controlScale()));
    }

    private int controlGap() {
        return Math.max(1, Math.round(2 * controlScale()));
    }

    private int listRowH() {
        // T-/T+ controls the real visual size of log rows.
        // Blur is disabled separately by bypassing Screen#render/renderBackground; do not remove this scaling again.
        int scaledFontH = Math.max(1, Math.round(this.font.lineHeight * logTextScale()));
        return Math.max(5, scaledFontH + Math.max(1, Math.round(3 * logTextScale())));
    }

    private int detailLineH() {
        int scaledFontH = Math.max(1, Math.round(this.font.lineHeight * logTextScale()));
        return Math.max(5, scaledFontH + Math.max(1, Math.round(1 * logTextScale())));
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clampFloat(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    protected void init() {
        super.init();
        int cx = this.width / 2;
        int top = 18;

        // Layout constants. These shrink automatically on high Minecraft GUI scales.
        final int leftPad = 10;
        final int leftPanelW = leftPanelW();
        final int leftX = leftPad;
        final int leftRowH = controlH();
        final int leftGap = controlGap();
        final int leftTop = top + leftRowH + leftGap + 2;

        this.titleWidget = this.addRenderableWidget(new StringWidget(0, 6, this.width, 10,
                (title.isEmpty() ? Component.translatable("gui.avilixlogger.title") : Component.literal(title))
                        .withStyle(ChatFormatting.GOLD),
                this.font));
        // 1.21.1 StringWidget has no setCentered(); we center by positioning the widget.

        this.btnPrev = addButton(Component.literal("<"), b -> sendPage(C2SRequestPagePayload.Nav.PREV),
                cx - Math.round(60 * controlScale()), top, controlH(), controlH());

        this.btnNext = addButton(Component.literal(">"), b -> sendPage(C2SRequestPagePayload.Nav.NEXT),
                cx + Math.round(40 * controlScale()), top, controlH(), controlH());

        this.btnRefresh = addButton(Component.literal("⟳"), b -> sendPage(C2SRequestPagePayload.Nav.SAME),
                cx - Math.round(20 * controlScale()), top, Math.max(controlH() * 2, Math.round(40 * controlScale())), controlH());

        // Mode toggles moved to the right sidebar (people confuse them with Details/Raw).
        // We'll create them later after we know rightX.
        this.btnAgg = null;
        this.btnRaw = null;

        // Left sidebar: compact rows (button + input on the same line)
        int y = leftTop;
        final int inputGap = sideInputGap();
        final int btnW = sideButtonW(leftPanelW);
        final int boxW = sideBoxW(leftPanelW, btnW, inputGap);

        // Time
        this.btnTime = addButton(Component.translatable("gui.avilixlogger.filter.time"), b -> cycleTime(),
                leftX, y, btnW, leftRowH);
        this.timeBox = makeEditBox(leftX + btnW + inputGap, y, boxW, leftRowH, Component.translatable("gui.avilixlogger.input.time"));
        this.timeBox.setHint(Component.translatable("gui.avilixlogger.hint.time"));
        this.timeBox.setValue(savedTimeInput);
        this.addRenderableWidget(this.timeBox);
        y += leftRowH + leftGap;

        // Radius
        this.btnRadius = addButton(Component.translatable("gui.avilixlogger.filter.radius"), b -> cycleRadius(),
                leftX, y, btnW, leftRowH);
        this.radiusBox = makeEditBox(leftX + btnW + inputGap, y, boxW, leftRowH, Component.translatable("gui.avilixlogger.input.radius"));
        this.radiusBox.setHint(Component.translatable("gui.avilixlogger.hint.radius"));
        this.radiusBox.setValue(savedRadiusInput);
        this.addRenderableWidget(this.radiusBox);
        y += leftRowH + leftGap;

        // Type dropdown (full width)
        this.btnType = addButton(Component.translatable("gui.avilixlogger.filter.type"), b -> toggleTypeDropdown(),
                leftX, y, leftPanelW, leftRowH);
        y += leftRowH + leftGap;

        // Actor
        this.btnActor = addButton(Component.translatable("gui.avilixlogger.filter.actor"), b -> cycleActor(),
                leftX, y, btnW, leftRowH);
        this.actorBox = makeEditBox(leftX + btnW + inputGap, y, boxW, leftRowH, Component.translatable("gui.avilixlogger.input.actor"));
        this.actorBox.setHint(Component.translatable("gui.avilixlogger.hint.actor"));
        this.actorBox.setValue(savedActorInput);
        this.addRenderableWidget(this.actorBox);
        y += leftRowH + leftGap;

        // Train
        this.btnTrain = addButton(Component.translatable("gui.avilixlogger.filter.train"), b -> cycleTrain(),
                leftX, y, btnW, leftRowH);
        this.trainBox = makeEditBox(leftX + btnW + inputGap, y, boxW, leftRowH, Component.translatable("gui.avilixlogger.input.train"));
        this.trainBox.setHint(Component.translatable("gui.avilixlogger.hint.train"));
        this.trainBox.setValue(savedTrainInput);
        this.addRenderableWidget(this.trainBox);
        y += leftRowH + leftGap;

        // Plane name (only relevant for Type=PLANES). Kept hidden otherwise.
        this.btnPlaneName = addButton(Component.literal("Самолёт"), b -> {},
                leftX, y, btnW, leftRowH);
        this.btnPlaneName.active = false;
        this.planeNameBox = makeEditBox(leftX + btnW + inputGap, y, boxW, leftRowH, Component.literal("Самолёт"));
        this.planeNameBox.setHint(Component.literal("имя/тип самолёта"));
        this.planeNameBox.setValue(savedPlaneNameInput);
        this.addRenderableWidget(this.planeNameBox);
        y += leftRowH + leftGap;

        // Block id filter
        this.btnBlockId = addButton(Component.literal("Блок"), b -> {},
                leftX, y, btnW, leftRowH);
        this.btnBlockId.active = false;
        this.blockIdBox = makeEditBox(leftX + btnW + inputGap, y, boxW, leftRowH, Component.literal("block id"));
        this.blockIdBox.setHint(Component.literal("minecraft:chest"));
        this.blockIdBox.setValue(savedBlockIdInput);
        this.addRenderableWidget(this.blockIdBox);
        y += leftRowH + leftGap;

        // Search (label-style button + box)
        Button btnSearch = addButton(Component.translatable("gui.avilixlogger.filter.search"), b -> {},
                leftX, y, btnW, leftRowH);
        btnSearch.active = false;
        this.searchBox = makeEditBox(leftX + btnW + inputGap, y, boxW, leftRowH, Component.translatable("gui.avilixlogger.input.search"));
        this.searchBox.setHint(Component.translatable("gui.avilixlogger.hint.search"));
        this.searchBox.setValue(savedSearchInput);
        this.addRenderableWidget(this.searchBox);
        y += leftRowH + leftGap;

        // Clear + Apply
        this.btnClear = addButton(Component.translatable("gui.avilixlogger.filter.clear"), b -> clearFilters(),
                leftX, y, leftPanelW, leftRowH);
        y += leftRowH + leftGap;

        this.btnApply = addButton(Component.translatable("gui.avilixlogger.apply"), b -> applyCustomInputs(),
                leftX, y, leftPanelW, leftRowH);
        y += leftRowH + leftGap;

        // Server-side inspect-tool setting. The server decides whether this player may edit it.
        this.btnInspectToolLabel = addButton(Component.translatable("gui.avilixlogger.inspect_tool"), b -> {},
                leftX, y, btnW, leftRowH);
        this.btnInspectToolLabel.active = false;
        this.inspectToolBox = makeEditBox(leftX + btnW + inputGap, y, boxW, leftRowH,
                Component.translatable("gui.avilixlogger.inspect_tool"));
        this.inspectToolBox.setHint(Component.translatable("gui.avilixlogger.inspect_tool.hint"));
        this.inspectToolBox.setMaxLength(256);
        this.inspectToolBox.setValue(this.inspectToolItemId);
        this.inspectToolBox.active = false;
        this.addRenderableWidget(this.inspectToolBox);
        y += leftRowH + leftGap;

        int toolHalf = (leftPanelW - leftGap) / 2;
        this.btnInspectToolApply = addButton(Component.translatable("gui.avilixlogger.inspect_tool.save"), b -> setInspectTool(),
                leftX, y, toolHalf, leftRowH);
        this.btnInspectToolReset = addButton(Component.translatable("gui.avilixlogger.inspect_tool.reset"), b -> resetInspectTool(),
                leftX + toolHalf + leftGap, y, leftPanelW - toolHalf - leftGap, leftRowH);
        this.inspectToolStatusY = y + leftRowH + leftGap;

        // Row action buttons (operate on selected row) - right sidebar.
        // On large Minecraft GUI scale these are compact two-column rows, so the details panel keeps room.
        int rightX = rightPanelX();
        int rightW = rightPanelW();
        int btnH = controlH();
        int gap = controlGap();
        int actionTop = top + btnH + gap + 2;
        int half = (rightW - gap) / 2;
        this.btnAgg = addButton(Component.translatable("gui.avilixlogger.mode.agg"), b -> setAggregatedMode(true),
                rightX, actionTop, half, btnH);
        this.btnRaw = addButton(Component.translatable("gui.avilixlogger.mode.raw"), b -> setAggregatedMode(false),
                rightX + half + gap, actionTop, rightW - half - gap, btnH);

        int y2 = actionTop + btnH + gap;
        int scaleButtonCount = 4;
        int tiny = Math.max(12, (rightW - gap * (scaleButtonCount - 1)) / scaleButtonCount);
        int sx = rightX;
        this.btnButtonsMinus = addButton(Component.literal("B-"), b -> adjustButtonScale(-5),
                sx, y2, tiny, btnH);
        sx += tiny + gap;
        this.btnButtonsPlus = addButton(Component.literal("B+"), b -> adjustButtonScale(5),
                sx, y2, tiny, btnH);
        sx += tiny + gap;
        this.btnTextMinus = addButton(Component.literal("T-"), b -> adjustLogTextScale(-5),
                sx, y2, tiny, btnH);
        sx += tiny + gap;
        this.btnTextPlus = addButton(Component.literal("T+"), b -> adjustLogTextScale(5),
                sx, y2, rightX + rightW - sx, btnH);

        y2 += btnH + gap;
        this.backgroundDimSlider = this.addRenderableWidget(new BackgroundDimSlider(
                rightX, y2, rightW, btnH, savedBackgroundDimPercent));

        y2 += btnH + gap;
        this.btnCopy = addButton(sideLabel("gui.avilixlogger.copy_xyz", "XYZ"), b -> copySelectedXYZ(),
                rightX, y2, half, btnH);
        this.btnTp = addButton(sideLabel("gui.avilixlogger.copy_tp", "TP"), b -> runSelectedTpCmd(),
                rightX + half + gap, y2, rightW - half - gap, btnH);

        y2 += btnH + gap;
        this.btnCopyFull = addButton(sideLabel("gui.avilixlogger.copy_full", "Full"), b -> copyCurrentTabText(),
                rightX, y2, half, btnH);
        this.btnCopyJson = addButton(sideLabel("gui.avilixlogger.copy_json", "JSON"), b -> requestJsonAndCopy(),
                rightX + half + gap, y2, rightW - half - gap, btnH);

        y2 += btnH + gap;
        this.btnTabDetails = addButton(sideLabel("gui.avilixlogger.tab.details", "D"), b -> { showRawTab = false; saveGuiStateFromInstance(); },
                rightX, y2, half, btnH);
        this.btnTabRaw = addButton(sideLabel("gui.avilixlogger.tab.raw", "Raw"), b -> { showRawTab = true; saveGuiStateFromInstance(); requestRawIfNeeded(); },
                rightX + half + gap, y2, rightW - half - gap, btnH);

        y2 += btnH + gap;
        this.btnShowRaw = addButton(sideLabel("gui.avilixlogger.show_raw", "RAW"), b -> requestRawIfNeeded(),
                rightX, y2, rightW, btnH);

        // Details panel must start BELOW the right-side controls to avoid overlap.
        this.detailsPanelTop = y2 + btnH + gap + 2;

        updateButtons();
        refreshFilterButtonLabels();
    }

    private Button addButton(Component message, Button.OnPress onPress, int x, int y, int w, int h) {
        return this.addRenderableWidget(new ScaledTextButton(x, y, w, h, message, onPress));
    }

    private EditBox makeEditBox(int x, int y, int w, int h, Component message) {
        return new ScaledEditBox(this.font, x, y, w, h, message);
    }

    /**
     * Vanilla Button scales only the rectangle. The label is always drawn with the normal Minecraft font size.
     * When B- makes controls very small, text starts overflowing. This button scales and clips its own label.
     */
    private final class ScaledTextButton extends Button {
        private ScaledTextButton(int x, int y, int width, int height, Component message, Button.OnPress onPress) {
            super(x, y, width, height, message, onPress, Button.DEFAULT_NARRATION);
        }

        @Override
        public void renderString(GuiGraphics g, Font font, int color) {
            Component message = this.getMessage();
            int x = this.getX();
            int y = this.getY();
            int w = this.getWidth();
            int h = this.getHeight();
            int textW = Math.max(1, font.width(message));
            float s = controlScale();
            s = Math.min(s, Math.max(0.35f, (h - 4) / (float) font.lineHeight));
            s = Math.min(s, Math.max(0.35f, (w - 6) / (float) textW));
            s = clampFloat(s, 0.35f, 1.25f);

            int scLeft = x + 1;
            int scTop = y + 1;
            int scRight = x + w - 1;
            int scBottom = y + h - 1;
            if (scRight <= scLeft || scBottom <= scTop) return;

            g.enableScissor(scLeft, scTop, scRight, scBottom);
            g.pose().pushPose();
            float drawX = x + (w / 2.0f) - (textW * s / 2.0f);
            float drawY = y + (h / 2.0f) - (font.lineHeight * s / 2.0f);
            g.pose().translate(drawX, drawY, 0);
            g.pose().scale(s, s, 1.0f);
            g.drawString(font, message, 0, 0, color, false);
            g.pose().popPose();
            g.disableScissor();
        }
    }

    /**
     * EditBox also does not follow our custom B-/B+ scale. We keep its normal input behavior,
     * but draw the visible text ourselves so long values are clipped and small controls stay readable.
     */
    private final class ScaledEditBox extends EditBox {
        private final Font boxFont;
        private Component localHint = Component.empty();

        private ScaledEditBox(Font font, int x, int y, int width, int height, Component message) {
            super(font, x, y, width, height, message);
            this.boxFont = font;
        }

        @Override
        public void setHint(Component hint) {
            super.setHint(hint);
            this.localHint = hint == null ? Component.empty() : hint;
        }

        @Override
        public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            int x = this.getX();
            int y = this.getY();
            int w = this.getWidth();
            int h = this.getHeight();
            if (w <= 0 || h <= 0) return;

            boolean focused = this.isFocused();
            int border = focused ? 0xFFE0E0E0 : 0xFF777777;
            int bg = this.active ? 0xE0000000 : 0xA0202020;
            g.fill(x, y, x + w, y + h, border);
            g.fill(x + 1, y + 1, x + w - 1, y + h - 1, bg);

            String value = this.getValue() == null ? "" : this.getValue();
            boolean hint = value.isEmpty() && !focused;
            Component text = hint ? this.localHint.copy().withStyle(ChatFormatting.DARK_GRAY) : Component.literal(value);
            int color = this.active ? 0xFFE0E0E0 : 0xFF808080;
            if (hint) color = 0xFF808080;

            float s = Math.min(controlScale(), Math.max(0.35f, (h - 4) / (float) this.boxFont.lineHeight));
            s = clampFloat(s, 0.45f, 1.10f);
            int innerLeft = x + 3;
            int innerTop = y + 2;
            int innerRight = x + w - 3;
            int innerBottom = y + h - 2;
            if (innerRight <= innerLeft || innerBottom <= innerTop) return;

            g.enableScissor(innerLeft, innerTop, innerRight, innerBottom);
            g.pose().pushPose();
            float drawY = y + (h / 2.0f) - (this.boxFont.lineHeight * s / 2.0f);
            g.pose().translate(innerLeft, drawY, 0);
            g.pose().scale(s, s, 1.0f);
            g.drawString(this.boxFont, text, 0, 0, color, false);
            if (focused && !hint && (System.currentTimeMillis() / 500L) % 2L == 0L) {
                int cursor = Math.max(0, Math.min(value.length(), this.getCursorPosition()));
                String before = value.substring(0, cursor);
                int cx = this.boxFont.width(before);
                g.fill(cx, 0, cx + 1, this.boxFont.lineHeight, 0xFFFFFFFF);
            }
            g.pose().popPose();
            g.disableScissor();
        }
    }

    /**
     * Client-only background dimmer. It uses a plain black overlay instead of Minecraft's
     * screen background, so log text remains crisp and no blur shader is enabled.
     */
    private final class BackgroundDimSlider extends AbstractSliderButton {
        private BackgroundDimSlider(int x, int y, int width, int height, int percent) {
            super(x, y, width, height, Component.empty(), percentToSliderValue(percent));
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable("gui.avilixlogger.background_dim", savedBackgroundDimPercent));
        }

        @Override
        protected void applyValue() {
            int percent = clampInt((int) Math.round(this.value * BACKGROUND_DIM_MAX),
                    BACKGROUND_DIM_MIN, BACKGROUND_DIM_MAX);
            if (percent == savedBackgroundDimPercent) return;
            savedBackgroundDimPercent = percent;
            updateMessage();
            saveGuiStateFromInstance();
        }

        private static double percentToSliderValue(int percent) {
            return clampInt(percent, BACKGROUND_DIM_MIN, BACKGROUND_DIM_MAX) / (double) BACKGROUND_DIM_MAX;
        }
    }

    private Component sideLabel(String translationKey, String compact) {
        return compactControlLabels() ? Component.literal(compact) : Component.translatable(translationKey);
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
        saveGuiStateFromInstance();
        PacketDistributor.sendToServer(new C2SRequestPagePayload(nav, aggregatedMode, currentFilters()));
    }

    /** Called once after Minecraft has initialized all widgets for this screen. */
    public void requestInitialData() {
        sendPage(C2SRequestPagePayload.Nav.FIRST);
        PacketDistributor.sendToServer(new C2SInspectToolPayload(C2SInspectToolPayload.Action.QUERY, ""));
    }

    public void applyInspectTool(S2CInspectToolPayload payload) {
        if (payload == null) return;
        this.inspectToolSettingsLoaded = true;
        this.canEditInspectTool = payload.canEdit();
        this.inspectToolItemId = payload.itemId() == null ? "" : payload.itemId();
        if (this.inspectToolBox != null) {
            // Preserve an invalid value so the admin can correct it after a rejected SET.
            if (payload.success() || this.inspectToolBox.getValue().isBlank()) {
                this.inspectToolBox.setValue(this.inspectToolItemId);
            }
            this.inspectToolBox.active = this.canEditInspectTool;
        }
        this.inspectToolStatus = payload.message() == null ? Component.empty() : payload.message();
        updateButtons();
    }

    private void setInspectTool() {
        if (!inspectToolSettingsLoaded || !canEditInspectTool || inspectToolBox == null) return;
        String itemId = inspectToolBox.getValue() == null ? "" : inspectToolBox.getValue().trim();
        PacketDistributor.sendToServer(new C2SInspectToolPayload(C2SInspectToolPayload.Action.SET, itemId));
    }

    private void resetInspectTool() {
        if (!inspectToolSettingsLoaded || !canEditInspectTool) return;
        PacketDistributor.sendToServer(new C2SInspectToolPayload(C2SInspectToolPayload.Action.RESET, ""));
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
        this.expandedRows.clear();
        this.listScroll = 0;
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
        if (inspectToolBox != null) inspectToolBox.active = inspectToolSettingsLoaded && canEditInspectTool;
        if (btnInspectToolApply != null) btnInspectToolApply.active = inspectToolSettingsLoaded && canEditInspectTool;
        if (btnInspectToolReset != null) btnInspectToolReset.active = inspectToolSettingsLoaded && canEditInspectTool;
    }

    private void setAggregatedMode(boolean aggregated) {
        if (this.aggregatedMode == aggregated) return;
        this.aggregatedMode = aggregated;
        this.selected = -1;
        this.selectedEntryId = -1L;
        this.detailLines = List.of();
        this.rawLines = List.of();
        this.detailsScroll = 0;
        this.expandedRows.clear();
        this.listScroll = 0;
        this.lastJson = "";
        this.pendingCopyJson = false;
        updateButtons();
        saveGuiStateFromInstance();
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

        updateAdaptiveInputWidths();
        if (mouseInsideAnyInput(mouseX, mouseY)) {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        // Row selection
        int listLeft = listLeftX();
        int listTop = listTop();
        int rowH = listRowH();
        int listWidth = Math.max(60, (rightPanelX() - 10) - listLeft); // leave room for right panel
        int rpp = rowsPerPage();

        if (mouseX >= listLeft && mouseX <= listLeft + listWidth && mouseY >= listTop && mouseY <= listTop + rpp * rowH) {
            List<DisplayLine> visibleLines = displayLines();
            this.listScroll = clampInt(this.listScroll, 0, maxListScroll(visibleLines));
            int displayIndex = this.listScroll + (int) ((mouseY - listTop) / rowH);
            if (displayIndex >= 0 && displayIndex < visibleLines.size()) {
                DisplayLine clicked = visibleLines.get(displayIndex);
                int idx = clicked.rowIndex();
                LogRow clickedRow = rows.get(idx);

                // The chevron is a real disclosure control: clicking it expands/collapses all
                // underlying rows locally without issuing another database query.
                if (button == 0 && clicked.groupHeader() && mouseX <= listLeft + 13) {
                    if (!expandedRows.add(clickedRow.id())) expandedRows.remove(clickedRow.id());
                    List<DisplayLine> afterToggle = displayLines();
                    this.listScroll = clampInt(this.listScroll, 0, maxListScroll(afterToggle));
                    return true;
                }

                // Quick owner fill: when viewing Plane logs, clicking the actor name auto-fills the Owner filter.
                if (button == 0 && this.typePresetIdx == 8) {
                    Component line = clicked.text();
                    int relX = (int) (mouseX - listLeft - (clicked.child() ? 14 : (clicked.groupHeader() ? 13 : 0)));
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
                this.selectedEntryId = clickedRow.id();
                this.detailLines = List.of(Component.literal("Loading…").withStyle(ChatFormatting.DARK_GRAY));
                this.rawLines = List.of();
                this.detailsScroll = 0;
                PacketDistributor.sendToServer(new C2SRequestDetailsPayload(
                        clickedRow.id(), C2SRequestDetailsPayload.Mode.DETAILS, clickedRow.rawIds(), clickedRow.dim()));
                updateButtons();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (typeDropdownOpen) {
            DropdownLayout layout = typeDropdownLayout();
            if (layout != null) {
                int maxScroll = Math.max(0, TYPE_COUNT - layout.visibleCount());
                int step = (int) Math.signum(deltaY);
                this.typeDropdownScroll = clampInt(this.typeDropdownScroll - step, 0, maxScroll);
            }
            return true; // modal
        }

        int listLeft = listLeftX();
        int listRight = rightPanelX() - 10;
        int listTop = listTop();
        int listBottom = listTop + rowsPerPage() * listRowH();
        if (mouseX >= listLeft && mouseX <= listRight && mouseY >= listTop && mouseY <= listBottom) {
            List<DisplayLine> visibleLines = displayLines();
            int step = (int) Math.signum(deltaY);
            this.listScroll = clampInt(this.listScroll - step, 0, maxListScroll(visibleLines));
            return true;
        }

        int rightX = rightPanelX();
        int detailsTop = listTop() + 8;
        int detailsBottom = this.height - 36;
        if (mouseX >= rightX && mouseX <= this.width - 10 && mouseY >= detailsTop && mouseY <= detailsBottom) {
            int step = (int) Math.signum(deltaY);
            List<Component> lines = showRawTab ? rawLines : detailLines;
            if (lines == null) lines = List.of();
            int headerH = 14;
            int y = detailsTop + headerH;
            int maxLines = Math.max(0, (detailsBottom - y) / detailLineH());
            int maxStart = Math.max(0, lines.size() - maxLines);
            this.detailsScroll = Math.max(0, Math.min(maxStart, this.detailsScroll - step));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Hard-disable vanilla 1.21.x screen background/blur for this transparent admin overlay.
        // Do not draw any dim layer and do not trigger the menu blur shader here.
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Do NOT call Screen#renderBackground or Screen#render here.
        // On 1.21.x some Screen render paths apply the vanilla blurred/dimmed background.
        // This GUI draws logs/details before widgets, so a late vanilla background pass makes
        // the already-drawn log text look smeared while the buttons stay sharp.
        updateAdaptiveInputWidths();
        renderBackgroundDim(g);

        int listLeft = listLeftX();
        int listTop = listTop();
        int rowH = listRowH();
        int rpp = rowsPerPage();
        int listWidth = Math.max(60, (rightPanelX() - 10) - listLeft);

        // Small page indicator
        g.drawString(this.font, Component.literal("page " + pageIndex).withStyle(ChatFormatting.DARK_GRAY),
                this.width - 10 - 90, 6, 0xFFFFFF, false);

        // Render base rows plus locally expanded children. The list itself scrolls when disclosed
        // rows no longer fit, so every hidden instance remains reachable on low resolutions.
        List<DisplayLine> visibleLines = displayLines();
        this.listScroll = clampInt(this.listScroll, 0, maxListScroll(visibleLines));
        int end = Math.min(visibleLines.size(), this.listScroll + rpp);
        for (int displayIndex = this.listScroll; displayIndex < end; displayIndex++) {
            DisplayLine displayLine = visibleLines.get(displayIndex);
            int drawn = displayIndex - this.listScroll;
            int y = listTop + drawn * rowH;
            if (displayLine.rowIndex() == selected) {
                g.fill(listLeft - 2, y - 1, listLeft + listWidth + 2, y + rowH, 0x55222222);
            }

            int textX = listLeft;
            if (displayLine.groupHeader()) {
                boolean expanded = expandedRows.contains(rows.get(displayLine.rowIndex()).id());
                drawLogString(g, Component.literal(expanded ? "▼" : "▶").withStyle(ChatFormatting.GOLD),
                        listLeft, y + Math.max(1, (rowH - this.font.lineHeight) / 2), 0xFFFFFF);
                textX += 13;
            } else if (displayLine.child()) {
                drawLogString(g, Component.literal("•").withStyle(ChatFormatting.DARK_GRAY),
                        listLeft + 4, y + Math.max(1, (rowH - this.font.lineHeight) / 2), 0xFFFFFF);
                textX += 14;
            }
            // Keep formatting/colors and native crisp Minecraft font; clip by width.
            g.enableScissor(textX, y, listLeft + listWidth, y + rowH);
            drawLogString(g, displayLine.text(), textX,
                    y + Math.max(1, (rowH - this.font.lineHeight) / 2), 0xFFFFFF);
            g.disableScissor();
        }

        if (visibleLines.size() > rpp && rpp > 0) {
            int barX = listLeft + listWidth - 2;
            int barTop = listTop;
            int barBottom = listTop + rpp * rowH;
            g.fill(barX, barTop, barX + 2, barBottom, 0x55222222);
            int maxScroll = maxListScroll(visibleLines);
            int thumbH = Math.max(8, Math.round((barBottom - barTop) * (rpp / (float) visibleLines.size())));
            int thumbY = barTop + Math.round((barBottom - barTop - thumbH)
                    * (maxScroll == 0 ? 0.0f : this.listScroll / (float) maxScroll));
            g.fill(barX, thumbY, barX + 2, thumbY + thumbH, 0x99AAAAAA);
        }

        // Selected row preview (full line) at bottom
        if (selected >= 0 && selected < rows.size()) {
            LogRow r = rows.get(selected);
            Component full = r.line();
            int y = this.height - 24;
            g.fill(8, y - 2, this.width - 8, y + 12, 0x66000000);
            drawLogString(g, full, 10, y, 0xFFFFFF);
        }

        // Right-side details panel
        renderDetailsPanel(g);
        renderInspectToolStatus(g);

        // Render widgets after rows/details so an active adaptive input field stays readable above the log list.
        // Do this manually instead of super.render(...): on some 1.21.x mappings/modpacks
        // Screen#render may route through background rendering and blur/dim content that was
        // already drawn earlier in this method.
        renderWidgetsWithoutScreenBackground(g, mouseX, mouseY, partialTick);

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

    private void renderBackgroundDim(GuiGraphics g) {
        int percent = clampInt(savedBackgroundDimPercent, BACKGROUND_DIM_MIN, BACKGROUND_DIM_MAX);
        if (percent <= 0) return;
        int alpha = Math.round(255.0f * (percent / 100.0f));
        // Dim only the journal/details viewport. Side filters, top controls and the surrounding
        // world remain untouched. Scissor is kept in addition to exact fill bounds so future
        // rendering changes cannot leak the overlay back onto the whole screen.
        int left = clampInt(listLeftX() - 4, 0, this.width);
        int top = clampInt(listTop() - 3, 0, this.height);
        int right = clampInt(this.width - 8, left, this.width);
        int bottom = clampInt(this.height - 10, top, this.height);
        if (right <= left || bottom <= top) return;
        g.enableScissor(left, top, right, bottom);
        g.fill(left, top, right, bottom, alpha << 24);
        g.disableScissor();
    }


    private void renderWidgetsWithoutScreenBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        for (net.minecraft.client.gui.components.Renderable renderable : this.renderables) {
            renderable.render(g, mouseX, mouseY, partialTick);
        }
    }

    private void renderInspectToolStatus(GuiGraphics g) {
        if (inspectToolStatus == null || inspectToolStatus.getString().isBlank() || inspectToolStatusY <= 0) return;
        int left = 10;
        int right = left + leftPanelW();
        float scale = clampFloat(controlScale(), 0.50f, 1.0f);
        g.enableScissor(left, inspectToolStatusY, right, inspectToolStatusY + Math.max(8, controlH()));
        g.pose().pushPose();
        g.pose().translate(left, inspectToolStatusY, 0);
        g.pose().scale(scale, scale, 1.0f);
        g.drawString(this.font, inspectToolStatus, 0, 0, 0xFFFFFF, false);
        g.pose().popPose();
        g.disableScissor();
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
        int splitW = Math.max(10, Math.round(wrapW / Math.max(0.25f, logTextScale())));
        java.util.ArrayList<net.minecraft.util.FormattedCharSequence> lines = new java.util.ArrayList<>();
        for (Component c : base) {
            if (c == null) continue;
            var split = this.font.split(c, splitW);
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
        int lineH = detailLineH();
        int maxLines = Math.max(0, (panelBottom - y) / lineH);
        int maxStart = Math.max(0, lines.size() - maxLines);
        if (detailsScroll > maxStart) detailsScroll = maxStart;
        int start = detailsScroll;
        for (int i = 0; i < maxLines && (start + i) < lines.size(); i++) {
            net.minecraft.util.FormattedCharSequence c = lines.get(start + i);
            int yy = y + i * lineH;
            g.enableScissor(panelLeft + 2, yy, panelRight - 6, yy + lineH);
            drawLogString(g, c, panelLeft + 3, yy, 0xFFFFFF);
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

    private void updateAdaptiveInputWidths() {
        int panelW = leftPanelW();
        int gap = sideInputGap();
        int btnW = sideButtonW(panelW);
        int baseX = 10 + btnW + gap;
        int baseW = sideBoxW(panelW, btnW, gap);
        adaptInputBox(timeBox, baseX, baseW);
        adaptInputBox(radiusBox, baseX, baseW);
        adaptInputBox(actorBox, baseX, baseW);
        adaptInputBox(trainBox, baseX, baseW);
        adaptInputBox(planeNameBox, baseX, baseW);
        adaptInputBox(blockIdBox, baseX, baseW);
        adaptInputBox(searchBox, baseX, baseW);
        adaptInputBox(inspectToolBox, baseX, baseW);
    }

    private float inputTextScaleFor(int h) {
        return clampFloat(Math.min(controlScale(), Math.max(0.35f, (h - 4) / (float) this.font.lineHeight)), 0.45f, 1.10f);
    }

    private void adaptInputBox(EditBox box, int baseX, int baseW) {
        if (box == null) return;
        box.setX(baseX);
        int w = baseW;
        if (box.isFocused()) {
            String value = box.getValue() == null ? "" : box.getValue();
            float s = inputTextScaleFor(box.getHeight());
            int wanted = Math.max(baseW, Math.round(this.font.width(value) * s) + 30);
            int maxW = Math.max(baseW, rightPanelX() - 12 - baseX);
            w = clampInt(wanted, baseW, maxW);
        }
        box.setWidth(w);
    }

    private boolean mouseInsideAnyInput(double mouseX, double mouseY) {
        return mouseInsideInput(timeBox, mouseX, mouseY)
                || mouseInsideInput(radiusBox, mouseX, mouseY)
                || mouseInsideInput(actorBox, mouseX, mouseY)
                || mouseInsideInput(trainBox, mouseX, mouseY)
                || mouseInsideInput(planeNameBox, mouseX, mouseY)
                || mouseInsideInput(blockIdBox, mouseX, mouseY)
                || mouseInsideInput(searchBox, mouseX, mouseY)
                || mouseInsideInput(inspectToolBox, mouseX, mouseY);
    }

    private boolean mouseInsideInput(EditBox box, double mouseX, double mouseY) {
        if (box == null || !box.visible) return false;
        return mouseX >= box.getX() && mouseX <= box.getX() + box.getWidth()
                && mouseY >= box.getY() && mouseY <= box.getY() + box.getHeight();
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
        String dim = r.dim() == null ? "" : r.dim().trim();
        String xyz = r.x() + " " + r.y() + " " + r.z();
        String currentDim = "";
        if (Minecraft.getInstance() != null && Minecraft.getInstance().level != null) {
            currentDim = Minecraft.getInstance().level.dimension().location().toString();
        }
        String cmd = (!dim.isBlank() && !dim.equals("*") && !dim.equals(currentDim))
                ? "execute in " + dim + " run tp @s " + xyz
                : "tp " + xyz;
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
        if (typeDropdownOpen) {
            this.typeDropdownScroll = 0;
            DropdownLayout layout = typeDropdownLayout();
            if (layout != null) {
                if (typePresetIdx >= layout.visibleCount()) {
                    this.typeDropdownScroll = clampInt(
                            typePresetIdx - layout.visibleCount() + 1,
                            0,
                            TYPE_COUNT - layout.visibleCount());
                }
            }
        }
    }

    private static final int TYPE_COUNT = 9;

    private record DropdownLayout(int x, int y, int width, int itemHeight, int firstIndex, int visibleCount) {}

    private DropdownLayout typeDropdownLayout() {
        if (btnType == null) return null;
        final int margin = 4;
        int itemH = Math.max(9, Math.round(18 * controlScale()));

        float preferredTextScale = clampFloat(controlScale(), 0.45f, 1.0f);
        int maxTextW = 1;
        for (int i = 0; i < TYPE_COUNT; i++) {
            maxTextW = Math.max(maxTextW,
                    this.font.width(Component.translatable("gui.avilixlogger.type." + typeKey(i))));
        }
        int availableW = Math.max(20, this.width - margin * 2);
        int desiredW = Math.max(btnType.getWidth(), Math.round(maxTextW * preferredTextScale) + 14);
        int w = Math.min(availableW, desiredW);
        int x = clampInt(btnType.getX(), margin, Math.max(margin, this.width - margin - w));

        int belowY = btnType.getY() + btnType.getHeight();
        int spaceBelow = Math.max(0, this.height - margin - belowY);
        int spaceAbove = Math.max(0, btnType.getY() - margin);
        int fullHeight = TYPE_COUNT * itemH;
        boolean openDown = spaceBelow >= fullHeight || spaceBelow >= spaceAbove;
        int availableH = openDown ? spaceBelow : spaceAbove;

        // At extreme resolutions keep every pixel inside the screen and expose remaining options
        // through wheel scrolling instead of letting the popup leave the viewport.
        if (availableH < itemH) itemH = Math.max(1, availableH);
        int visible = Math.max(1, Math.min(TYPE_COUNT, availableH / Math.max(1, itemH)));
        int maxScroll = Math.max(0, TYPE_COUNT - visible);
        this.typeDropdownScroll = clampInt(this.typeDropdownScroll, 0, maxScroll);
        int popupH = visible * itemH;
        int y = openDown ? belowY : btnType.getY() - popupH;
        y = clampInt(y, margin, Math.max(margin, this.height - margin - popupH));
        return new DropdownLayout(x, y, w, itemH, this.typeDropdownScroll, visible);
    }

    private boolean handleTypeDropdownClick(double mouseX, double mouseY) {
        DropdownLayout layout = typeDropdownLayout();
        if (layout == null) return false;
        int h = layout.visibleCount() * layout.itemHeight();
        if (mouseX < layout.x() || mouseX > layout.x() + layout.width()
                || mouseY < layout.y() || mouseY > layout.y() + h) return false;
        int idx = layout.firstIndex() + (int) ((mouseY - layout.y()) / layout.itemHeight());
        if (idx < 0 || idx >= TYPE_COUNT) return false;
        this.typePresetIdx = idx;
        this.typeDropdownOpen = false;
        refreshFilterButtonLabels();
        sendPage(C2SRequestPagePayload.Nav.FIRST);
        return true;
    }

    private void renderTypeDropdown(GuiGraphics g, int mouseX, int mouseY) {
        if (!typeDropdownOpen) return;
        DropdownLayout layout = typeDropdownLayout();
        if (layout == null) return;
        int h = layout.visibleCount() * layout.itemHeight();
        g.fill(layout.x(), layout.y(), layout.x() + layout.width(), layout.y() + h, 0xFF0A0A0A);
        for (int slot = 0; slot < layout.visibleCount(); slot++) {
            int i = layout.firstIndex() + slot;
            int yy = layout.y() + slot * layout.itemHeight();
            boolean hover = mouseX >= layout.x() && mouseX <= layout.x() + layout.width()
                    && mouseY >= yy && mouseY <= yy + layout.itemHeight();
            if (i == typePresetIdx) g.fill(layout.x(), yy, layout.x() + layout.width(), yy + layout.itemHeight(), 0x55333333);
            if (hover) g.fill(layout.x(), yy, layout.x() + layout.width(), yy + layout.itemHeight(), 0x55222222);
            Component label = Component.translatable("gui.avilixlogger.type." + typeKey(i));
            g.enableScissor(layout.x() + 2, yy + 1,
                    layout.x() + layout.width() - 2, yy + layout.itemHeight() - 1);
            drawDropdownLabel(g, label, layout.x() + 5, yy, layout.width() - 10, layout.itemHeight());
            g.disableScissor();
        }

        // Thin edge markers indicate that more choices are available with the mouse wheel.
        if (layout.firstIndex() > 0) g.fill(layout.x(), layout.y(), layout.x() + layout.width(), layout.y() + 1, 0xFFFFAA00);
        if (layout.firstIndex() + layout.visibleCount() < TYPE_COUNT) {
            g.fill(layout.x(), layout.y() + h - 1, layout.x() + layout.width(), layout.y() + h, 0xFFFFAA00);
        }
    }

    private void drawDropdownLabel(GuiGraphics g, Component label, int x, int y, int availableWidth, int itemHeight) {
        int textW = Math.max(1, this.font.width(label));
        float scale = Math.min(controlScale(), Math.max(0.20f, (itemHeight - 2) / (float) this.font.lineHeight));
        scale = Math.min(scale, Math.max(0.20f, availableWidth / (float) textW));
        scale = clampFloat(scale, 0.20f, 1.10f);
        g.pose().pushPose();
        float drawY = y + (itemHeight - this.font.lineHeight * scale) / 2.0f;
        g.pose().translate(x, drawY, 0);
        g.pose().scale(scale, scale, 1.0f);
        g.drawString(this.font, label, 0, 0, 0xFFFFFF, false);
        g.pose().popPose();
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
        boolean compact = compactControlLabels();
        if (btnTime != null) btnTime.setMessage(compact ? Component.literal("T: " + t) : Component.translatable("gui.avilixlogger.filter.time.v", t));
        if (btnRadius != null) btnRadius.setMessage(compact ? Component.literal("R: " + r) : Component.translatable("gui.avilixlogger.filter.radius.v", r));
        if (btnType != null) btnType.setMessage(compact ? Component.literal("Тип: ").append(ty) : Component.translatable("gui.avilixlogger.filter.type.v", ty));

        String actor = (actorBox != null && !actorBox.getValue().isBlank()) ? actorBox.getValue().trim() : (actorFilter == null ? "" : actorFilter);
        String train = (trainBox != null && !trainBox.getValue().isBlank()) ? trainBox.getValue().trim() : (trainFilter == null ? "" : trainFilter);

        if (btnActor != null) {
            Component actorValue = actor.isBlank() ? Component.translatable("gui.avilixlogger.value.any") : Component.literal(trimLabel(actor, compact ? 8 : 16));
            btnActor.setMessage(compact ? Component.literal("Иг: ").append(actorValue) : Component.translatable("gui.avilixlogger.filter.actor.v", actorValue));
        }
        // Reuse the existing "train" input as a context-sensitive filter:
        // - Type=TRAINS: train name/needle
        // - Type=PLANES: owner name/needle
        boolean planes = typePresetIdx == 8;
        if (trainBox != null) {
            trainBox.setHint(Component.translatable(planes ? "gui.avilixlogger.hint.owner" : "gui.avilixlogger.hint.train"));
        }
        if (btnTrain != null) {
            String key = planes ? "gui.avilixlogger.filter.owner.v" : "gui.avilixlogger.filter.train.v";
            Component trainValue = train.isBlank() ? Component.translatable("gui.avilixlogger.value.any") : Component.literal(trimLabel(train, compact ? 8 : 16));
            btnTrain.setMessage(compact ? Component.literal(planes ? "Вл: " : "По: ").append(trainValue) : Component.translatable(key, trainValue));
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
            if (inspectToolBox != null && inspectToolBox.isFocused()) {
                setInspectTool();
                return true;
            }
            if ((timeBox != null && timeBox.isFocused())
                    || (radiusBox != null && radiusBox.isFocused())
                    || (actorBox != null && actorBox.isFocused())
                    || (trainBox != null && trainBox.isFocused())
                    || (planeNameBox != null && planeNameBox.isFocused())
                    || (blockIdBox != null && blockIdBox.isFocused())
                    || (searchBox != null && searchBox.isFocused())) {
                applyCustomInputs();
                return true;
            }
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void drawLogString(GuiGraphics g, Component text, int x, int y, int color) {
        drawScaledLogString(g, () -> g.drawString(this.font, text, 0, 0, color, false), x, y);
    }

    private void drawLogString(GuiGraphics g, net.minecraft.util.FormattedCharSequence text, int x, int y, int color) {
        drawScaledLogString(g, () -> g.drawString(this.font, text, 0, 0, color, false), x, y);
    }

    private void drawScaledLogString(GuiGraphics g, Runnable draw, int x, int y) {
        float s = logTextScale();
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(s, s, 1.0f);
        draw.run();
        g.pose().popPose();
    }

    private static Path guiStatePath() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.gameDirectory != null) {
                return mc.gameDirectory.toPath().resolve("config").resolve("avilixlogger_gui.properties");
            }
        } catch (Throwable ignored) {}
        return Path.of("config", "avilixlogger_gui.properties");
    }

    private static void loadGuiState() {
        if (guiStateLoaded) return;
        guiStateLoaded = true;
        Path path = guiStatePath();
        if (!Files.isRegularFile(path)) return;
        Properties p = new Properties();
        try (java.io.Reader reader = Files.newBufferedReader(path)) {
            p.load(reader);
            savedTimePresetIdx = parseInt(p.getProperty("timePreset"), savedTimePresetIdx);
            savedRadiusPresetIdx = parseInt(p.getProperty("radiusPreset"), savedRadiusPresetIdx);
            savedTypePresetIdx = parseInt(p.getProperty("typePreset"), savedTypePresetIdx);
            savedAggregatedMode = Boolean.parseBoolean(p.getProperty("aggregated", String.valueOf(savedAggregatedMode)));
            savedActorFilter = p.getProperty("actorFilter", savedActorFilter);
            savedTrainFilter = p.getProperty("trainFilter", savedTrainFilter);
            savedPlaneNameFilter = p.getProperty("planeNameFilter", savedPlaneNameFilter);
            savedBlockIdFilter = p.getProperty("blockIdFilter", savedBlockIdFilter);
            savedTimeInput = p.getProperty("timeInput", savedTimeInput);
            savedRadiusInput = p.getProperty("radiusInput", savedRadiusInput);
            savedActorInput = p.getProperty("actorInput", savedActorInput);
            savedTrainInput = p.getProperty("trainInput", savedTrainInput);
            savedPlaneNameInput = p.getProperty("planeNameInput", savedPlaneNameInput);
            savedBlockIdInput = p.getProperty("blockIdInput", savedBlockIdInput);
            savedSearchInput = p.getProperty("searchInput", savedSearchInput);
            savedButtonScalePercent = clampInt(parseInt(p.getProperty("buttonScalePercent"), savedButtonScalePercent), BUTTON_SCALE_MIN, BUTTON_SCALE_MAX);
            savedLogTextScalePercent = clampInt(parseInt(p.getProperty("logTextScalePercent"), savedLogTextScalePercent), LOG_TEXT_SCALE_MIN, LOG_TEXT_SCALE_MAX);
            savedBackgroundDimPercent = clampInt(parseInt(p.getProperty("backgroundDimPercent"), savedBackgroundDimPercent), BACKGROUND_DIM_MIN, BACKGROUND_DIM_MAX);
        } catch (Throwable ignored) {
            // Broken local client config should never break opening the logger GUI.
        }
    }

    private static int parseInt(String s, int fallback) {
        try {
            if (s == null || s.isBlank()) return fallback;
            return Integer.parseInt(s.trim());
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private void restoreSavedStateToFields() {
        this.timePresetIdx = savedTimePresetIdx;
        this.radiusPresetIdx = savedRadiusPresetIdx;
        this.typePresetIdx = savedTypePresetIdx;
        this.aggregatedMode = savedAggregatedMode;
        this.actorFilter = savedActorFilter == null ? "" : savedActorFilter;
        this.trainFilter = savedTrainFilter == null ? "" : savedTrainFilter;
        this.planeNameFilter = savedPlaneNameFilter == null ? "" : savedPlaneNameFilter;
        this.blockIdFilter = savedBlockIdFilter == null ? "" : savedBlockIdFilter;
    }

    private void saveGuiStateFromInstance() {
        savedTimePresetIdx = this.timePresetIdx;
        savedRadiusPresetIdx = this.radiusPresetIdx;
        savedTypePresetIdx = this.typePresetIdx;
        savedAggregatedMode = this.aggregatedMode;
        savedActorFilter = this.actorFilter == null ? "" : this.actorFilter;
        savedTrainFilter = this.trainFilter == null ? "" : this.trainFilter;
        savedPlaneNameFilter = this.planeNameFilter == null ? "" : this.planeNameFilter;
        savedBlockIdFilter = this.blockIdFilter == null ? "" : this.blockIdFilter;
        savedTimeInput = this.timeBox == null ? savedTimeInput : this.timeBox.getValue();
        savedRadiusInput = this.radiusBox == null ? savedRadiusInput : this.radiusBox.getValue();
        savedActorInput = this.actorBox == null ? savedActorInput : this.actorBox.getValue();
        savedTrainInput = this.trainBox == null ? savedTrainInput : this.trainBox.getValue();
        savedPlaneNameInput = this.planeNameBox == null ? savedPlaneNameInput : this.planeNameBox.getValue();
        savedBlockIdInput = this.blockIdBox == null ? savedBlockIdInput : this.blockIdBox.getValue();
        savedSearchInput = this.searchBox == null ? savedSearchInput : this.searchBox.getValue();
        saveGuiStateToDisk();
    }

    private static void saveGuiStateToDisk() {
        Properties p = new Properties();
        p.setProperty("timePreset", String.valueOf(savedTimePresetIdx));
        p.setProperty("radiusPreset", String.valueOf(savedRadiusPresetIdx));
        p.setProperty("typePreset", String.valueOf(savedTypePresetIdx));
        p.setProperty("aggregated", String.valueOf(savedAggregatedMode));
        p.setProperty("actorFilter", savedActorFilter == null ? "" : savedActorFilter);
        p.setProperty("trainFilter", savedTrainFilter == null ? "" : savedTrainFilter);
        p.setProperty("planeNameFilter", savedPlaneNameFilter == null ? "" : savedPlaneNameFilter);
        p.setProperty("blockIdFilter", savedBlockIdFilter == null ? "" : savedBlockIdFilter);
        p.setProperty("timeInput", savedTimeInput == null ? "" : savedTimeInput);
        p.setProperty("radiusInput", savedRadiusInput == null ? "" : savedRadiusInput);
        p.setProperty("actorInput", savedActorInput == null ? "" : savedActorInput);
        p.setProperty("trainInput", savedTrainInput == null ? "" : savedTrainInput);
        p.setProperty("planeNameInput", savedPlaneNameInput == null ? "" : savedPlaneNameInput);
        p.setProperty("blockIdInput", savedBlockIdInput == null ? "" : savedBlockIdInput);
        p.setProperty("searchInput", savedSearchInput == null ? "" : savedSearchInput);
        p.setProperty("buttonScalePercent", String.valueOf(savedButtonScalePercent));
        p.setProperty("wideControlScalePercent", String.valueOf(savedButtonScalePercent));
        p.setProperty("logTextScalePercent", String.valueOf(savedLogTextScalePercent));
        p.setProperty("backgroundDimPercent", String.valueOf(savedBackgroundDimPercent));
        try {
            Path path = guiStatePath();
            Files.createDirectories(path.getParent());
            try (java.io.Writer writer = Files.newBufferedWriter(path)) {
                p.store(writer, "Avilix Logger client GUI state");
            }
        } catch (Throwable ignored) {
            // Read-only config folder should not break the GUI.
        }
    }

    private void adjustButtonScale(int delta) {
        savedButtonScalePercent = clampInt(savedButtonScalePercent + delta, BUTTON_SCALE_MIN, BUTTON_SCALE_MAX);
        saveGuiStateFromInstance();
        rebuildGuiSafely();
    }

    private void adjustLogTextScale(int delta) {
        savedLogTextScalePercent = clampInt(savedLogTextScalePercent + delta, LOG_TEXT_SCALE_MIN, LOG_TEXT_SCALE_MAX);
        saveGuiStateFromInstance();
        rebuildGuiSafely();
    }

    private void rebuildGuiSafely() {
        try {
            this.clearWidgets();
            this.init();
            updateButtons();
        } catch (Throwable ignored) {
            // If a future MC version changes Screen internals, the new scale still applies on reopen.
        }
    }

    @Override
    public void removed() {
        saveGuiStateFromInstance();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
