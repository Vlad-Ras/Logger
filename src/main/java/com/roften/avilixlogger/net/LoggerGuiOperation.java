package com.roften.avilixlogger.net;

import java.util.*;

/** One catalog supplies GUI forms and /log help. Commands retain their own permission checks. */
public enum LoggerGuiOperation {
    HELP("Справка","Основное","log help","","","",false),
    LOOKUP("Поиск логов","Основное","log","Фильтры","","--t 2h --r 20 --p Steve --m all; --d 2026-10-19; --at 2026-10-19T03:00; --ad",false),
    INSPECT("Осмотреть блок","Основное","log i","Фильтры (необязательно)","","Осмотр блока под прицелом; пустые фильтры допустимы",false),
    LAST("Последний запрос","Основное","log last","Дополнительные фильтры","","Повторить последний запрос с новыми фильтрами",false),
    PAGE("Страница","Основное","log page","Номер / next / prev / first","","Листание чатового запроса с результатами в этом окне",false),
    ROLLBACK("Предпросмотр отката мира","Откаты","log rollback","Фильтры","","--t 2h --r 20 --m grief или --at 2026-10-19T03:00 --we. Это откат мира, а не возврат штуковины",false),
    ROLLBACK_CONFIRM("Подтвердить откат мира","Откаты","log rollback --confirm","","","Применить ранее подготовленный откат мира",true),
    ROLLBACK_CANCEL("Отменить откат мира","Откаты","log rollback --cancel","","","Отменить подготовленный откат мира",false),
    PRESET_LIST("Список пресетов","Пресеты","log preset list","","","Сохранённые наборы фильтров",false),
    PRESET_SAVE("Сохранить пресет","Пресеты","log preset save","Название","Фильтры","Например theft и --t 2h --r 20 --m theft",false),
    PRESET_RUN("Выполнить пресет","Пресеты","log preset run","Название","Дополнительные фильтры","Запустить сохранённый поиск",false),
    PRESET_DELETE("Удалить пресет","Пресеты","log preset delete","Название","","Удалить только этот сохранённый набор фильтров",true),
    BACKPACK_HELD("Рюкзак в руке","Рюкзаки","log backpack held","","","Найти рюкзак из основной руки",false),
    BACKPACK_FIND("Рюкзаки владельца","Рюкзаки","log backpack find","Владелец","","Точный ник первого владельца",false),
    BACKPACK_LOCATE("Где рюкзак","Рюкзаки","log backpack locate","ID","","Полный или короткий ID рюкзака",false),
    BACKPACK_HISTORY("История рюкзака","Рюкзаки","log backpack history","ID","До entry (необязательно)","Пагинация: укажите последний entry для следующей страницы",false),
    BACKPACK_RECOVER("Восстановить рюкзак","Рюкзаки","log backpack recover","ID","Получатель","Подготовить восстановление потерянного рюкзака; затем подтвердить",false),
    BACKPACK_RETURN("Вернуть рюкзак","Рюкзаки","log backpack return","ID","Получатель","Подготовить возврат существующего рюкзака; затем подтвердить",false),
    BACKPACK_CONFIRM("Подтвердить возврат рюкзака","Рюкзаки","log backpack confirm","","","Применить ранее подготовленную операцию",true),
    BACKPACK_CANCEL("Отменить возврат рюкзака","Рюкзаки","log backpack cancel","","","Отменить подготовку или предпросмотр",false),
    PLANE_FIND("Найти самолёт игрока","Самолёты","log plane find","Игрок","offline (необязательно)","Поиск самолёта, включая офлайн-данные при наличии прав",false),
    PLANE_OWNER("Владелец самолёта","Самолёты","log plane owner info","","","Информация о владельце самолёта под прицелом",false),
    PLANE_OWNER_SET("Назначить владельца самолёта","Самолёты","log plane owner set","Игрок","","Установить владельца выбранного самолёта",true),
    PLANE_OWNER_RESET("Сбросить владельца самолёта","Самолёты","log plane owner reset","","","Сбросить владельца выбранного самолёта",true),
    TOOL_SET("Предмет инспектора","Настройки","log tool","ID предмета","","Например minecraft:stick. Требует административного права",true),
    TOOL_RESET("Сбросить предмет инспектора","Настройки","log tool reset","","","Вернуть предмет инспектора по умолчанию",true);

    public final String label,group,command,first,second,hint;public final boolean confirmation;
    LoggerGuiOperation(String label,String group,String command,String first,String second,String hint,boolean confirmation){this.label=label;this.group=group;this.command=command;this.first=first;this.second=second;this.hint=hint;this.confirmation=confirmation;}
    public boolean searchForm(){return this==LOOKUP||this==INSPECT||this==LAST||this==ROLLBACK;}
    public String command(String a,String b){
        a=clean(a);b=clean(b);
        if(searchForm()&&!b.isBlank()){
            var form=com.google.gson.JsonParser.parseString(b).getAsJsonObject();
            a=(searchFlags(form)+" "+a).trim();b="";
        }
        if(first.isEmpty()&&!a.isEmpty()||second.isEmpty()&&!b.isEmpty())throw new IllegalArgumentException("Лишние параметры операции");
        if(!Set.of(LOOKUP,INSPECT,LAST,ROLLBACK).contains(this)&&!a.isEmpty()&&!a.matches("[A-Za-z0-9_:.@+\\-]+"))throw new IllegalArgumentException("Первое поле должно содержать одно имя, ID или номер");
        if(!Set.of(PRESET_SAVE,PRESET_RUN).contains(this)&&!b.isEmpty()&&!b.matches("[A-Za-z0-9_:.@+\\-]+"))throw new IllegalArgumentException("Второе поле должно содержать одно имя или номер");
        // The search forms cannot smuggle destructive flags into a preview button.
        if((this==LOOKUP||this==INSPECT||this==LAST||this==ROLLBACK)&&a.matches("(?s).*--(?:confirm|cancel)(?:\\s.*|$)"))throw new IllegalArgumentException("Для подтверждения или отмены используйте отдельную кнопку");
        return command+(a.isBlank()?"":" "+a)+(b.isBlank()?"":" "+b);
    }
    private static String searchFlags(com.google.gson.JsonObject form){
        var flags=new StringBuilder();String[] keys={"time","radius","player","dimension","mode","owner"};
        for(String key:keys){if(!form.has(key))continue;String value=clean(form.get(key).getAsString());if(value.isBlank())continue;
            if(key.equals("time")){
                var now=java.time.ZonedDateTime.now();var parsed=GuiTimeFilter.parse(value,now);
                if(parsed.until()>=0)throw new IllegalArgumentException("Для диапазона времени используйте фильтр основного журнала");
                if(parsed.minutes()>=0)flags.append(" --t ").append(parsed.minutes()).append('m');
                else flags.append(" --at ").append(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss").format(java.time.Instant.ofEpochMilli(parsed.since()).atZone(now.getZone())));
            }else{
                if(!value.matches("[A-Za-z0-9_:.+\\-]+"))throw new IllegalArgumentException("Неверное поле: "+key);
                if(key.equals("radius")){int radius=Integer.parseInt(value);if(radius<0||radius>30000)throw new IllegalArgumentException("Радиус: 0..30000");}
                if(key.equals("mode")&&!Set.of("all","grief","theft","combat","planes").contains(value))throw new IllegalArgumentException("Режим: all, grief, theft, combat, planes");
                flags.append(switch(key){case "radius"->" --r ";case "player"->" --p ";case "dimension"->" --dim ";case "mode"->" --m ";default->" --owner ";}).append(value);
            }
        }
        if(form.has("we")&&form.get("we").getAsBoolean())flags.append(" --we");
        if(form.has("block")&&form.get("block").getAsBoolean())flags.append(" --block");
        return flags.toString();
    }
    private static String clean(String value){if(value==null||value.length()>1024||value.chars().anyMatch(c->c<32))throw new IllegalArgumentException("Недопустимые параметры");return value.trim();}
    public static List<String> help(){var out=new ArrayList<String>();out.add("AvilixLogger 1.2.22 · /log gui → Все функции / помощь");out.add("В GUI: фильтры до LIMIT, дата/время, выделение строки, детали, JSON, координаты, телепорт и настройки масштаба.");out.add("/log cart → отдельная страница штуковин: ID, владелец, исполнитель, время, мир и действие.");out.add("Вернуть саму штуковину: состояние строки / последнее сохранённое → предмет нажавшему кнопку; способы: пропавшая, заменить в руке, забрать из мира. Сначала предпросмотр, затем подтверждение.");out.add("/log cart history <ID> [before] | held | find <игрок> | chat <ID> [before]");out.add("/log cart recover <ID> [latest|entry] | restore-held <ID> [entry] | restore-world <ID> [entry] | confirm-recovery <ID> <entry> | cancel-recovery");out.add("/log cart rollback <ID> <entry> | confirm [<ID> <entry>] | cancel — отдельный откат ДЕЙСТВИЙ в мире.");
        for(var op:values())if(op!=HELP)out.add("/"+op.command+(op.first.isEmpty()?"":" <"+op.first+">")+(op.second.isEmpty()?"":" <"+op.second+">")+" — "+op.label);
        out.add("Поиск: --t 30m/2h/1d; --d yyyy-MM-dd; --at yyyy-MM-ddTHH:mm; --r радиус; --p игрок; --owner владелец самолёта; --m grief/theft/combat/planes/all; --ty типы; --dim мир; --ad все миры; --world весь мир; --we выделение WorldEdit; --block блок под прицелом. /owner info|set|reset — псевдоним управления владельцем самолёта.");return List.copyOf(out);}
}
