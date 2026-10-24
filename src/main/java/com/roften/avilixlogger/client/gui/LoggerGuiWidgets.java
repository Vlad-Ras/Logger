package com.roften.avilixlogger.client.gui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/** Shared journal controls: identical font scaling, clipping, input borders and Minecraft buttons. */
final class LoggerGuiWidgets {
    private LoggerGuiWidgets() {}
    static void beginFrame(GuiGraphics g,int width,int height){
        if(net.minecraft.client.Minecraft.getInstance().level==null)g.fill(0,0,width,height,0xFF101010);
    }
    private static float clampFloat(float value,float min,float max){return Math.max(min,Math.min(max,value));}
    /**
     * Vanilla Button scales only the rectangle. The label is always drawn with the normal Minecraft font size.
     * When B- makes controls very small, text starts overflowing. This button scales and clips its own label.
     */
    static final class ScaledTextButton extends Button {
        private final java.util.function.DoubleSupplier scale;
        ScaledTextButton(int x, int y, int width, int height, Component message, Button.OnPress onPress, java.util.function.DoubleSupplier scale) {
            super(x, y, width, height, message, onPress, Button.DEFAULT_NARRATION);
            this.scale = scale;
        }

        @Override
        public void renderString(GuiGraphics g, Font font, int color) {
            Component message = this.getMessage();
            int x = this.getX();
            int y = this.getY();
            int w = this.getWidth();
            int h = this.getHeight();
            int textW = Math.max(1, font.width(message));
            float s = (float) scale.getAsDouble();
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
     * EditBox also does not follow our custom button scale. We keep its normal input behavior,
     * but draw the visible text ourselves so long values are clipped and small controls stay readable.
     */
    static final class ScaledEditBox extends EditBox {
        private final Font boxFont;
        private Component localHint = Component.empty();
        private String renderedValue;
        private Component valueText = Component.empty();

        private final java.util.function.DoubleSupplier scale;
        ScaledEditBox(Font font, int x, int y, int width, int height, Component message, java.util.function.DoubleSupplier scale) {
            super(font, x, y, width, height, message);
            this.boxFont = font;
            this.scale = scale;
        }

        @Override
        public void setHint(Component hint) {
            super.setHint(hint);
            this.localHint = hint == null ? Component.empty() : hint.copy().withStyle(ChatFormatting.DARK_GRAY);
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
            if (!java.util.Objects.equals(renderedValue, value)) { renderedValue = value; valueText = Component.literal(value); }
            Component text = hint ? this.localHint : valueText;
            int color = this.active ? 0xFFE0E0E0 : 0xFF808080;
            if (hint) color = 0xFF808080;

            float s = Math.min((float) scale.getAsDouble(), Math.max(0.35f, (h - 4) / (float) this.boxFont.lineHeight));
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

}
