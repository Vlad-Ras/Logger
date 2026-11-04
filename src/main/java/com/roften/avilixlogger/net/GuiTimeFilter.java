package com.roften.avilixlogger.net;

import java.time.*;
import java.time.format.*;
import java.util.Locale;

/** Client-side, strict parsing; the wire carries only absolute bounds, never date text. */
public final class GuiTimeFilter {
    public enum Mode { SINCE, UNTIL, MINUTE, RANGE }
    public static String form(Mode mode,String fromDate,String fromTime,String toDate,String toTime,ZonedDateTime now) {
        return switch(mode) {
            case RANGE -> rangeInput(fromDate,fromTime,toDate,toTime,now);
            case SINCE -> {
                if(fromDate.isBlank()&&fromTime.isBlank())throw new IllegalArgumentException("Укажите дату или время.");
                yield rangeInput(fromDate,fromTime,"","",now);
            }
            case UNTIL -> {
                if(toDate.isBlank()&&toTime.isBlank())throw new IllegalArgumentException("Укажите дату или время.");
                yield rangeInput("","",toDate,toTime,now);
            }
            case MINUTE -> {
                if(!fromTime.trim().matches("[0-9]{2}:[0-9]{2}"))throw new IllegalArgumentException("Для точной минуты укажите время ЧЧ:ММ.");
                String date=fromDate.isBlank()?now.toLocalDate().format(DATE):fromDate;
                yield rangeInput(date,fromTime,date,fromTime,now);
            }
        };
    }
    public record Selection(int minutes, long since, long until) {}
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.uuuu", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);
    private GuiTimeFilter() {}
    /** Validates the friendly form without requiring users to type the range separator. */
    public static String rangeInput(String fromDate,String fromTime,String toDate,String toTime,ZonedDateTime now) {
        fromDate=fromDate.trim();fromTime=fromTime.trim();toDate=toDate.trim();toTime=toTime.trim();
        if(fromDate.isEmpty()&&!fromTime.isEmpty())fromDate=now.toLocalDate().format(DATE);
        if(toDate.isEmpty()&&!toTime.isEmpty())toDate=fromDate.isEmpty()?now.toLocalDate().format(DATE):fromDate;
        if(fromDate.isEmpty()&&toDate.isEmpty())return "0m";
        // A blank upper time includes that entire date. A typed minute includes its whole minute.
        String start=fromDate+(fromTime.isEmpty()?"":" "+fromTime);
        String end=toDate+(toTime.isEmpty()?"":" "+(toTime.length()==5?toTime+":59.999":toTime.length()==8?toTime+".999":toTime));
        String result=start+(toDate.isEmpty()?"":".."+end);
        parse(result,now);return result;
    }
    public static Selection parse(String input, ZonedDateTime now) {
        String value = input == null ? "" : input.trim();
        if (value.isEmpty()) return new Selection(-1, -1, -1);
        try {
            if (value.matches("[0-9]+[mMhHdD]?")) {
                char last = Character.toLowerCase(value.charAt(value.length() - 1));
                boolean suffix = Character.isLetter(last);
                int amount = Integer.parseInt(suffix ? value.substring(0, value.length() - 1) : value);
                return new Selection(Math.multiplyExact(amount, last == 'd' ? 1440 : last == 'h' ? 60 : 1), -1, -1);
            }
            String[] range = value.split("\\.\\.", -1);
            if (range.length > 2 || range.length==1&&range[0].isBlank() || range.length == 2 && range[1].isBlank()) throw new IllegalArgumentException();
            long since = range[0].isBlank()?0:instant(range[0].trim(), now, false);
            long until = range.length == 2 ? instant(range[1].trim(), now, true) : -1;
            if (since < 0 || until >= 0 && until < since) throw new IllegalArgumentException();
            return new Selection(-1, since, until);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Время: 30m, 13.10.2026, 03:00 или 13.10.2026 03:00; диапазон разделяется ..", failure);
        }
    }
    private static long instant(String value, ZonedDateTime now, boolean upper) {
        String[] parts = value.replace('T', ' ').trim().split("\\s+");
        if (parts.length > 2) throw new IllegalArgumentException();
        LocalDate date;
        LocalTime time;
        boolean dateOnly = false;
        if (parts.length == 1 && parts[0].contains(":")) {
            date = now.toLocalDate(); time = LocalTime.parse(parts[0]);
        } else {
            String day = parts[0];
            date = day.contains("-") ? LocalDate.parse(day) : LocalDate.parse(day.matches("[0-9]{2}\\.[0-9]{2}") ? day + "." + now.getYear() : day, DATE);
            dateOnly = parts.length == 1;
            time = dateOnly ? LocalTime.MIDNIGHT : LocalTime.parse(parts[1]);
        }
        var local = date.atTime(time);
        var zoned = local.atZone(now.getZone());
        if (!zoned.toLocalDateTime().equals(local)) throw new IllegalArgumentException("Время отсутствует при смене часового пояса");
        return upper && dateOnly ? date.plusDays(1).atStartOfDay(now.getZone()).toInstant().toEpochMilli() - 1 : zoned.toInstant().toEpochMilli();
    }
}
