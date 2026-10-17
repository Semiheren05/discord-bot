package com.semih.modbot.core;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Sunucuya özel ayarlar (komutlarla değiştirilir) ve log kanalına yazma. */
public final class Ayarlar {

    private static final Logger log = LoggerFactory.getLogger(Ayarlar.class);

    public static final String LOG = "log";
    public static final String HOSGELDIN_KANAL = "hosgeldin.kanal";
    public static final String HOSGELDIN_MESAJ = "hosgeldin.mesaj";
    public static final String OTOROL = "otorol";

    public static final String VARSAYILAN_HOSGELDIN =
            "Sunucumuza hoş geldin {kullanici}! Seninle birlikte **{uye_sayisi}** kişiyiz 🎉";

    public final Store store;

    public Ayarlar(Store store) {
        this.store = store;
    }

    public static String key(long guildId, String name) {
        return "g." + guildId + "." + name;
    }

    public String get(Guild g, String name) {
        return store.get(key(g.getIdLong(), name));
    }

    public long id(Guild g, String name) {
        return store.getLong(key(g.getIdLong(), name));
    }

    public void set(Guild g, String name, String value) {
        store.set(key(g.getIdLong(), name), value);
    }

    public GuildMessageChannel channel(Guild g, String name) {
        long id = id(g, name);
        return id == 0 ? null : g.getChannelById(GuildMessageChannel.class, id);
    }

    public boolean isLogChannel(Guild g, long channelId) {
        long id = id(g, LOG);
        return id != 0 && id == channelId;
    }

    /** Log kanalına yazar; ayarlı değilse hiçbir şey yapmaz. */
    public void log(Guild g, MessageEmbed embed) {
        GuildMessageChannel ch = channel(g, LOG);
        if (ch == null) return;
        ch.sendMessageEmbeds(embed).queue(null, e -> log.warn("Log kanalına yazılamadı: {}", e.getMessage()));
    }
}
