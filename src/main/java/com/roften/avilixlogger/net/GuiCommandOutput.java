package com.roften.avilixlogger.net;

import net.minecraft.commands.CommandSource;
import net.minecraft.network.chat.Component;
import java.util.Objects;
import java.util.function.Consumer;

/** Reply destination retained by copied command stacks and asynchronous callbacks. */
public final class GuiCommandOutput implements CommandSource {
    private final Consumer<Component> replies;
    public GuiCommandOutput(Consumer<Component> replies) { this.replies = Objects.requireNonNull(replies); }
    @Override public void sendSystemMessage(Component message) { replies.accept(message); }
    @Override public boolean acceptsSuccess() { return true; }
    @Override public boolean acceptsFailure() { return true; }
    @Override public boolean shouldInformAdmins() { return false; }
}
