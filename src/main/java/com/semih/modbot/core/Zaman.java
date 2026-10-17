package com.semih.modbot.core;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Etkinlik zamanı okuyucu: "2h", "20:00", "18.10 20:00", "18.10.2026 20:00". */
public final class Zaman {

    private static final Pattern DATE = Pattern.compile("(\\d{1,2})[./](\\d{1,2})(?:[./](\\d{4}))?");
    private static final Pattern TIME = Pattern.compile("(\\d{1,2})[:.](\\d{2})");

    private Zaman() {
    }

    /** Zamanı okur; kaç argüman kullandığını da döner. */
    public record When(Instant time, int used) {
    }

    public static When parse(String a0, String a1, ZoneId zone, LocalDateTime now) {
        if (a0 == null) return null;
        Duration d = Sure.parse(a0);
        if (d != null) return new When(now.atZone(zone).toInstant().plus(d), 1);
        Matcher dm = DATE.matcher(a0);
        Matcher tm1 = a1 == null ? null : TIME.matcher(a1);
        if (dm.matches() && tm1 != null && tm1.matches()) {
            Matcher tm = tm1;
            int year = dm.group(3) != null ? Integer.parseInt(dm.group(3)) : now.getYear();
            try {
                LocalDateTime dt = LocalDateTime.of(LocalDate.of(year, Integer.parseInt(dm.group(2)), Integer.parseInt(dm.group(1))),
                        LocalTime.of(Integer.parseInt(tm.group(1)), Integer.parseInt(tm.group(2))));
                if (dm.group(3) == null && dt.isBefore(now)) dt = dt.plusYears(1);
                return new When(dt.atZone(zone).toInstant(), 2);
            } catch (Exception e) {
                return null;
            }
        }
        Matcher tm = TIME.matcher(a0);
        if (tm.matches()) {
            try {
                LocalDateTime dt = LocalDateTime.of(now.toLocalDate(), LocalTime.of(Integer.parseInt(tm.group(1)), Integer.parseInt(tm.group(2))));
                if (!dt.isAfter(now)) dt = dt.plusDays(1);
                return new When(dt.atZone(zone).toInstant(), 1);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

}
