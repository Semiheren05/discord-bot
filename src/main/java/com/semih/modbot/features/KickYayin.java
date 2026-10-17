package com.semih.modbot.features;

import com.semih.modbot.command.Category;
import com.semih.modbot.command.Command;
import com.semih.modbot.command.Ctx;
import com.semih.modbot.core.Ayarlar;
import com.semih.modbot.core.Embeds;
import com.semih.modbot.kick.KickClient;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Kick "yayındayım" bildirimi. Yayın açılınca duyurur, bitince duyuru mesajını "yayın bitti" diye günceller.
 * Bot kapalıyken başlayan yayın hâlâ sürüyorsa bot açılınca duyurulur.
 */
public final class KickYayin {

    private static final Logger log = LoggerFactory.getLogger(KickYayin.class);
    private static final int KICK_YESIL = 0x53FC18;

    private static final String KANAL = "kick.kanal";
    private static final String DUYURU = "kick.duyuru";
    private static final String ETIKET = "kick.etiket";
    private static final String SON = "kick.son";       // duyurulan yayının başlangıç zamanı
    private static final String MESAJ = "kick.mesaj";   // duyuru mesajının ID'si
    private static final String CANLI = "kick.canli";

    private final Ayarlar ayarlar;
    private final KickClient client;
    private final int intervalSeconds;
    private JDA jda;

    public KickYayin(Ayarlar ayarlar, KickClient client, int intervalSeconds) {
        this.ayarlar = ayarlar;
        this.client = client;
        this.intervalSeconds = Math.max(60, intervalSeconds);
    }

    public List<Command> commands() {
        return List.of(
                Command.of("yayin", Category.YOUTUBE, "<kick kanal adı|kapat> [#duyuru-kanalı] [@here|@everyone|@rol|yok]",
                        "Kick'te yayın açınca duyurur. Örnek: `!yayin kanaladi #yayinlar @here`", this::yayin, "kickyayin", "canli"),
                Command.of("yayin-test", Category.YOUTUBE, "", "Kick kanalının şu anki durumunu ve duyurunun nasıl görüneceğini gösterir",
                        this::test, "kicktest"));
    }

    public void start(JDA jda, ScheduledExecutorService scheduler) {
        this.jda = jda;
        scheduler.scheduleWithFixedDelay(this::checkAll, 30, intervalSeconds, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------

    private void yayin(Ctx ctx) {
        ctx.need(1);
        Guild g = ctx.guild;
        if (ctx.arg(0).equalsIgnoreCase("kapat")) {
            ayarlar.set(g, DUYURU, "");
            ctx.ok("Kick yayın bildirimi kapatıldı.");
            return;
        }
        ctx.need(2);
        String slug = slug(ctx.arg(0));
        GuildMessageChannel ch = ctx.textChannelArg(1);
        if (ch == null) throw new Ctx.UserError("Duyuru kanalını etiketle. Örnek: `" + ctx.prefix + "yayin kanaladi #yayinlar @here`");
        String etiket = parseEtiket(ctx, 2);
        var self = g.getSelfMember();
        if (!self.hasPermission(ch, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS)) {
            throw new Ctx.UserError(ch.getAsMention() + " kanalına yazamıyorum. Bana **Mesaj Gönder** ve **Bağlantı Yerleştir** izni ver.");
        }
        KickClient.Status status;
        try {
            status = client.status(slug);
        } catch (Exception e) {
            throw new Ctx.UserError("Kick kanalına ulaşamadım: " + e.getMessage());
        }
        ayarlar.set(g, KANAL, slug);
        ayarlar.set(g, DUYURU, ch.getId());
        ayarlar.set(g, ETIKET, etiket);
        // Şu an yayındaysa onu "zaten duyurulmuş" say, sadece bundan sonraki yayınları duyur
        ayarlar.set(g, SON, status.live() ? status.startTime() : "");
        ayarlar.set(g, CANLI, status.live() ? "1" : "");
        String warn = "";
        if (etiket.startsWith("@") && !self.hasPermission(ch, Permission.MESSAGE_MENTION_EVERYONE)) {
            warn = "\n⚠️ " + etiket + " çalışması için o kanalda bana **@everyone, @here ve Tüm Rollerden Bahset** izni ver.";
        }
        ctx.ok("**kick.com/" + slug + "** takip ediliyor (" + (status.live() ? "🔴 şu an yayında" : "⚫ şu an kapalı") + "). "
                + "Yayın açınca " + ch.getAsMention() + " kanalına duyurulacak" + (etiket.isEmpty() ? "." : " (" + etiketYazi(etiket) + ").")
                + "\nHer " + (intervalSeconds / 60) + " dakikada bir kontrol edilir."
                + (client.hasOfficialApi() ? "" : "\nℹ️ Resmi Kick API'si ayarlı değil; Kick zaman zaman bu kontrolü engelleyebilir.")
                + warn);
    }

    private void test(Ctx ctx) {
        String slug = ayarlar.get(ctx.guild, KANAL);
        if (slug.isEmpty()) throw new Ctx.UserError("Henüz ayarlanmadı. Örnek: `" + ctx.prefix + "yayin kanaladi #yayinlar @here`");
        KickClient.Status s;
        try {
            s = client.status(slug);
        } catch (Exception e) {
            throw new Ctx.UserError("Kick'e ulaşamadım: " + e.getMessage());
        }
        long duyuru = ayarlar.id(ctx.guild, DUYURU);
        ctx.info(Embeds.BILGI, "🟩 **Kanal:** kick.com/" + slug
                + "\n**Durum:** " + (s.live() ? "🔴 Yayında (" + s.viewers() + " izleyici)" : "⚫ Kapalı")
                + "\n**Duyuru kanalı:** " + (duyuru == 0 ? "Kapalı" : "<#" + duyuru + ">")
                + "\n**Etiket:** " + etiketYazi(ayarlar.get(ctx.guild, ETIKET))
                + "\n**Yöntem:** " + (client.hasOfficialApi() ? "Resmi Kick API ✅" : "Yedek yöntem (engellenebilir)"));
        ctx.channel.sendMessage("**Önizleme** (kimse etiketlenmedi):")
                .setEmbeds(embed(slug, s, true))
                .setComponents(ActionRow.of(Button.link("https://kick.com/" + slug, "Yayına Git")))
                .queue();
    }

    // ------------------------------------------------------------------

    private void checkAll() {
        if (jda == null) return;
        for (Guild g : jda.getGuilds()) {
            String slug = ayarlar.get(g, KANAL);
            GuildMessageChannel ch = ayarlar.channel(g, DUYURU);
            if (slug.isEmpty() || ch == null) continue;
            try {
                check(g, slug, ch);
            } catch (Exception e) {
                log.warn("Kick kontrolü başarısız ({}): {}", slug, e.getMessage());
            }
        }
    }

    private void check(Guild g, String slug, GuildMessageChannel ch) throws Exception {
        KickClient.Status s = client.status(slug);
        boolean wasLive = !ayarlar.get(g, CANLI).isEmpty();
        if (s.live()) {
            String start = s.startTime() == null ? "" : s.startTime();
            // Başlangıç zamanı varsa ona göre, yoksa "az önce kapalıydı" bilgisine göre karar ver (aynı yayın iki kez duyurulmasın)
            boolean newStream = start.isEmpty() ? !wasLive : !start.equals(ayarlar.get(g, SON));
            if (newStream) {
                ayarlar.set(g, SON, start.isEmpty() ? String.valueOf(System.currentTimeMillis()) : start);
                ayarlar.set(g, CANLI, "1");
                String raw = ayarlar.get(g, ETIKET);
                String mention = raw.matches("\\d+") ? "<@&" + raw + ">" : raw;
                String text = (mention.isEmpty() ? "" : mention + " ") + "🔴 **" + s.displayName() + "** Kick'te yayında! Gel takıl 🎮";
                Message m = ch.sendMessage(text)
                        .setEmbeds(embed(slug, s, true))
                        .setComponents(ActionRow.of(Button.link("https://kick.com/" + slug, "Yayına Git")))
                        .complete();
                ayarlar.set(g, MESAJ, m.getId());
                log.info("Kick yayını duyuruldu: {}", s.title());
            } else if (!wasLive) {
                ayarlar.set(g, CANLI, "1");
            }
        } else if (wasLive) {
            ayarlar.set(g, CANLI, "");
            String msgId = ayarlar.get(g, MESAJ);
            if (!msgId.isEmpty()) {
                ch.editMessageEmbedsById(msgId, embed(slug, s, false))
                        .setComponents(ActionRow.of(Button.link("https://kick.com/" + slug, "Kanala Git")))
                        .queue(null, x -> { });
            }
        }
    }

    private static MessageEmbed embed(String slug, KickClient.Status s, boolean live) {
        String url = "https://kick.com/" + slug;
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(live ? KICK_YESIL : Embeds.GRI)
                .setAuthor(s.displayName() + (live ? " • CANLI" : " • yayın bitti"), url)
                .setTitle(live ? (s.title() == null || s.title().isBlank() ? "Yayındayım!" : Embeds.kisalt(s.title(), 250)) : "Yayın sona erdi", url)
                .setTimestamp(Instant.now());
        if (live) {
            if (s.category() != null && !s.category().isBlank()) eb.addField("🎮 Kategori", s.category(), true);
            eb.addField("👀 İzleyici", String.valueOf(s.viewers()), true);
            if (s.thumbnail() != null && s.thumbnail().startsWith("http")) {
                eb.setImage(s.thumbnail() + (s.thumbnail().contains("?") ? "&" : "?") + "t=" + System.currentTimeMillis() / 60000);
            }
        } else {
            eb.setDescription("Kaçırdıysan bir sonraki yayında görüşürüz! 👋\n" + url);
        }
        return eb.build();
    }

    static String slug(String input) {
        String s = input.trim().toLowerCase(Locale.ROOT);
        s = s.replaceFirst("^(https?://)?(www\\.)?kick\\.com/", "").replaceFirst("^@", "");
        int cut = s.indexOf('/');
        if (cut >= 0) s = s.substring(0, cut);
        return s;
    }

    private static String parseEtiket(Ctx ctx, int i) {
        String e = ctx.arg(i);
        if (e == null) return "@here";
        String low = e.toLowerCase(Locale.ROOT);
        if (low.equals("@everyone") || low.equals("everyone")) return "@everyone";
        if (low.equals("@here") || low.equals("here")) return "@here";
        if (low.equals("yok") || low.equals("hic")) return "";
        Role role = ctx.needRole(i);
        return role.getId();
    }

    private static String etiketYazi(String e) {
        if (e.isEmpty()) return "kimse";
        return e.matches("\\d+") ? "<@&" + e + ">" : e;
    }
}
