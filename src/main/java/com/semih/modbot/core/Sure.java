package com.semih.modbot.core;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Süre okuyucu. Yardım menüsündeki gibi: 30s saniye • 10m dakika • 2h saat • 1d gün • 1w hafta • 1d12h birleşik.
 * Türkçe kısaltmalar da çalışır: 30sn, 10dk, 2sa, 1g, 1hf.
 */
public final class Sure {

    private static final Pattern PART = Pattern.compile(
            "(\\d+)(saniye|saat|sa|sn|s|dakika|dk|m|hafta|hf|h|gün|gun|g|d|w)"); // uzunlar önce

    private Sure() {
    }

    /** Geçersizse null. */
    public static Duration parse(String input) {
        if (input == null) return null;
        String text = input.toLowerCase(Locale.ROOT).replace(" ", "");
        if (text.isEmpty() || !Character.isDigit(text.charAt(0))) return null;
        Matcher m = PART.matcher(text);
        Duration total = Duration.ZERO;
        int pos = 0;
        while (pos < text.length()) {
            if (!m.find(pos) || m.start() != pos) return null;
            long amount;
            try {
                amount = Long.parseLong(m.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
            if (amount > 100_000) return null;
            Duration part = switch (m.group(2)) {
                case "saniye", "sn", "s" -> Duration.ofSeconds(amount);
                case "dakika", "dk", "m" -> Duration.ofMinutes(amount);
                case "saat", "sa", "h" -> Duration.ofHours(amount);
                case "gün", "gun", "g", "d" -> Duration.ofDays(amount);
                case "hafta", "hf", "w" -> Duration.ofDays(amount * 7);
                default -> null;
            };
            if (part == null) return null;
            total = total.plus(part);
            pos = m.end();
        }
        return total.isZero() ? null : total;
    }

    /** 90 dakika -> "1 saat 30 dakika" */
    public static String format(Duration d) {
        long days = d.toDays();
        long hours = d.toHoursPart();
        long minutes = d.toMinutesPart();
        long seconds = d.toSecondsPart();
        StringBuilder sb = new StringBuilder();
        if (days >= 7 && days % 7 == 0 && hours == 0 && minutes == 0) {
            return (days / 7) + " hafta";
        }
        if (days > 0) sb.append(days).append(" gün ");
        if (hours > 0) sb.append(hours).append(" saat ");
        if (minutes > 0) sb.append(minutes).append(" dakika ");
        if (seconds > 0 && days == 0) sb.append(seconds).append(" saniye ");
        String s = sb.toString().trim();
        return s.isEmpty() ? "0 saniye" : s;
    }
}
