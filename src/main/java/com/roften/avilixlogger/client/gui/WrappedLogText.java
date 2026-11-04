package com.roften.avilixlogger.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import java.util.ArrayList;
import java.util.List;

/** One current text layout per pane; never a history of previously viewed payloads. */
final class WrappedLogText {
    private Object source;
    private int width;
    private Font font;
    private Language language;
    private List<FormattedCharSequence> lines = List.of();

    List<FormattedCharSequence> get(Font font, Object source, int width) {
        Language current = Language.getInstance();
        if (this.source == source && this.width == width && this.font == font && language == current) return lines;
        this.source = source; this.width = width; this.font = font; language = current;
        var result = new ArrayList<FormattedCharSequence>();
        if (source instanceof List<?> list) {
            for (Object item : list) if (item instanceof Component text) result.addAll(font.split(text, width));
        } else if (source instanceof Component text) result.addAll(font.split(text, width));
        else if (source instanceof String text) result.addAll(font.split(Component.literal(text), width));
        lines = List.copyOf(result);
        return lines;
    }
    void clear() { source = null; lines = List.of(); font = null; language = null; }
}
