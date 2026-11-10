package com.roften.avilixlogger.smoke;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.roften.avilixlogger.net.GuiCommandOutput;
import com.roften.avilixlogger.net.S2CLoggerActionResultPayload;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.UUID;
import static com.roften.avilixlogger.smoke.ScgunsNativeSmoke.check;

final class GuiCommandOutputNativeSmoke {
    static void run(MinecraftServer server) throws Exception {
        var chat = new ArrayList<String>();
        var replies = new ArrayList<S2CLoggerActionResultPayload>();
        var player = new ServerPlayer(server, server.overworld(), new GameProfile(UUID.randomUUID(), "GuiOperator"), ClientInformation.createDefault()) {
            @Override public void sendSystemMessage(Component message) { chat.add(message.getString()); }
            @Override public void sendSystemMessage(Component message, boolean overlay) { chat.add(message.getString()); }
        };
        var denied=new ArrayList<com.roften.avilixlogger.command.EntryPositionCommand.Feedback>();
        check(com.roften.avilixlogger.command.EntryPositionCommand.request(player.createCommandSourceStack(),1880834017623801859L,denied::add)==0
                &&denied.size()==1&&denied.getFirst().state()==com.roften.avilixlogger.net.S2CEntryTeleportPayload.State.FAILURE&&chat.isEmpty(),"direct teleport bypassed AuthCore or sent feedback to chat");
        var normal = player.createCommandSourceStack();
        normal.sendSystemMessage(Component.literal("normal-chat-before"));
        var gui = normal.withSource(new GuiCommandOutput(message -> replies.add(S2CLoggerActionResultPayload.from(17, message))));
        check(gui instanceof GuiCommandOutput.Capture capture && capture.avilixlogger$capturesGuiOutput()
                && normal instanceof GuiCommandOutput.Capture standard && !standard.avilixlogger$capturesGuiOutput(),
                "GUI output destination did not survive command stack copying");
        check(gui.getPlayerOrException() == player && gui.getEntity() == player, "GUI lost real command actor");
        check(gui.hasPermission(2) == normal.hasPermission(2), "GUI changed permissions");
        var dispatcher = new CommandDispatcher<CommandSourceStack>();
        dispatcher.register(Commands.literal("reply-fixture").executes(context -> {
            context.getSource().sendSystemMessage(Component.literal("system"));
            context.getSource().sendSuccess(() -> Component.literal("success"), false);
            context.getSource().sendFailure(Component.literal("failure"));
            return 1;
        }));
        dispatcher.execute("reply-fixture", gui);
        var delayed = gui.withPosition(new Vec3(20, 80, 20));
        java.util.concurrent.CompletableFuture.runAsync(() -> delayed.sendSystemMessage(Component.literal("delayed"))).get();
        gui.withSuppressedOutput().sendSystemMessage(Component.literal("silent"));
        check(replies.stream().map(S2CLoggerActionResultPayload::message).toList().equals(java.util.List.of("system", "success", "failure", "delayed")), "missing/duplicate GUI replies: " + replies);
        check(replies.stream().allMatch(reply -> reply.request() == 17), "request correlation lost");
        check(chat.equals(java.util.List.of("normal-chat-before")), "GUI replies leaked into chat: " + chat);
        normal.sendSystemMessage(Component.literal("normal-chat-after"));
        check(chat.size() == 2, "ordinary chat was intercepted");
        System.out.println("GUI_COMMAND_OUTPUT_NATIVE_OK: real player + Brigadier, system/success/failure, delayed copied source, silent output and unchanged chat/permissions");
    }
}
