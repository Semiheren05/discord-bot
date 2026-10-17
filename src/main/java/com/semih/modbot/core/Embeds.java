package com.semih.modbot.core;

import java.time.Instant;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;

/** Bot genelinde aynı görünen mesaj kutuları. */
public final class Embeds {

    public static final int ANA = 0x5865F2;      // yardım menüsündeki mor-mavi
    public static final int BASARI = 0x2ECC71;
    public static final int HATA = 0xE74C3C;
    public static final int UYARI = 0xF1C40F;
    public static final int BILGI = 0x3498DB;
    public static final int GRI = 0x95A5A6;
    public static final int ETKINLIK = 0xE91E63;

    private Embeds() {
    }

    public static MessageEmbed basari(String text) {
        return new EmbedBuilder().setColor(BASARI).setDescription("✅ " + text).build();
    }

    public static MessageEmbed hata(String text) {
        return new EmbedBuilder().setColor(HATA).setDescription("❌ " + text).build();
    }

    public static MessageEmbed renkli(int color, String text) {
        return new EmbedBuilder().setColor(color).setDescription(text).build();
    }

    public static EmbedBuilder log(int color, String title) {
        return new EmbedBuilder().setColor(color).setTitle(title).setTimestamp(Instant.now());
    }

    public static String kisalt(String text, int max) {
        if (text == null || text.isEmpty()) return "*(boş)*";
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
