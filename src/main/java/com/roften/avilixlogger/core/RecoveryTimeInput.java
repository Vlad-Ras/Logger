package com.roften.avilixlogger.core;

import com.roften.avilixlogger.net.GuiTimeFilter;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** Shared, explicit recovery time semantics; GUI sends unambiguous UTC instants. */
public final class RecoveryTimeInput {
    public enum Area { RADIUS, WORLDEDIT, BLOCK }
    public record Window(long from,long until,boolean replay) {
        public String flags(Area area,String radius,String actors) {
            String scope=switch(area) {
                case WORLDEDIT -> "--we";
                case BLOCK -> "--block";
                case RADIUS -> {
                    int value;
                    try {value=Integer.parseInt(radius.trim());} catch (RuntimeException failure) {throw new IllegalArgumentException("Радиус: целое число от 1 до 256.");}
                    if(value<1||value>256)throw new IllegalArgumentException("Радиус: целое число от 1 до 256.");
                    yield "--r "+value;
                }
            };
            String players=actors.trim();
            if(!players.isEmpty()&&!players.matches("[A-Za-z0-9_]{1,16}(,[A-Za-z0-9_]{1,16}){0,15}"))
                throw new IllegalArgumentException("Ники: до 16 игроков через запятую, без пробелов. Пусто — все источники.");
            return "--at "+Instant.ofEpochMilli(from)+(replay?" --until "+Instant.ofEpochMilli(until):"")
                    +" "+scope+(players.isEmpty()?"":" --p "+players);
        }
    }
    private RecoveryTimeInput() {}
    public static Window form(boolean replay,String fromDate,String fromTime,String toDate,String toTime,ZonedDateTime now) {
        var selection=GuiTimeFilter.parse(GuiTimeFilter.form(replay?GuiTimeFilter.Mode.RANGE:GuiTimeFilter.Mode.SINCE,
                fromDate,fromTime,toDate,toTime,now),now);
        long end=replay?selection.until():now.toInstant().toEpochMilli();
        if(selection.since()<=0||selection.since()>=end)throw new IllegalArgumentException("Начало должно быть раньше конца периода.");
        if(end>now.toInstant().toEpochMilli())throw new IllegalArgumentException("Конец периода находится в будущем; уточните секунды или выберите прошлую минуту.");
        return new Window(selection.since(),end,replay);
    }
    public static Long absolute(String value,ZoneId serverZone) {
        if(value==null||value.isBlank())return null;
        String text=value.trim().replace('_','T').replace('@','T');
        try {return OffsetDateTime.parse(text,DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli();}
        catch(DateTimeParseException ignored) {}
        try {
            if(!text.contains("T"))return LocalDate.parse(text).atStartOfDay(serverZone).toInstant().toEpochMilli();
            var local=LocalDateTime.parse(text,DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            var offsets=serverZone.getRules().getValidOffsets(local);
            if(offsets.size()!=1)return null; // A missing/ambiguous clock time requires an explicit offset.
            return local.toInstant(offsets.getFirst()).toEpochMilli();
        } catch(DateTimeParseException ignored) {return null;}
    }
}
