package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.client.gui.CartHistoryScreen;
import com.roften.avilixlogger.net.S2CCartHistoryPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.util.*;

/** Dev-only real client rendering. Synthetic rows; no auth bypass or server operations. */
@EventBusSubscriber(modid="avilixlogger",value=Dist.CLIENT)
public final class CartGuiClientSmoke {
    private static CartHistoryScreen root;
    private static int ticks,stage,readyTicks,waitingTicks;
    private static boolean finished;
    private static final String ID="51000000-0000-0000-0000-000000000001";
    private static final List<String> FILES=List.of("cart-gui-history.png","cart-gui-actions.png","cart-gui-confirm.png","cart-gui-results.png","logger-actions.png","logger-action-results.png","logger-guide.png","logger-main.png","logger-capture-types.png","logger-time-range.png","logger-modules.png","logger-opac.png","logger-performance-results.png","logger-capture-paused.png","logger-capture-handlers.png","logger-row-actions.png","logger-client-report.png","logger-sections.png");
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        if(!Boolean.getBoolean("avilixlogger.guiSmoke")||finished)return;
        var mc=Minecraft.getInstance();
        try{
            if(root==null&&waitingTicks==0)java.nio.file.Files.deleteIfExists(mc.gameDirectory.toPath().resolve("gui-smoke-result.txt"));
            if(root==null){
                if(mc.getOverlay()==null&&mc.screen instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen){
                    mc.options.onboardingAccessibilityFinished();mc.setScreen(new net.minecraft.client.gui.screens.TitleScreen());return;
                }
                if(++waitingTicks%200==0)System.out.println("GUI_SMOKE_WAIT: screen="+(mc.screen==null?"null":mc.screen.getClass().getName())+" overlay="+(mc.getOverlay()==null?"none":mc.getOverlay().getClass().getName()));
                if(!(mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen)||mc.getOverlay()!=null||++readyTicks<40)return;
                root=new CartHistoryScreen(mc.screen,"");mc.setScreen(root);
                var rows=new ArrayList<S2CCartHistoryPayload.Row>();for(int i=0;i<12;i++)rows.add(new S2CCartHistoryPayload.Row(ID,100-i,"18.10.2026 23:50:00","Штуковина: упаковка","MaxKuz","minecraft:overworld · -1775 38 -2726","Полный снимок: 125 блоков. Хранилища предметов и жидкости сохранены.",true,true));
                root.apply(new S2CCartHistoryPayload(S2CCartHistoryPayload.PAGE,0,"",88,true,"Все операции доступны через GUI","",rows));
                var selected=CartHistoryScreen.class.getDeclaredField("selected");selected.setAccessible(true);selected.setInt(root,0);
                root.apply(new S2CCartHistoryPayload(S2CCartHistoryPayload.DONE,0,"",0,false,"Штуковина выдана в свободный слот. entry=100. Сохранены блоки, компоненты предметов и жидкости.","",List.of()));
                var jsonRequest=CartHistoryScreen.class.getDeclaredField("jsonRequest");jsonRequest.setAccessible(true);
                mc.keyboardHandler.setClipboard("GUI smoke sentinel");jsonRequest.setLong(root,100);
                root.acceptDetails(new com.roften.avilixlogger.net.S2CLogDetailsPayload(100,com.roften.avilixlogger.net.C2SRequestDetailsPayload.Mode.JSON,List.of(net.minecraft.network.chat.Component.literal("{\"id\":99}"))));
                check(mc.keyboardHandler.getClipboard().equals("GUI smoke sentinel"),"neighbour JSON copied as selected entry");
                jsonRequest.setLong(root,100);root.acceptDetails(new com.roften.avilixlogger.net.S2CLogDetailsPayload(100,com.roften.avilixlogger.net.C2SRequestDetailsPayload.Mode.JSON,List.of(net.minecraft.network.chat.Component.literal("{\"id\":100}"))));
                check(mc.keyboardHandler.getClipboard().equals("{\"id\":100}"),"selected JSON not copied");
                layout(root,mc);ticks=0;return;
            }
            if(++ticks<15)return;ticks=0;
            if(stage<FILES.size()){
                Screenshot.grab(mc.gameDirectory,FILES.get(stage),mc.getMainRenderTarget(),message->System.out.println("CART_GUI_SCREENSHOT: "+message.getString()));
                stage++;
                if(stage==1)mc.setScreen(panel("Actions"));
                else if(stage==2)root.confirmation("Вернуть саму штуковину в предмет: "+ID+". entry=100; блоков: 125. Основа: снимок entry=80, применено изменений состояния: 12. Хранилищ предметов: 4, предметов: 128; баков: 2, жидкости: 3000 mB. Мир и действия штуковины не откатываются. Подтверждение действует две минуты.","log cart confirm-recovery "+ID+" 100");
                else if(stage==3)mc.setScreen(panel("Journal"));
                else if(stage==4){
                    var screen=new com.roften.avilixlogger.client.gui.LoggerActionsScreen(root);
                    var actions=screen.getClass().getDeclaredField("actions");actions.setAccessible(true);actions.set(screen,com.roften.avilixlogger.core.LoggerActionCatalog.ACTIONS);
                    var allActions=screen.getClass().getDeclaredField("allActions");allActions.setAccessible(true);allActions.set(screen,com.roften.avilixlogger.core.LoggerActionCatalog.ACTIONS);
                    var groups=screen.getClass().getDeclaredField("groups");groups.setAccessible(true);var categories=new ArrayList<String>();categories.add("Все");categories.addAll(com.roften.avilixlogger.core.LoggerActionCatalog.ACTIONS.stream().map(com.roften.avilixlogger.core.LoggerActionCatalog.Action::group).distinct().toList());groups.set(screen,List.copyOf(categories));
                    mc.setScreen(screen);
                    for (int i = 0; i < categories.indexOf("Клиенты"); i++) {
                        var category = screen.children().stream()
                                .filter(w -> w instanceof net.minecraft.client.gui.components.Button button
                                        && button.getX() == 215 && button.getY() == 30)
                                .map(w -> (net.minecraft.client.gui.components.Button) w).findFirst().orElseThrow();
                        category.onPress();
                    }
                    check(screen.children().stream().anyMatch(w -> w instanceof net.minecraft.client.gui.components.Button button
                            && button.getMessage().getString().equals("Клиенты")), "client report category did not open");
                    check(screen.children().stream().anyMatch(net.minecraft.client.gui.components.EditBox.class::isInstance),
                            "client report form has no player field");
                }
                else if(stage==5){
                    var screen=(com.roften.avilixlogger.client.gui.LoggerActionsScreen)mc.screen;
                    var request=screen.getClass().getDeclaredField("lastRequest");request.setAccessible(true);request.setLong(screen,1);
                    com.roften.avilixlogger.client.gui.LoggerActionsScreen.accept(new com.roften.avilixlogger.net.S2CLoggerActionResultPayload(1,"Подтвердить откат выбранной конструкции","SUGGEST_COMMAND","/log cart confirm "+ID+" 100"));
                    var results=screen.getClass().getDeclaredField("results");results.setAccessible(true);results.setBoolean(screen,true);
                    var refresh=screen.getClass().getDeclaredMethod("refresh");refresh.setAccessible(true);refresh.invoke(screen);
                }
                else if(stage==6){
                    var screen=(com.roften.avilixlogger.client.gui.LoggerActionsScreen)mc.screen;
                    check(screen.mouseClicked(15,65,0),"result link did not open a GUI form");
                    var values=screen.getClass().getDeclaredField("values");values.setAccessible(true);check(values.get(screen).equals(List.of(ID,"100")),"confirmation link lost ID/entry");
                    var guide=Class.forName("com.roften.avilixlogger.client.gui.LoggerGuideScreen").getDeclaredConstructor(Screen.class);
                    guide.setAccessible(true);
                    var help=(Screen)guide.newInstance(root);
                    mc.setScreen(help);
                    var rollback=help.children().stream()
                            .filter(w -> w instanceof net.minecraft.client.gui.components.Button button
                                    && button.getMessage().getString().equals("Откат мира: время и область"))
                            .map(w -> (net.minecraft.client.gui.components.Button)w).findFirst().orElseThrow();
                    check(help.mouseClicked(rollback.getX()+2,rollback.getY()+2,0),"help topic button did not open");
                    check(help.children().stream().anyMatch(w -> w instanceof net.minecraft.client.gui.components.Button button
                            && button.getMessage().getString().equals("К разделам справки")),"help article did not expose its menu");
                    help.onClose();
                }
                else if(stage==7){
                    var viewer = new com.roften.avilixlogger.client.gui.LogViewerScreen();
                    mc.setScreen(viewer);
                    teleportReplies(mc, viewer);
                    var tp = viewer.children().stream()
                            .filter(w -> w instanceof net.minecraft.client.gui.components.Button b
                                    && b.getMessage().getString().equals("Телепорт"))
                            .map(net.minecraft.client.gui.components.Button.class::cast).findFirst().orElseThrow();
                    check(!tp.active, "teleport enabled with no entry selected");
                    check(viewer.children().stream().noneMatch(w -> w instanceof AbstractWidget widget
                            && widget.visible && widget.getMessage().getString().equals("Вернуть")), "undo shown before hiding");
                    clickLabel(viewer, "Разделы");
                    var sections = mc.screen;
                    check(sections.getTitle().getString().equals("Разделы"), "sections menu did not open");
                    for (String label : List.of("Журналы модов", "Вагонеточные штуковины", "Действия",
                            "Запись событий", "Настройки GUI", "Справка")) {
                        check(sections.children().stream().anyMatch(w -> w instanceof AbstractWidget widget
                                && widget.visible && widget.getMessage().getString().equals(label)), "missing section: " + label);
                    }
                    clickLabel(sections, "Журналы модов");
                    check(mc.screen.getTitle().getString().equals("Журналы модов"), "mod picker did not open");
                    var mods = mc.screen;
                    var firstMod = mods.children().stream().filter(net.minecraft.client.gui.components.Button.class::isInstance)
                            .map(net.minecraft.client.gui.components.Button.class::cast).findFirst().orElseThrow();
                    firstMod.onPress();
                    check(mc.screen != mods && mc.screen != viewer, "mod journal did not open");
                    clickLabel(mc.screen, "Общий лог");
                    check(mc.screen == viewer, "direct return lost the main journal");
                    mc.setScreen(mods);
                    check(mc.screen.mouseClicked(0, 0, 3) && mc.screen == sections, "mouse Back skipped sections");
                    clickLabel(sections, "Справка");
                    mc.screen.onClose();
                    check(mc.screen == sections, "guide Back skipped sections");
                    sections.onClose();
                    check(mc.screen == viewer, "sections Back lost the journal");

                    var timeField = viewer.getClass().getDeclaredField("btnTime");
                    timeField.setAccessible(true);
                    var timeButton = (net.minecraft.client.gui.components.Button) timeField.get(viewer);
                    String previousTime = timeButton.getMessage().getString();
                    check(viewer.mouseClicked(timeButton.getX() + 2, timeButton.getY() + 2, 0)
                            && mc.screen == viewer && !timeButton.getMessage().getString().equals(previousTime), "left click did not cycle time");
                    check(viewer.mouseClicked(timeButton.getX() + 2, timeButton.getY() + 2, 1)
                            && mc.screen != viewer, "right click did not open date form");
                    mc.screen.onClose();
                    check(mc.screen == viewer, "date form lost parent");

                    var line = net.minecraft.network.chat.Component.literal("[01.11 23:25:42] Create поставил компостницу");
                    var row = com.roften.avilixlogger.net.LogRow.single(42, "minecraft:overworld", 3173, 72, 3485, line);
                    viewer.apply(new com.roften.avilixlogger.net.S2CLogPagePayload("Логи · 01.11.2026", 1, false, false, List.of(row)));
                    var selected = viewer.getClass().getDeclaredField("selected");
                    selected.setAccessible(true);
                    selected.setInt(viewer, 0);
                    var entryId = viewer.getClass().getDeclaredField("selectedEntryId");
                    entryId.setAccessible(true);
                    entryId.setLong(viewer, row.id());
                    viewer.applyDetails(new com.roften.avilixlogger.net.S2CLogDetailsPayload(row.id(),
                            com.roften.avilixlogger.net.C2SRequestDetailsPayload.Mode.DETAILS,
                            List.of(net.minecraft.network.chat.Component.literal("Измерение: Верхний мир"),
                                    net.minecraft.network.chat.Component.literal("Координаты: 3173 72 3485"))));
                    layout(viewer, mc);
                    check(viewer.children().stream().anyMatch(w -> w instanceof net.minecraft.client.gui.components.Button b
                            && b.active && b.getMessage().getString().equals("Телепорт")), "selected entry lost quick teleport");
                    var copyCoordinates = viewer.children().stream()
                            .filter(w -> w instanceof net.minecraft.client.gui.components.Button b && b.active
                                    && (b.getMessage().getString().equals("Копировать XYZ") || b.getMessage().getString().equals("XYZ")))
                            .map(net.minecraft.client.gui.components.Button.class::cast).findFirst().orElseThrow();
                    copyCoordinates.onPress();
                    check(mc.keyboardHandler.getClipboard().equals("3173 72 3485"), "quick copy used wrong coordinates");
                    clickLabel(viewer, "…");
                    check(mc.screen.getTitle().getString().equals("Запись #42"), "entry menu lost selected row");
                    clickLabel(mc.screen, "Копировать всё");
                    check(mc.screen == viewer && mc.keyboardHandler.getClipboard().contains("3173 72 3485"),
                            "entry menu failed to copy details");
                }
                else if(stage==8)mc.setScreen(new com.roften.avilixlogger.client.gui.CaptureTypesScreen(mc.screen));
                else if(stage==9){
                    var parent=new com.roften.avilixlogger.client.gui.LogViewerScreen();
                    var constructor=Class.forName("com.roften.avilixlogger.client.gui.TimeRangeScreen").getDeclaredConstructor(parent.getClass(),java.time.ZoneId.class,String.class,java.util.function.Consumer.class);
                    constructor.setAccessible(true);mc.setScreen((Screen)constructor.newInstance(parent,java.time.ZoneId.of("Europe/Moscow"),"01.11.2026 10:30..02.11.2026 12:45",(java.util.function.Consumer<String>)value->{}));
                }
                else if(stage==10){
                    var parent=new com.roften.avilixlogger.client.gui.LogViewerScreen();
                    var constructor=Class.forName("com.roften.avilixlogger.client.gui.ModulePickerScreen").getDeclaredConstructor(parent.getClass(), List.class);constructor.setAccessible(true);
                    mc.setScreen((Screen)constructor.newInstance(parent, List.of()));
                }
                else if(stage==11)mc.setScreen(com.roften.avilixlogger.client.gui.LogViewerScreen.forModule(new com.roften.avilixlogger.client.gui.LogViewerScreen(),14));
                else if(stage==12){
                    var screen=new com.roften.avilixlogger.client.gui.LoggerActionsScreen(root);mc.setScreen(screen);
                    var request=screen.getClass().getDeclaredField("lastRequest");request.setAccessible(true);request.setLong(screen,2);
                    String example="Тест отображения отчёта. Числа ниже — данные fixture, не замер сервера.\n";
                    for(String name:List.of("Обработка снимков","События","Запись на диск"))example+=name+": в очереди 100, в обработке 25; пик записей 4096 / 4096; данные 0.50 MiB, пик 16.00 / 16.00 MiB; ожиданий 4, сейчас ждут 1, завершённый максимум 100.000 ms\n";
                    example+="Объём — оценка данных очереди, а не весь heap JVM. Один крупный снимок может превысить лимит буфера.";
                    com.roften.avilixlogger.client.gui.LoggerActionsScreen.accept(new com.roften.avilixlogger.net.S2CLoggerActionResultPayload(2,example,"",""));
                    var results=screen.getClass().getDeclaredField("results");results.setAccessible(true);results.setBoolean(screen,true);
                    var refresh=screen.getClass().getDeclaredMethod("refresh");refresh.setAccessible(true);refresh.invoke(screen);
                }
                else if(stage==13){
                    com.roften.avilixlogger.client.gui.LogViewerClientHooks.acceptCaptureHealth(new com.roften.avilixlogger.net.S2CCaptureHealthPayload(com.roften.avilixlogger.net.S2CCaptureHealthPayload.State.PAUSED,"Тест отображения остановки захвата"));
                    var viewer = new com.roften.avilixlogger.client.gui.LogViewerScreen();
                    mc.setScreen(viewer);
                    var line = net.minecraft.network.chat.Component.literal("[01.11 23:25:42] ")
                            .withStyle(net.minecraft.ChatFormatting.DARK_GRAY)
                            .append(net.minecraft.network.chat.Component.literal("Create поставил компостницу ")
                                    .withStyle(net.minecraft.ChatFormatting.WHITE))
                            .append(net.minecraft.network.chat.Component.literal("(3173 72 3485) · mod:create"));
                    viewer.apply(new com.roften.avilixlogger.net.S2CLogPagePayload("Логи · 01.11.2026", 1, false, false,
                            List.of(com.roften.avilixlogger.net.LogRow.single(42, "minecraft:overworld", 3173, 72, 3485, line))));
                    check(viewer.children().stream().anyMatch(widget -> widget instanceof net.minecraft.client.gui.components.Button button
                            && button.getMessage().getString().equals("Запись на паузе")),
                            "pause status missing after applying a journal page");
                }
                else if(stage==14){
                    var screen=new com.roften.avilixlogger.client.gui.CaptureTypesScreen(root);
                    var mode=screen.getClass().getDeclaredField("handlers");
                    mode.setAccessible(true);
                    mode.setBoolean(screen,true);
                    mc.setScreen(screen);
                }
                else if(stage==15) {
                    var viewer = new com.roften.avilixlogger.client.gui.LogViewerScreen();
                    var row = new com.roften.avilixlogger.net.LogRow(42, "minecraft:overworld", 1, 2, 3,
                            net.minecraft.network.chat.Component.literal("Create поставил компостницу"),
                            false, new long[]{42}, List.of(), com.roften.avilixlogger.core.ActionType.BLOCK_PLACE, "create", "");
                    var screenClass = Class.forName("com.roften.avilixlogger.client.gui.LogRowActionsScreen");
                    var constructor = screenClass.getDeclaredConstructor(viewer.getClass(), row.getClass(), Runnable.class, Runnable.class);
                    constructor.setAccessible(true);
                    var menu = (Screen) constructor.newInstance(viewer, row, (Runnable) () -> {}, (Runnable) () -> {});
                    mc.setScreen(menu);
                    check(menu.children().stream().anyMatch(widget -> widget instanceof net.minecraft.client.gui.components.Button button
                            && button.active && button.getMessage().getString().equals("Скрыть мод · create")),
                            "mod from the selected row was not exposed as a hide filter");
                    var systemRow = new com.roften.avilixlogger.net.LogRow(43, "minecraft:overworld", 1, 2, 3,
                            net.minecraft.network.chat.Component.literal("Система изменила состояние пшеницы"),
                            false, new long[]{43}, List.of(), com.roften.avilixlogger.core.ActionType.BLOCK_PLACE, "",
                            com.roften.avilixlogger.core.GuiEventVisibility.UNATTRIBUTED);
                    var systemMenu = (Screen) constructor.newInstance(viewer, systemRow, (Runnable) () -> {}, (Runnable) () -> {});
                    mc.setScreen(systemMenu);
                    check(systemMenu.children().stream().anyMatch(widget -> widget instanceof net.minecraft.client.gui.components.Button button
                            && button.active && button.getMessage().getString().startsWith("Скрыть источник · ")),
                            "a saved system origin did not expose a hide filter");
                }
                else if(stage==16) {
                    var type = Class.forName("com.roften.avilixlogger.client.gui.ClientReportScreen");
                    var constructor = type.getDeclaredConstructor(Screen.class);
                    constructor.setAccessible(true);
                    var screen = (Screen) constructor.newInstance(root);
                    mc.setScreen(screen);
                    var input = screen.children().stream().filter(net.minecraft.client.gui.components.EditBox.class::isInstance)
                            .map(net.minecraft.client.gui.components.EditBox.class::cast).findFirst().orElseThrow();
                    input.setValue("AuditTarget");
                    check(input.getValue().equals("AuditTarget"), "report nickname input missing");
                    var request = type.getDeclaredField("reportRequest");
                    request.setAccessible(true);
                    request.setLong(screen, 41L);
                    var pack = "file/Xray_Ultimate-v1.21.zip";
                    var otherPack = "file/DefaultTweaks.zip";
                    var modHash = "ab".repeat(32);
                    var packHash = "cd".repeat(32);
                    var report = com.roften.avilixlogger.net.ClientReportPayloads.collect(10,
                            List.of(new com.roften.avilixlogger.net.ClientReportPayloads.Mod("create", "6.0", "Create", modHash),
                                    new com.roften.avilixlogger.net.ClientReportPayloads.Mod("ae2", "1", "Applied Energistics 2")),
                            List.of(pack, otherPack), java.util.Map.of(pack, packHash));
                    var searchField = type.getDeclaredField("inventorySearch");
                    searchField.setAccessible(true);
                    var search = (net.minecraft.client.gui.components.EditBox) searchField.get(screen);
                    com.roften.avilixlogger.client.gui.LogViewerClientHooks.acceptClientReportView(
                            new com.roften.avilixlogger.net.ClientReportViewPayloads.View(41, "", "AuditTarget", 123, List.of(), List.of(pack), report));
                    clickLabel(screen, "Моды");
                    clickLabel(screen, "Копировать вкладку");
                    check(mc.keyboardHandler.getClipboard().contains("Create (create)"), "mods tab did not show the display name and ID");
                    search.setValue("ABABAB");
                    clickLabel(screen, "Копировать вкладку");
                    check(mc.keyboardHandler.getClipboard().contains("Create (create)")
                            && !mc.keyboardHandler.getClipboard().contains("Applied Energistics"), "mod SHA-256 filter did not isolate Create");
                    search.setValue("ENERGISTICS");
                    clickLabel(screen, "Копировать вкладку");
                    check(mc.keyboardHandler.getClipboard().contains("Applied Energistics")
                            && !mc.keyboardHandler.getClipboard().contains("Create (create)"), "mod name filter failed");
                    clickLabel(screen, "Ресурс-паки");
                    clickLabel(screen, "Копировать вкладку");
                    check(mc.keyboardHandler.getClipboard().contains("⚠ " + pack), "matched pack was not marked in its tab");
                    search.setValue("CDCDCD");
                    clickLabel(screen, "Копировать вкладку");
                    check(mc.keyboardHandler.getClipboard().contains(pack) && !mc.keyboardHandler.getClipboard().contains(otherPack),
                            "pack SHA-256 filter failed");
                    search.setValue("defaulttweaks");
                    clickLabel(screen, "Копировать вкладку");
                    check(mc.keyboardHandler.getClipboard().contains(otherPack) && !mc.keyboardHandler.getClipboard().contains(pack),
                            "pack name filter failed");
                    clickLabel(screen, "Моды");
                    check(search.getValue().equals("ENERGISTICS"), "switching tabs lost the mod search");
                    var commandsRequest = type.getDeclaredField("commandsRequest");
                    commandsRequest.setAccessible(true);
                    commandsRequest.setLong(screen, 42L);
                    com.roften.avilixlogger.client.gui.LogViewerClientHooks.acceptClientReportView(
                            new com.roften.avilixlogger.net.ClientReportViewPayloads.View(42, "", "AuditTarget", 0, List.of("/home"), List.of(), null));
                    clickLabel(screen, "Команды");
                    clickLabel(screen, "Копировать вкладку");
                    check(mc.keyboardHandler.getClipboard().contains("/home"), "commands tab did not show its journal rows");
                    clickLabel(screen, "Сводка");
                    clickLabel(screen, "Сохранить JSON");
                }
                else if (stage == 17) {
                    var viewer = new com.roften.avilixlogger.client.gui.LogViewerScreen();
                    mc.setScreen(viewer);
                    clickLabel(viewer, "Разделы");
                }
                if(stage<FILES.size())layout(mc.screen,mc);
                if(stage==14){
                    var screen=(com.roften.avilixlogger.client.gui.CaptureTypesScreen)mc.screen;
                    var handler=com.roften.avilixlogger.core.CaptureHandlers.at(70);
                    screen.apply(new com.roften.avilixlogger.net.S2CCaptureTypesPayload(0,9,true,true,
                            "Индивидуальные обработчики",List.of(handler.key())));
                    var search=screen.getClass().getDeclaredField("search");
                    search.setAccessible(true);
                    ((net.minecraft.client.gui.components.EditBox)search.get(screen)).setValue("industry.");
                    var group=screen.children().stream()
                            .filter(w -> w instanceof net.minecraft.client.gui.components.Button button
                                    && button.visible && button.getMessage().getString().startsWith("Промышленные машины  ·"))
                            .map(w -> (net.minecraft.client.gui.components.Button)w).findFirst().orElseThrow();
                    check(screen.mouseClicked(group.getX()+2,group.getY()+2,0),"handler group did not open");
                    check(screen.children().stream().anyMatch(w -> w instanceof net.minecraft.client.gui.components.Button button
                            && button.visible && button.getMessage().getString().contains("Промышленные машины ·")),
                            "group did not show its individual switches");
                }
                if(stage==8){
                    var capture=(com.roften.avilixlogger.client.gui.CaptureTypesScreen)mc.screen;
                    capture.apply(new com.roften.avilixlogger.net.S2CCaptureTypesPayload(1<<10,7,true,true,"Подшипники отключены без перезагрузки"));
                }
            }else{
                var reportDir = mc.gameDirectory.toPath().resolve("avilixlogger-reports");
                try (var exports = java.nio.file.Files.list(reportDir)) {
                    var files = exports.filter(file -> file.getFileName().toString().startsWith("AuditTarget-")).toList();
                    check(!files.isEmpty(), "report JSON export missing");
                    var file = files.stream().max(java.util.Comparator.comparingLong(path -> path.toFile().lastModified())).orElseThrow();
                    var reportJson = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(file)).getAsJsonObject();
                    check(reportJson.get("player").getAsString().equals("AuditTarget")
                            && reportJson.getAsJsonArray("mods").get(0).getAsJsonObject().get("displayName").getAsString().equals("Create")
                            && reportJson.getAsJsonArray("serverCommands").get(0).getAsString().equals("/home")
                            && reportJson.getAsJsonArray("matchedResourcePacks").size() == 1
                            && reportJson.getAsJsonObject("resourcePackHashes").get("file/Xray_Ultimate-v1.21.zip")
                                    .getAsString().equals("cd".repeat(32)), "report JSON missing sections");
                    java.nio.file.Files.delete(file);
                }
                for(var name:FILES)check(new java.io.File(new java.io.File(mc.gameDirectory,"screenshots"),name).isFile(),"missing screenshot "+name);
                String result="CART_GUI_CLIENT_OK: actual history/actions/confirmation/results, performance report, shared action forms, help and journal rendered; 320x240, 640x360, 960x540 widget bounds and overlap checks";
                java.nio.file.Files.writeString(mc.gameDirectory.toPath().resolve("gui-smoke-result.txt"),result+"\n");
                com.roften.avilixlogger.AvilixLoggerMod.LOGGER.info(result);finished=true;mc.stop();
            }
        }catch(Throwable error){
            com.roften.avilixlogger.AvilixLoggerMod.LOGGER.error("CART_GUI_CLIENT_FAILED",error);
            try{java.nio.file.Files.writeString(mc.gameDirectory.toPath().resolve("gui-smoke-result.txt"),"CART_GUI_CLIENT_FAILED: "+error+"\n");}catch(java.io.IOException ignored){}
            finished=true;mc.stop();
        }
    }
    private static void clickLabel(Screen screen, String label) {
        var button = screen.children().stream()
                .filter(w -> w instanceof net.minecraft.client.gui.components.Button b
                        && b.active && b.getMessage().getString().equals(label))
                .map(net.minecraft.client.gui.components.Button.class::cast).findFirst().orElseThrow();
        button.onPress();
    }

    private static void teleportReplies(Minecraft mc,com.roften.avilixlogger.client.gui.LogViewerScreen viewer)throws Exception {
        var hooks=com.roften.avilixlogger.client.gui.LogViewerClientHooks.class;
        var owner=hooks.getDeclaredField("teleportOwner");owner.setAccessible(true);
        var request=hooks.getDeclaredField("teleportRequest");request.setAccessible(true);
        owner.set(null,new java.lang.ref.WeakReference<>(viewer));request.setLong(null,71);
        hooks.getMethod("acceptEntryTeleport",com.roften.avilixlogger.net.S2CEntryTeleportPayload.class).invoke(null,new com.roften.avilixlogger.net.S2CEntryTeleportPayload(70,com.roften.avilixlogger.net.S2CEntryTeleportPayload.State.SUCCESS,"stale"));
        check(mc.screen==viewer,"stale teleport response closed journal");
        com.roften.avilixlogger.client.gui.LogViewerClientHooks.acceptEntryTeleport(new com.roften.avilixlogger.net.S2CEntryTeleportPayload(71,com.roften.avilixlogger.net.S2CEntryTeleportPayload.State.PREPARING,"Подготовка перехода"));
        check(mc.screen==viewer,"teleport progress opened an action screen");
        com.roften.avilixlogger.client.gui.LogViewerClientHooks.acceptEntryTeleport(new com.roften.avilixlogger.net.S2CEntryTeleportPayload(71,com.roften.avilixlogger.net.S2CEntryTeleportPayload.State.FAILURE,"Измерение записи недоступно"));
        check(mc.screen==viewer,"failed teleport closed journal");
        owner.set(null,new java.lang.ref.WeakReference<>(viewer));request.setLong(null,72);
        com.roften.avilixlogger.client.gui.LogViewerClientHooks.acceptEntryTeleport(new com.roften.avilixlogger.net.S2CEntryTeleportPayload(72,com.roften.avilixlogger.net.S2CEntryTeleportPayload.State.SUCCESS,"Запись: minecraft:the_nether · -1020 15 -820"));
        check(mc.screen!=viewer&&!(mc.screen instanceof com.roften.avilixlogger.client.gui.LoggerActionsScreen),"confirmed teleport retained journal/action overlay");
        mc.setScreen(viewer);
        System.out.println("ENTRY_TELEPORT_GUI_OK: inline progress/error, stale reply ignored, successful result closes only requesting journal");
    }
    private static Screen panel(String name)throws Exception{var c=Class.forName(CartHistoryScreen.class.getName()+"$"+name).getDeclaredConstructor(CartHistoryScreen.class);c.setAccessible(true);return (Screen)c.newInstance(root);}
    private static void layout(Screen screen,Minecraft mc){
        for(int[] size:new int[][]{{320,240},{640,360},{960,540}}){
            screen.resize(mc,size[0],size[1]);var widgets=screen.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast).filter(widget->widget.visible).toList();
            for(int i=0;i<widgets.size();i++){var a=widgets.get(i);check(a.getX()>=0&&a.getY()>=0&&a.getX()+a.getWidth()<=size[0]&&a.getY()+a.getHeight()<=size[1],"widget outside screen: "+a.getMessage().getString());
                for(int j=i+1;j<widgets.size();j++){var b=widgets.get(j);check(a.getX()+a.getWidth()<=b.getX()||b.getX()+b.getWidth()<=a.getX()||a.getY()+a.getHeight()<=b.getY()||b.getY()+b.getHeight()<=a.getY(),"widgets overlap: "+a.getMessage().getString()+" / "+b.getMessage().getString());}}
        }
        screen.resize(mc,mc.getWindow().getGuiScaledWidth(),mc.getWindow().getGuiScaledHeight());
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
