package com.roften.avilixlogger.net;

import java.time.*;
import java.time.format.*;
import java.util.Locale;

/** Client-side, strict parsing; the wire carries only absolute bounds, never date text. */
public final class GuiTimeFilter {
    public enum Mode { SINCE, UNTIL, MINUTE, RANGE }
    public static String form(Mode mode,String fromDate,String fromTime,String toDate,String toTime,ZonedDateTime now) {
        return switch(mode) {
            case RANGE -> {
                if ((fromDate.isBlank() && fromTime.isBlank()) || (toDate.isBlank() && toTime.isBlank()))
                    throw new IllegalArgumentException("Укажите начало и конец периода.");
                yield rangeInput(fromDate,fromTime,toDate,toTime,now);
            }
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
    public record Selection(int minutes, long since, long until) {
        public Mode mode() {
            if (since < 0) return null;
            if (until < 0) return Mode.SINCE;
            if (since == 0) return Mode.UNTIL;
            return until - since == 59_999 && since % 60_000 == 0 ? Mode.MINUTE : Mode.RANGE;
        }
    }
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
        String end=toDate+(toTime.isEmpty()?"":" "+toTime);
        String result=start+(toDate.isEmpty()?"":".."+end);
        parse(result,now);return result;
    }
    public static Selection parse(String input, ZonedDateTime now) {
        // Pasted dates can contain a non-breaking space between the date and time.
        String value = input == null ? "" : input.replace('\u00A0', ' ').replace('\u202F', ' ').strip();
        if (value.isEmpty()) return new Selection(-1, -1, -1);
        try {
            boolean fromPrefix = value.startsWith("от ") || value.startsWith("с ");
            if (fromPrefix) value = value.substring(value.indexOf(' ') + 1).strip();
            if (!fromPrefix && value.matches("[0-9]+[mMhHdD]?")) {
                char last = Character.toLowerCase(value.charAt(value.length() - 1));
                boolean suffix = Character.isLetter(last);
                int amount = Integer.parseInt(suffix ? value.substring(0, value.length() - 1) : value);
                return new Selection(Math.multiplyExact(amount, last == 'd' ? 1440 : last == 'h' ? 60 : 1), -1, -1);
            }
            String[] range = value.split("\\.\\.", -1);
            if (range.length > 2 || range.length==1&&range[0].isBlank() || range.length == 2 && range[1].isBlank()) throw new IllegalArgumentException();
            DateTime start = range[0].isBlank() ? null : parseDateTime(range[0].strip(), now.toLocalDate());
            LocalDate firstDate = start == null ? now.toLocalDate() : start.date();
            long since = start == null ? 0 : instant(start, now.getZone(), false);
            long until = range.length == 2 ? instant(parseDateTime(range[1].strip(), firstDate), now.getZone(), true) : -1;
            if (since < 0 || until >= 0 && until < since) throw new IllegalArgumentException();
            return new Selection(-1, since, until);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Время: 30m, 13.10.2026, 03:00 или 13.10.2026 03:00; диапазон разделяется ..", failure);
        }
    }
    private record DateTime(LocalDate date, String clock) {}

    private static DateTime parseDateTime(String value, LocalDate defaultDate) {
        String[] parts = value.replace('T', ' ').strip().split("\\s+");
        if (parts.length > 2) throw new IllegalArgumentException();
        if (parts.length == 1 && parts[0].contains(":")) return new DateTime(defaultDate, parts[0]);
        String day = parts[0];
        LocalDate date = day.contains("-") ? LocalDate.parse(day)
                : LocalDate.parse(day.matches("[0-9]{2}\\.[0-9]{2}") ? day + "." + defaultDate.getYear() : day, DATE);
        return new DateTime(date, parts.length == 1 ? "" : parts[1]);
    }

    private static long instant(DateTime value, ZoneId zone, boolean upper) {
        var date = value.date();
        String clock = value.clock();
        if (clock.isEmpty()) {
            return upper ? date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
                    : date.atStartOfDay(zone).toInstant().toEpochMilli();
        }
        var local = date.atTime(LocalTime.parse(clock));
        var zoned = local.atZone(zone);
        if (!zoned.toLocalDateTime().equals(local)) throw new IllegalArgumentException("Время отсутствует при смене часового пояса");
        long millis = zoned.toInstant().toEpochMilli();
        if (!upper) return millis;
        if (clock.length() == 5) return millis + 59_999;
        if (clock.length() == 8) return millis + 999;
        return millis;
    }
}
