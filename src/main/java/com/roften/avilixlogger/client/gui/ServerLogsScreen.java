package com.roften.avilixlogger.client.gui;

import com.roften.avilixlogger.core.ServerLogFiles;
import com.roften.avilixlogger.net.ServerLogPayloads;
import com.roften.avilixlogger.net.ServerLogPayloads.Action;
import com.roften.avilixlogger.net.ServerLogPayloads.FileInfo;
import com.roften.avilixlogger.net.ServerLogPayloads.Query;
import com.roften.avilixlogger.net.ServerLogPayloads.View;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

final class ServerLogsScreen extends LoggerScreen {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final long WAIT_NANOS = TimeUnit.SECONDS.toNanos(35);
    private static long sequence;

    private final Screen parent;
    private final net.minecraft.client.multiplayer.ClientPacketListener connection;
    private List<FileInfo> files = List.of();
    private int scroll;
    private long listing;
    private long deadline;
    private Download download;
    private String status = "Запрашиваю список файлов…";
    private boolean initial = true;

    ServerLogsScreen(Screen parent) {
        super(Component.literal("Логи сервера"));
        this.parent = parent;
        connection = Minecraft.getInstance().getConnection();
    }

    static boolean available() {
        var connection = Minecraft.getInstance().getConnection();
        if (connection == null || !connection.hasChannel(Query.TYPE) || !connection.hasChannel(View.TYPE)) return false;
        var log = connection.getCommands().getRoot().getChild("log");
        return log != null && log.getChild("server-logs") != null;
    }

    @Override protected void init() {
        if (initial) {
            initial = false;
            refresh();
        } else rebuild();
    }

    private void rebuild() {
        clearWidgets();
        int w = Math.min(450, width - 20), x = (width - w) / 2;
        int count = Math.max(1, (height - 113) / 23);
        scroll = Math.max(0, Math.min(scroll, files.size() - count));
        for (int i = 0; i < count && scroll + i < files.size(); i++) {
            var file = files.get(scroll + i);
            String label = file.name() + " · " + bytes(file.size());
            var button = button(label, x, 43 + i * 23, w, () -> start(file));
            button.active = listing == 0 && download == null && file.size() <= ServerLogPayloads.MAX_FILE_BYTES;
            button.setTooltip(Tooltip.create(Component.literal("Изменён " + DATE.format(
                    Instant.ofEpochMilli(file.modified()).atZone(ZoneId.systemDefault()))
                    + (file.size() > ServerLogPayloads.MAX_FILE_BYTES ? "\nФайл превышает 128 МиБ." : "\nНажмите, чтобы сохранить файл."))));
        }
        int half = (w - 6) / 2;
        var update = button(download == null ? "Обновить" : "Отменить загрузку", x, height - 30, half,
                () -> { if (download == null) refresh(); else abort("Загрузка отменена."); });
        update.active = listing == 0;
        button("Назад", x + half + 6, height - 30, w - half - 6, this::onClose);
    }

    private Button button(String text, int x, int y, int w, Runnable action) {
        return addRenderableWidget(new LoggerGuiWidgets.ScaledTextButton(x, y, w, 20, Component.literal(text),
                b -> action.run(), () -> LogViewerScreen.sharedControlScale(width, height)));
    }

    private void refresh() {
        if (!available() || minecraft.getConnection() != connection) {
            status = "Выгрузка недоступна.";
        } else {
            status = "Запрашиваю список файлов…";
            listing = ++sequence;
            request(listing, Action.LIST, "", 0);
        }
        rebuild();
    }

    private void start(FileInfo file) {
        if (listing != 0 || download != null || !available() || minecraft.getConnection() != connection) return;
        Path dir = minecraft.gameDirectory.toPath().resolve("avilixlogger-server-logs");
        download = new Download(++sequence, file.name(), dir);
        status = "Начинаю выгрузку " + file.name() + "…";
        request(download.id, Action.START, file.name(), 0);
        rebuild();
    }

    private void request(long id, Action action, String name, long offset) {
        deadline = System.nanoTime() + WAIT_NANOS;
        PacketDistributor.sendToServer(new Query(id, action, name, offset));
    }

    void accept(View view) {
        if (listing != 0 && view.request() == listing) {
            listing = 0;
            if (view.result() == ServerLogPayloads.Result.LIST) {
                files = view.files().stream().filter(file -> ServerLogFiles.allowedName(file.name()) && file.size() >= 0).toList();
                status = files.isEmpty() ? "На сервере пока нет доступных логов." : "Выберите файл для скачивания.";
            } else status = view.message();
            scroll = 0;
            rebuild();
            return;
        }
        var active = download;
        if (active == null || view.request() != active.id) return;
        if (view.result() == ServerLogPayloads.Result.ERROR) {
            abort(view.message());
            return;
        }
        int size = view.data().length;
        if (view.result() != ServerLogPayloads.Result.DATA || active.writing || !active.name.equals(view.name())
                || view.offset() != active.offset || view.total() < active.offset || view.total() > ServerLogPayloads.MAX_FILE_BYTES
                || active.total >= 0 && view.total() != active.total
                || size != Math.min(ServerLogPayloads.CHUNK_BYTES, view.total() - view.offset())) {
            abort("Сервер передал некорректную часть файла.");
            return;
        }
        active.total = view.total();
        active.writing = true;
        deadline = System.nanoTime() + WAIT_NANOS;
        var client = minecraft;
        Util.ioPool().execute(() -> {
            Path saved = null;
            boolean success = false;
            try {
                saved = active.write(view.data(), view.offset() + size == view.total());
                success = true;
            } catch (IOException | RuntimeException ignored) {}
            Path destination = saved;
            boolean written = success;
            client.execute(() -> {
                if (download != active) return;
                if (!written) {
                    abort("Не удалось сохранить файл на компьютере.");
                    return;
                }
                active.offset += size;
                active.writing = false;
                if (destination != null) {
                    download = null;
                    status = "Сохранено · " + destination.getFileName();
                    rebuild();
                } else if (client.getConnection() == connection && available()) {
                    status = "Скачано " + bytes(active.offset) + " из " + bytes(active.total);
                    request(active.id, Action.NEXT, "", active.offset);
                } else abort("Соединение с сервером закрыто.");
            });
        });
    }

    private static final class Download {
        final long id;
        final String name;
        final Path directory;
        long offset;
        long total = -1;
        boolean writing;
        volatile boolean cancelled;
        private Path partial;

        Download(long id, String name, Path directory) {
            this.id = id;
            this.name = name;
            this.directory = directory;
        }

        synchronized Path write(byte[] data, boolean complete) throws IOException {
            if (cancelled) return null;
            if (partial == null) {
                Files.createDirectories(directory);
                partial = Files.createTempFile(directory, "download-", ".part");
            }
            Files.write(partial, data, StandardOpenOption.APPEND);
            if (!complete || cancelled) return null;
            Path target = directory.resolve(name);
            try {
                Files.move(partial, target);
            } catch (FileAlreadyExistsException alreadySaved) {
                target = directory.resolve(UUID.randomUUID() + "-" + name);
                Files.move(partial, target);
            }
            partial = null;
            return target;
        }

        synchronized void clean() {
            if (partial != null) {
                try { Files.deleteIfExists(partial); } catch (IOException ignored) {}
                partial = null;
            }
        }
    }

    private void abort(String message) {
        stopTransfer();
        status = message;
        rebuild();
    }

    private void stopTransfer() {
        var active = download;
        long id = active == null ? listing : active.id;
        download = null;
        listing = 0;
        if (id != 0 && connection != null && minecraft.getConnection() == connection && connection.hasChannel(Query.TYPE))
            PacketDistributor.sendToServer(new Query(id, Action.CANCEL, "", 0));
        if (active != null) {
            active.cancelled = true;
            Util.ioPool().execute(active::clean);
        }
    }

    void disconnected() {
        abort("Соединение с сервером закрыто.");
    }

    @Override public void tick() {
        if ((listing != 0 || download != null) && System.nanoTime() >= deadline) abort("Время ожидания ответа истекло.");
    }

    private static String bytes(long value) {
        if (value < 1024) return value + " Б";
        if (value < 1024 * 1024) return value / 1024 + " КиБ";
        return String.format(java.util.Locale.ROOT, "%.1f МиБ", value / (1024.0 * 1024.0));
    }

    @Override public void render(GuiGraphics g, int x, int y, float dt) {
        LoggerGuiWidgets.beginFrame(g, width, height, false);
        g.drawCenteredString(font, title, width / 2, 13, 0xFFD700);
        g.drawCenteredString(font, "Папка игры / avilixlogger-server-logs", width / 2, 27, 0xD9E0EA);
        g.drawCenteredString(font, font.plainSubstrByWidth(status, width - 20), width / 2, height - 47, 0xE7EDF5);
        super.render(g, x, y, dt);
    }

    @Override public boolean mouseScrolled(double x, double y, double dx, double dy) {
        scroll -= 3 * (int) Math.signum(dy);
        rebuild();
        return true;
    }

    @Override public void removed() { stopTransfer(); }
    @Override public void renderBackground(GuiGraphics g, int x, int y, float dt) {}
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
