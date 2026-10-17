package com.semih.modbot.features;

import com.semih.modbot.command.Category;
import com.semih.modbot.command.Command;
import com.semih.modbot.command.Ctx;
import com.semih.modbot.core.Ayarlar;
import com.semih.modbot.core.Embeds;
import com.semih.modbot.core.Store;
import com.semih.modbot.youtube.YouTubeFeed;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.NewsChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * YouTube yeni video bildirimi. Bot kapalıyken yüklenen videolar (son 3 gün) bot açılınca duyurulur.
 */
public final class YouTube {

    private static final Logger log = LoggerFactory.getLogger(YouTube.class);
    private static final Duration MAX_AGE = Duration.ofDays(3);

    private static final String HESAP = "yt.hesap";
    private static final String KANAL_ID = "yt.id";
    private static final String DUYURU = "yt.kanal";
    private static final String ETIKET = "yt.etiket";
    private static final String GORULEN = "yt.gorulen";

    private final Ayarlar ayarlar;
    private final Store store;
    private final YouTubeFeed feed = new YouTubeFeed();
    private final int intervalMinutes;
    private JDA jda;

    public YouTube(Ayarlar ayarlar, int intervalMinutes) {
        this.ayarlar = ayarlar;
        this.store = ayarlar.store;
        this.intervalMinutes = Math.max(2, intervalMinutes);
    }

    public List<Command> commands() {
        return List.of(
                Command.of("youtube", Category.YOUTUBE, "<@kanal|link|kapat> [#duyuru-kanalı] [@here|@everyone|@rol|yok]",
                        "Yeni video/Short gelince duyurur. Örnek: `!youtube @kanaladi #videolar @everyone`", this::youtube, "yt"),
                Command.of("youtube-test", Category.YOUTUBE, "", "Ayarları ve son videonun duyurusunun nasıl görüneceğini gösterir", this::test, "yttest"));
    }

    public void start(JDA jda, ScheduledExecutorService scheduler) {
        this.jda = jda;
        scheduler.scheduleWithFixedDelay(this::checkAll, 20, intervalMinutes * 60L, TimeUnit.SECONDS);
    }

    private void youtube(Ctx ctx) {
        ctx.need(1);
        Guild g = ctx.guild;
        if (ctx.arg(0).equalsIgnoreCase("kapat")) {
            ayarlar.set(g, DUYURU, "");
            ctx.ok("YouTube duyurusu kapatıldı.");
            return;
        }
        ctx.need(2);
        String hesap = ctx.arg(0);
        GuildMessageChannel ch = ctx.textChannelArg(1);
        if (ch == null) throw new Ctx.UserError("Duyuru kanalını etiketle. Örnek: `" + ctx.prefix + "youtube @kanaladi #videolar`");
        String etiket = "";
        String e = ctx.arg(2);
        if (e != null) {
            String low = e.toLowerCase(Locale.ROOT);
            if (low.equals("@everyone") || low.equals("everyone")) etiket = "@everyone";
            else if (low.equals("@here") || low.equals("here")) etiket = "@here";
            else if (low.equals("yok") || low.equals("hic")) etiket = "";
            else {
                Role role = ctx.needRole(2);
                etiket = role.getId();
            }
        }
        var self = g.getSelfMember();
        if (!self.hasPermission(ch, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS)) {
            throw new Ctx.UserError(ch.getAsMention() + " kanalına yazamıyorum. Bana **Mesaj Gönder** ve **Bağlantı Yerleştir** izni ver.");
        }
        ctx.info(Embeds.BILGI, "⏳ YouTube kanalı aranıyor...");
        String channelId;
        try {
            channelId = feed.resolveChannelId(hesap);
        } catch (Exception ex) {
            throw new Ctx.UserError("YouTube kanalını bulamadım: " + ex.getMessage());
        }
        if (!channelId.equals(ayarlar.get(g, KANAL_ID))) {
            ayarlar.set(g, GORULEN, ""); // yeni kanal: önceki kayıtları sıfırla
        }
        ayarlar.set(g, HESAP, hesap);
        ayarlar.set(g, KANAL_ID, channelId);
        ayarlar.set(g, DUYURU, ch.getId());
        ayarlar.set(g, ETIKET, etiket);
        // Mevcut videoları kaydet ki eski videolar duyurulmasın
        try {
            List<String> ids = new ArrayList<>();
            for (YouTubeFeed.Video v : feed.fetch(channelId)) ids.add(v.id());
            ayarlar.set(g, GORULEN, String.join(",", ids));
        } catch (Exception ignored) {
            // ilk kontrolde kaydedilir
        }
        String warn = "";
        if (etiket.startsWith("@") && !self.hasPermission(ch, Permission.MESSAGE_MENTION_EVERYONE)) {
            warn = "\n⚠️ " + etiket + " çalışması için o kanalda bana **@everyone, @here ve Tüm Rollerden Bahset** izni ver.";
        }
        ctx.ok("**" + hesap + "** takip ediliyor. Yeni video ve Short'lar " + ch.getAsMention() + " kanalına duyurulacak"
                + (etiket.isEmpty() ? "." : " (" + etiketYazi(etiket) + ").") + " Her " + intervalMinutes + " dakikada kontrol edilir."
                + "\nBilgisayarın kapalıyken yüklenenler, bot açılınca duyurulur." + warn);
    }

    private void test(Ctx ctx) {
        Guild g = ctx.guild;
        String id = ayarlar.get(g, KANAL_ID);
        if (id.isEmpty()) throw new Ctx.UserError("Henüz ayarlanmadı. Örnek: `" + ctx.prefix + "youtube @kanaladi #videolar @everyone`");
        List<YouTubeFeed.Video> videos;
        try {
            videos = feed.fetch(id);
        } catch (Exception e) {
            throw new Ctx.UserError("YouTube'a ulaşılamadı: " + e.getMessage());
        }
        if (videos.isEmpty()) throw new Ctx.UserError("Kanalda hiç video yok.");
        YouTubeFeed.Video latest = videos.stream().max(Comparator.comparing(YouTubeFeed.Video::published)).orElseThrow();
        long duyuru = ayarlar.id(g, DUYURU);
        ctx.info(Embeds.BILGI, "📺 **Hesap:** " + ayarlar.get(g, HESAP)
                + "\n**Duyuru kanalı:** " + (duyuru == 0 ? "Kapalı" : "<#" + duyuru + ">")
                + "\n**Etiket:** " + etiketYazi(ayarlar.get(g, ETIKET)));
        ctx.channel.sendMessage("**Önizleme** (kimse etiketlenmedi):\n\n" + format(latest, feed.isShort(latest), etiketYazi(ayarlar.get(g, ETIKET))))
                .setAllowedMentions(List.of()).queue();
    }

    private static String etiketYazi(String e) {
        if (e.isEmpty()) return "kimse";
        return e.matches("\\d+") ? "<@&" + e + ">" : e;
    }

    private static String format(YouTubeFeed.Video v, boolean isShort, String mention) {
        String head = isShort ? "yeni bir Short paylaştı! ⚡" : "yeni bir video yükledi! 🎬";
        return ((mention.isEmpty() || mention.equals("kimse")) ? "" : mention + " ")
                + "**" + (v.author().isEmpty() ? "Kanal" : v.author()) + "** " + head + "\n**" + v.title() + "**\n" + v.url();
    }

    private void checkAll() {
        if (jda == null) return;
        for (Guild g : jda.getGuilds()) {
            try {
                check(g);
            } catch (Exception e) {
                log.warn("YouTube kontrolü başarısız ({}): {}", g.getName(), e.getMessage());
            }
        }
    }

    private void check(Guild g) throws Exception {
        String channelId = ayarlar.get(g, KANAL_ID);
        GuildMessageChannel ch = ayarlar.channel(g, DUYURU);
        if (channelId.isEmpty() || ch == null) return;
        List<YouTubeFeed.Video> videos = feed.fetch(channelId);
        String gKey = Ayarlar.key(g.getIdLong(), GORULEN);
        List<String> seen = store.list(gKey);
        if (seen.isEmpty()) {
            store.set(gKey, String.join(",", videos.stream().map(YouTubeFeed.Video::id).toList()));
            return;
        }
        List<YouTubeFeed.Video> fresh = new ArrayList<>(videos.stream().filter(v -> !seen.contains(v.id())).toList());
        fresh.sort(Comparator.comparing(YouTubeFeed.Video::published));
        Instant cutoff = Instant.now().minus(MAX_AGE);
        String raw = ayarlar.get(g, ETIKET);
        String mention = raw.matches("\\d+") ? "<@&" + raw + ">" : raw;
        for (YouTubeFeed.Video v : fresh) {
            seen.add(v.id());
            if (v.published().isBefore(cutoff)) continue;
            boolean isShort = feed.isShort(v);
            ch.sendMessage(format(v, isShort, mention)).queue(m -> {
                log.info("Yeni {} duyuruldu: {}", isShort ? "Short" : "video", v.title());
                if (ch instanceof NewsChannel) m.crosspost().queue(null, x -> { });
            }, e -> log.warn("Duyuru gönderilemedi: {}", e.getMessage()));
        }
        List<String> trimmed = seen.size() > 200 ? seen.subList(seen.size() - 200, seen.size()) : seen;
        store.set(gKey, String.join(",", trimmed));
    }
}
