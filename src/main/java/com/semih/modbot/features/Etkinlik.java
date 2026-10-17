package com.semih.modbot.features;

import com.semih.modbot.command.Category;
import com.semih.modbot.command.Command;
import com.semih.modbot.command.Ctx;
import com.semih.modbot.core.Embeds;
import com.semih.modbot.core.Store;
import com.semih.modbot.core.Sure;
import com.semih.modbot.core.Zaman;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.utils.TimeFormat;
import net.dv8tion.jda.api.utils.messages.MessagePollBuilder;
import net.dv8tion.jda.api.utils.messages.MessagePollData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Etkinlik kategorisi: çekiliş, anket, etkinlik duyurusu.
 * Çekiliş ve etkinlikler kaydedilir: bilgisayar kapanıp açılınca kaldıkları yerden devam eder;
 * kapalıyken süresi dolan çekilişler bot açılınca bitirilir.
 */
public final class Etkinlik extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(Etkinlik.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Store store;
    private JDA jda;

    public Etkinlik(Store store) {
        this.store = store;
    }

    public List<Command> commands() {
        return List.of(
                Command.of("cekilis", Category.ETKINLIK, "<süre> <kazanan sayısı> <ödül>",
                        "Butonla katılınan çekiliş başlatır. Örnek: `!cekilis 1d 2 Minecraft hesabı`", this::cekilis, "giveaway", "gstart"),
                Command.of("cekilis-bitir", Category.ETKINLIK, "<mesaj ID>", "Çekilişi hemen bitirir ve kazananları seçer", this::cekilisBitir, "gend"),
                Command.of("cekilis-yenile", Category.ETKINLIK, "<mesaj ID> [kazanan sayısı]", "Biten çekilişte yeni kazanan seçer", this::cekilisYenile, "reroll"),
                Command.of("anket", Category.ETKINLIK, "[süre] <soru> | <seçenek> | <seçenek> ...",
                        "Discord anketi açar. Seçenek yazmazsan Evet/Hayır. Örnek: `!anket 1d Hangi mod? | Fabric | Forge`", this::anket, "poll"),
                Command.of("etkinlik", Category.ETKINLIK, "<zaman> <başlık> [| açıklama]",
                        "Katılım butonlu etkinlik duyurusu; vakti gelince katılanlara hatırlatır. Zaman: `2h`, `20:00` ya da `18.10 20:00`",
                        this::etkinlik, "event"));
    }

    public void start(JDA jda, ScheduledExecutorService scheduler) {
        this.jda = jda;
        scheduler.scheduleWithFixedDelay(this::tick, 10, 15, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------
    // Çekiliş
    // ------------------------------------------------------------------

    private static String ck(String id, String f) {
        return "cekilis." + id + "." + f;
    }

    private void cekilis(Ctx ctx) {
        ctx.need(3);
        Duration d = Sure.parse(ctx.arg(0));
        if (d == null) throw new Ctx.UserError("Süreyi anlayamadım: `" + ctx.arg(0) + "`. Örnek: `30m`, `1d`, `1w`");
        if (d.compareTo(Duration.ofDays(60)) > 0) throw new Ctx.UserError("Çekiliş en fazla 60 gün sürebilir.");
        int winners;
        try {
            winners = Integer.parseInt(ctx.arg(1).replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            throw new Ctx.UserError("Kazanan sayısını sayıyla yaz. Kullanım: " + ctx.usage());
        }
        if (winners < 1 || winners > 20) throw new Ctx.UserError("Kazanan sayısı 1 ile 20 arasında olmalı.");
        String prize = Embeds.kisalt(ctx.rest(2), 200);
        ctx.botNeeds(Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS);
        long end = Instant.now().plus(d).toEpochMilli();

        Message msg = ctx.channel.sendMessageEmbeds(cekilisEmbed(prize, end, winners, 0, ctx.member.getId(), null))
                .setComponents(ActionRow.of(Button.success("cekilis:katil", "Katıl").withEmoji(Emoji.fromUnicode("🎉"))))
                .complete();
        String id = msg.getId();
        store.set(ck(id, "g"), ctx.guild.getId());
        store.set(ck(id, "k"), ctx.channel.getId());
        store.set(ck(id, "bitis"), end);
        store.set(ck(id, "sayi"), winners);
        store.set(ck(id, "odul"), prize);
        store.set(ck(id, "baslatan"), ctx.member.getId());
        ctx.message.delete().queue(null, x -> { });
    }

    private static MessageEmbed cekilisEmbed(String prize, long end, int winners, int count, String host, String result) {
        EmbedBuilder eb = new EmbedBuilder().setColor(result == null ? Embeds.ETKINLIK : Embeds.GRI)
                .setTitle(result == null ? "🎉 ÇEKİLİŞ 🎉" : "🎉 ÇEKİLİŞ BİTTİ")
                .setDescription("**" + prize + "**\n\n"
                        + (result == null
                        ? "Katılmak için aşağıdaki **🎉 Katıl** butonuna bas!\n\n⏰ **Bitiş:** " + TimeFormat.RELATIVE.format(end)
                          + " (" + TimeFormat.DATE_TIME_SHORT.format(end) + ")"
                        : "🏆 **Kazanan:** " + result)
                        + "\n👥 **Katılımcı:** " + count
                        + "\n🎁 **Kazanan sayısı:** " + winners
                        + "\n🙋 **Başlatan:** <@" + host + ">")
                .setFooter(result == null ? "Tekrar basarsan çekilişten çıkarsın" : "Bitti")
                .setTimestamp(Instant.ofEpochMilli(end));
        return eb.build();
    }

    private void cekilisBitir(Ctx ctx) {
        ctx.need(1);
        String id = idArg(ctx);
        if (store.get(ck(id, "g")).isEmpty()) throw new Ctx.UserError("Bu ID'de bir çekiliş yok. Çekiliş mesajına sağ tık → Mesaj ID'sini Kopyala.");
        if (!store.get(ck(id, "bitti")).isEmpty()) throw new Ctx.UserError("Bu çekiliş zaten bitmiş. Yeni kazanan için `" + ctx.prefix + "cekilis-yenile " + id + "`");
        endGiveaway(id);
        ctx.ok("Çekiliş bitirildi.");
    }

    private void cekilisYenile(Ctx ctx) {
        ctx.need(1);
        String id = idArg(ctx);
        if (store.get(ck(id, "g")).isEmpty()) throw new Ctx.UserError("Bu ID'de bir çekiliş yok.");
        if (store.get(ck(id, "bitti")).isEmpty()) throw new Ctx.UserError("Bu çekiliş henüz bitmedi. Önce `" + ctx.prefix + "cekilis-bitir " + id + "`");
        int count = (int) store.getLong(ck(id, "sayi"));
        if (ctx.arg(1) != null) {
            try {
                count = Math.max(1, Math.min(20, Integer.parseInt(ctx.arg(1))));
            } catch (NumberFormatException ignored) {
                // varsayılan
            }
        }
        List<String> picked = pick(store.list(ck(id, "katilim")), count);
        if (picked.isEmpty()) throw new Ctx.UserError("Bu çekilişe kimse katılmamış.");
        String mentions = picked.stream().map(u -> "<@" + u + ">").collect(Collectors.joining(", "));
        ctx.channel.sendMessage("🔄 Yeni kazanan: " + mentions + "! **" + store.get(ck(id, "odul")) + "** senin! 🎉").queue();
    }

    private static String idArg(Ctx ctx) {
        String raw = ctx.arg(0).replaceAll(".*/", "").replaceAll("[^0-9]", "");
        if (raw.isEmpty()) throw new Ctx.UserError("Mesaj ID'sini yaz (mesaja sağ tık → Mesaj ID'sini Kopyala).");
        return raw;
    }

    static List<String> pick(List<String> pool, int count) {
        List<String> copy = new ArrayList<>(pool);
        Collections.shuffle(copy, RANDOM);
        return copy.subList(0, Math.min(count, copy.size()));
    }

    private synchronized void endGiveaway(String id) {
        if (!store.get(ck(id, "bitti")).isEmpty()) return;
        store.set(ck(id, "bitti"), "1");
        GuildMessageChannel ch = channel(store.get(ck(id, "g")), store.get(ck(id, "k")));
        if (ch == null) return;
        List<String> pool = store.list(ck(id, "katilim"));
        int count = (int) store.getLong(ck(id, "sayi"));
        String prize = store.get(ck(id, "odul"));
        List<String> winners = pick(pool, count);
        String mentions = winners.stream().map(u -> "<@" + u + ">").collect(Collectors.joining(", "));
        String result = winners.isEmpty() ? "Kimse katılmadı 😢" : mentions;
        ch.editMessageEmbedsById(id, cekilisEmbed(prize, store.getLong(ck(id, "bitis")), count, pool.size(),
                        store.get(ck(id, "baslatan")), result))
                .setComponents(ActionRow.of(Button.secondary("cekilis:katil", "Bitti").withEmoji(Emoji.fromUnicode("🎉")).asDisabled()))
                .queue(null, e -> log.warn("Çekiliş mesajı güncellenemedi: {}", e.getMessage()));
        if (winners.isEmpty()) {
            ch.sendMessage("🎉 **" + prize + "** çekilişi bitti ama kimse katılmadı.").queue();
        } else {
            ch.sendMessage("🎉 Tebrikler " + mentions + "! **" + prize + "** kazandın!")
                    .setMessageReference(id).failOnInvalidReply(false).queue();
        }
    }

    // ------------------------------------------------------------------
    // Anket
    // ------------------------------------------------------------------

    private void anket(Ctx ctx) {
        ctx.need(1);
        int from = 0;
        Duration d = Sure.parse(ctx.arg(0));
        if (d != null) from = 1;
        else d = Duration.ofDays(1);
        if (d.compareTo(Duration.ofHours(1)) < 0) d = Duration.ofHours(1);
        if (d.compareTo(Duration.ofDays(32)) > 0) throw new Ctx.UserError("Anket en fazla 32 gün sürebilir.");
        String text = ctx.rest(from);
        if (text.isEmpty()) throw new Ctx.UserError("Soruyu yaz. Kullanım: " + ctx.usage());
        String[] parts = text.split("\\|");
        String question = parts[0].strip();
        List<String> options = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].isBlank()) options.add(parts[i].strip());
        }
        if (options.isEmpty()) options = List.of("Evet", "Hayır");
        if (options.size() == 1) throw new Ctx.UserError("En az 2 seçenek yaz ya da hiç yazma (Evet/Hayır olur).");
        if (options.size() > 10) throw new Ctx.UserError("En fazla 10 seçenek olabilir.");
        if (question.length() > 300) throw new Ctx.UserError("Soru en fazla 300 karakter olabilir.");
        for (String o : options) {
            if (o.length() > 55) throw new Ctx.UserError("Seçenekler en fazla 55 karakter olabilir: `" + o + "`");
        }
        ctx.botNeeds(Permission.MESSAGE_SEND, Permission.MESSAGE_SEND_POLLS);
        long hours = Math.max(1, Math.round(d.toMinutes() / 60.0));
        MessagePollBuilder poll = MessagePollData.builder(question).setDuration(Duration.ofHours(hours));
        for (String o : options) poll.addAnswer(o);
        ctx.channel.sendMessagePoll(poll.build()).complete();
        ctx.message.delete().queue(null, x -> { });
    }

    // ------------------------------------------------------------------
    // Etkinlik duyurusu
    // ------------------------------------------------------------------

    private static String ek(String id, String f) {
        return "etkinlik." + id + "." + f;
    }

    private void etkinlik(Ctx ctx) {
        ctx.need(2);
        ZoneId zone = ZoneId.systemDefault();
        Zaman.When when = Zaman.parse(ctx.arg(0), ctx.arg(1), zone, LocalDateTime.now(zone));
        if (when == null) throw new Ctx.UserError("Zamanı anlayamadım. Örnekler: `2h`, `20:00`, `18.10 20:00`");
        if (!when.time().isAfter(Instant.now())) throw new Ctx.UserError("Etkinlik zamanı gelecekte olmalı.");
        String text = ctx.rest(when.used());
        if (text.isEmpty()) throw new Ctx.UserError("Etkinliğin başlığını yaz. Kullanım: " + ctx.usage());
        String[] parts = text.split("\\|", 2);
        String title = Embeds.kisalt(parts[0].strip(), 200);
        String desc = parts.length > 1 ? Embeds.kisalt(parts[1].strip(), 1500) : "";
        long t = when.time().toEpochMilli();

        Message msg = ctx.channel.sendMessageEmbeds(etkinlikEmbed(title, desc, t, List.of(), List.of(), ctx.member.getId(), false))
                .setComponents(etkinlikButtons(false))
                .complete();
        String id = msg.getId();
        store.set(ek(id, "g"), ctx.guild.getId());
        store.set(ek(id, "k"), ctx.channel.getId());
        store.set(ek(id, "zaman"), t);
        store.set(ek(id, "baslik"), title);
        store.set(ek(id, "aciklama"), desc);
        store.set(ek(id, "baslatan"), ctx.member.getId());
        if (t - System.currentTimeMillis() < TimeUnit.MINUTES.toMillis(30)) {
            store.set(ek(id, "h15"), "1"); // çok yakın etkinliğe ayrıca "15 dk kaldı" hatırlatması atma
        }
        ctx.message.delete().queue(null, x -> { });
    }

    private static ActionRow etkinlikButtons(boolean disabled) {
        return ActionRow.of(
                Button.success("etkinlik:evet", "Katılıyorum").withEmoji(Emoji.fromUnicode("✅")).withDisabled(disabled),
                Button.secondary("etkinlik:hayir", "Katılamıyorum").withEmoji(Emoji.fromUnicode("❌")).withDisabled(disabled));
    }

    private static MessageEmbed etkinlikEmbed(String title, String desc, long time, List<String> yes, List<String> no,
                                              String host, boolean started) {
        EmbedBuilder eb = new EmbedBuilder().setColor(started ? Embeds.GRI : Embeds.ETKINLIK)
                .setTitle("📅 " + title)
                .setDescription((desc.isEmpty() ? "" : desc + "\n\n")
                        + "🕒 **Zaman:** " + TimeFormat.DATE_TIME_LONG.format(time) + " (" + TimeFormat.RELATIVE.format(time) + ")"
                        + (started ? "\n\n**Etkinlik başladı!**" : ""))
                .addField("✅ Katılanlar (" + yes.size() + ")", names(yes), true)
                .addField("❌ Katılamayanlar (" + no.size() + ")", names(no), true)
                .setFooter("Düzenleyen bir kişi • vakti gelince katılanlara hatırlatılır")
                .setTimestamp(Instant.ofEpochMilli(time));
        eb.addField("Düzenleyen", "<@" + host + ">", false);
        return eb.build();
    }

    private static String names(List<String> ids) {
        if (ids.isEmpty()) return "-";
        String s = ids.stream().limit(25).map(i -> "<@" + i + ">").collect(Collectors.joining("\n"));
        return ids.size() > 25 ? s + "\n… +" + (ids.size() - 25) : s;
    }

    private MessageEmbed etkinlikEmbedFor(String id, boolean started) {
        return etkinlikEmbed(store.get(ek(id, "baslik")), store.get(ek(id, "aciklama")), store.getLong(ek(id, "zaman")),
                store.list(ek(id, "evet")), store.list(ek(id, "hayir")), store.get(ek(id, "baslatan")), started);
    }

    // ------------------------------------------------------------------
    // Butonlar
    // ------------------------------------------------------------------

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String cid = event.getComponentId();
        String id = event.getMessageId();
        String user = event.getUser().getId();
        if (cid.equals("cekilis:katil")) {
            if (store.get(ck(id, "g")).isEmpty() || !store.get(ck(id, "bitti")).isEmpty()) {
                event.replyEmbeds(Embeds.hata("Bu çekiliş bitmiş.")).setEphemeral(true).queue();
                return;
            }
            boolean joined = store.addToList(ck(id, "katilim"), user);
            if (!joined) store.removeFromList(ck(id, "katilim"), user);
            int count = store.list(ck(id, "katilim")).size();
            event.editMessageEmbeds(cekilisEmbed(store.get(ck(id, "odul")), store.getLong(ck(id, "bitis")),
                    (int) store.getLong(ck(id, "sayi")), count, store.get(ck(id, "baslatan")), null)).queue();
            event.getHook().sendMessageEmbeds(joined ? Embeds.basari("Çekilişe katıldın! Bol şans 🍀")
                    : Embeds.renkli(Embeds.GRI, "Çekilişten çıktın.")).setEphemeral(true).queue();
        } else if (cid.equals("etkinlik:evet") || cid.equals("etkinlik:hayir")) {
            if (store.get(ek(id, "g")).isEmpty()) {
                event.replyEmbeds(Embeds.hata("Bu etkinlik artık yok.")).setEphemeral(true).queue();
                return;
            }
            boolean yes = cid.endsWith("evet");
            store.removeFromList(ek(id, yes ? "hayir" : "evet"), user);
            store.addToList(ek(id, yes ? "evet" : "hayir"), user);
            event.editMessageEmbeds(etkinlikEmbedFor(id, !store.get(ek(id, "basladi")).isEmpty())).queue();
            event.getHook().sendMessageEmbeds(yes ? Embeds.basari("Katılıyorsun! Vakti gelince seni etiketleyeceğim.")
                    : Embeds.renkli(Embeds.GRI, "Katılamayacağını kaydettim.")).setEphemeral(true).queue();
        }
    }

    // ------------------------------------------------------------------
    // Zamanlayıcı: biten çekilişler ve etkinlik hatırlatmaları
    // ------------------------------------------------------------------

    private void tick() {
        if (jda == null) return;
        long now = System.currentTimeMillis();
        try {
            for (String key : store.keys("cekilis.")) {
                if (!key.endsWith(".bitis")) continue;
                String id = key.substring("cekilis.".length(), key.length() - ".bitis".length());
                if (store.get(ck(id, "bitti")).isEmpty() && store.getLong(key) <= now) {
                    endGiveaway(id);
                }
                // 30 gün önce biten çekilişleri temizle
                if (!store.get(ck(id, "bitti")).isEmpty() && store.getLong(key) < now - TimeUnit.DAYS.toMillis(30)) {
                    store.removePrefix("cekilis." + id + ".");
                }
            }
            for (String key : store.keys("etkinlik.")) {
                if (!key.endsWith(".zaman")) continue;
                String id = key.substring("etkinlik.".length(), key.length() - ".zaman".length());
                long t = store.getLong(key);
                // 15 dakika kala hatırlatma (etkinlik eklendiğinde 30 dakikadan uzaksa)
                if (store.get(ek(id, "h15")).isEmpty() && t - now <= TimeUnit.MINUTES.toMillis(15) && t > now) {
                    store.set(ek(id, "h15"), "1");
                    remind(id, false);
                }
                if (store.get(ek(id, "basladi")).isEmpty() && t <= now) {
                    store.set(ek(id, "basladi"), "1");
                    store.set(ek(id, "h15"), "1");
                    remind(id, true);
                }
                if (t < now - TimeUnit.DAYS.toMillis(7)) {
                    store.removePrefix("etkinlik." + id + ".");
                }
            }
        } catch (Exception e) {
            log.warn("Zamanlayıcı hatası: {}", e.toString());
        }
    }

    private void remind(String id, boolean started) {
        GuildMessageChannel ch = channel(store.get(ek(id, "g")), store.get(ek(id, "k")));
        if (ch == null) return;
        long t = store.getLong(ek(id, "zaman"));
        boolean late = System.currentTimeMillis() - t > TimeUnit.MINUTES.toMillis(30);
        if (late) {
            // Bilgisayar kapalıyken vakti geçmiş: sadece mesajı güncelle, kimseyi rahatsız etme
            ch.editMessageEmbedsById(id, etkinlikEmbedFor(id, true)).setComponents(etkinlikButtons(true)).queue(null, x -> { });
            return;
        }
        List<String> yes = store.list(ek(id, "evet"));
        String mentions = yes.stream().map(u -> "<@" + u + ">").collect(Collectors.joining(" "));
        String title = store.get(ek(id, "baslik"));
        String text = started
                ? "🔔 **" + title + "** başlıyor!" + (mentions.isEmpty() ? "" : "\n" + mentions)
                : "⏰ **" + title + "** " + TimeFormat.RELATIVE.format(t) + " başlıyor!" + (mentions.isEmpty() ? "" : "\n" + mentions);
        ch.sendMessage(Embeds.kisalt(text, 2000)).setMessageReference(id).failOnInvalidReply(false).queue(null, x -> { });
        if (started) {
            ch.editMessageEmbedsById(id, etkinlikEmbedFor(id, true)).setComponents(etkinlikButtons(true)).queue(null, x -> { });
        }
    }

    private GuildMessageChannel channel(String guildId, String channelId) {
        if (guildId.isEmpty() || channelId.isEmpty()) return null;
        Guild g = jda.getGuildById(guildId);
        return g == null ? null : g.getChannelById(GuildMessageChannel.class, channelId);
    }
}
