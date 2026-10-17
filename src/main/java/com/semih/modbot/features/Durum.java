package com.semih.modbot.features;

import com.semih.modbot.youtube.YouTubeFeed;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Activity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Botun durum yazısı: YouTube kanalının bilgileri sırayla döner.
 * Örn. "📺 Son video: ..." → "👥 1,2 B abone" → "💬 !yardim"
 */
public final class Durum {

    private static final Logger log = LoggerFactory.getLogger(Durum.class);

    private final String account;
    private final String prefix;
    private final int rotateSeconds;
    private final YouTubeFeed feed = new YouTubeFeed();

    private volatile List<String> lines = List.of();
    private volatile String channelId;
    private int index;
    private JDA jda;

    public Durum(String account, String prefix, int rotateSeconds) {
        this.account = account == null ? "" : account.trim();
        this.prefix = prefix;
        this.rotateSeconds = Math.max(15, rotateSeconds);
    }

    public void start(JDA jda, ScheduledExecutorService scheduler) {
        this.jda = jda;
        if (account.isEmpty()) {
            jda.getPresence().setActivity(Activity.customStatus("💬 " + prefix + "yardim"));
            return;
        }
        // YouTube bilgilerini 10 dakikada bir yenile, durumu her X saniyede bir değiştir
        scheduler.scheduleWithFixedDelay(this::refresh, 0, 10, TimeUnit.MINUTES);
        scheduler.scheduleWithFixedDelay(this::rotate, 5, rotateSeconds, TimeUnit.SECONDS);
    }

    private void refresh() {
        try {
            if (channelId == null) channelId = feed.resolveChannelId(account);
            List<String> next = new ArrayList<>();
            List<YouTubeFeed.Video> videos = feed.fetch(channelId);
            String name = videos.isEmpty() ? account : videos.get(0).author();
            YouTubeFeed.Video latest = videos.stream().max(Comparator.comparing(YouTubeFeed.Video::published)).orElse(null);
            if (latest != null) {
                next.add((feed.isShort(latest) ? "⚡ Son Short: " : "📺 Son video: ") + latest.title());
            }
            String subs = feed.subscribers(channelId);
            if (subs != null) next.add("👥 " + name + " • " + subs);
            next.add("🎬 youtube.com/" + (account.startsWith("@") ? account : "@" + name.replace(" ", "")));
            next.add("💬 " + prefix + "yardim");
            lines = next;
        } catch (Exception e) {
            log.warn("Durum için YouTube bilgisi alınamadı: {}", e.getMessage());
            if (lines.isEmpty()) lines = List.of("💬 " + prefix + "yardim");
        }
    }

    private void rotate() {
        List<String> l = lines;
        if (jda == null || l.isEmpty()) return;
        String text = l.get(index++ % l.size());
        if (text.length() > 128) text = text.substring(0, 127) + "…";
        jda.getPresence().setActivity(Activity.customStatus(text));
    }
}
