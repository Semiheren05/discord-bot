package com.semih.modbot.features;

import com.semih.modbot.command.Category;
import com.semih.modbot.command.Command;
import com.semih.modbot.command.Ctx;
import com.semih.modbot.core.Ayarlar;
import com.semih.modbot.core.Embeds;
import com.semih.modbot.core.Sure;
import com.semih.modbot.core.WarningStore;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.UserSnowflake;
import net.dv8tion.jda.api.entities.channel.attribute.IPermissionContainer;
import net.dv8tion.jda.api.entities.channel.attribute.ISlowmodeChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.utils.TimeFormat;

/** Ceza, Uyarı, Kanal ve Üye kategorilerindeki komutlar. */
public final class Moderasyon {

    private static final String SEBEP_YOK = "Sebep belirtilmedi";
    private static final Duration MAKS_SUSTURMA = Duration.ofDays(28);

    private final Ayarlar ayarlar;
    private final WarningStore warnings;
    private final int warnLimit;
    private final Duration warnPenalty;

    public Moderasyon(Ayarlar ayarlar, WarningStore warnings, int warnLimit, Duration warnPenalty) {
        this.ayarlar = ayarlar;
        this.warnings = warnings;
        this.warnLimit = warnLimit;
        this.warnPenalty = warnPenalty;
    }

    public List<Command> commands() {
        return List.of(
                // 🔨 Ceza
                Command.of("ban", Category.CEZA, "<@üye|ID> [sebep]", "Kullanıcıyı sunucudan yasaklar", this::ban, "yasakla"),
                Command.of("unban", Category.CEZA, "<ID> [sebep]", "Yasağı kaldırır", this::unban, "yasakkaldir"),
                Command.of("kick", Category.CEZA, "<@üye> [sebep]", "Üyeyi sunucudan atar", this::kick, "at"),
                Command.of("mute", Category.CEZA, "<@üye> <süre> [sebep]", "Üyeyi susturur (zaman aşımı, en fazla 28 gün)", this::mute, "sustur", "timeout"),
                Command.of("unmute", Category.CEZA, "<@üye> [sebep]", "Susturmayı kaldırır", this::unmute, "susturmakaldir"),
                Command.of("softban", Category.CEZA, "<@üye> [sebep]", "Atar ve son 7 günlük mesajlarını siler (yasaklı kalmaz)", this::softban),
                // ⚠️ Uyarı
                Command.of("uyar", Category.UYARI, "<@üye> <sebep>", "Uyarı verir; " + warnLimitText(), this::uyar, "warn"),
                Command.of("uyarilar", Category.UYARI, "<@üye|ID>", "Kişinin uyarılarını listeler", this::uyarilar, "warns"),
                Command.of("uyarisil", Category.UYARI, "<@üye|ID> [numara]", "Bir uyarıyı siler; numara yazmazsan hepsini", this::uyarisil, "unwarn"),
                // 📢 Kanal
                Command.of("temizle", Category.KANAL, "<1-100> [@üye]", "Son mesajları siler", this::temizle, "sil", "clear"),
                Command.of("kilitle", Category.KANAL, "[#kanal] [sebep]", "Kanalı herkese yazmaya kapatır", ctx -> kilit(ctx, true), "lock"),
                Command.of("kilitac", Category.KANAL, "[#kanal]", "Kanalın kilidini açar", ctx -> kilit(ctx, false), "unlock"),
                Command.of("yavasmod", Category.KANAL, "<süre|kapat> [#kanal]", "Yavaş modu ayarlar (en fazla 6h)", this::yavasmod, "slowmode"),
                Command.of("gizle", Category.KANAL, "[#kanal]", "Kanalı herkesten gizler", ctx -> gizle(ctx, true), "hide"),
                Command.of("goster", Category.KANAL, "[#kanal]", "Gizlenen kanalı tekrar gösterir", ctx -> gizle(ctx, false), "unhide"),
                // 👤 Üye
                Command.of("rolver", Category.UYE, "<@üye> <@rol|rol adı>", "Üyeye rol verir", ctx -> rol(ctx, true), "addrole"),
                Command.of("rolal", Category.UYE, "<@üye> <@rol|rol adı>", "Üyeden rol alır", ctx -> rol(ctx, false), "removerole"),
                Command.of("isim", Category.UYE, "<@üye> [yeni isim]", "Sunucudaki adını değiştirir; boş bırakırsan sıfırlar", this::isim, "nick"),
                Command.of("kullanici", Category.UYE, "[@üye|ID]", "Üye hakkında bilgi gösterir", this::kullanici, "bilgi", "userinfo"));
    }

    private String warnLimitText() {
        return warnLimit > 0 ? warnLimit + " uyarıda " + Sure.format(warnPenalty) + " otomatik susturur" : "otomatik ceza kapalı";
    }

    // ------------------------------------------------------------------
    // 🔨 Ceza
    // ------------------------------------------------------------------

    private void ban(Ctx ctx) {
        User user = ctx.needUser(0);
        Member member = ctx.guild.getMember(user);
        if (member == null) member = ctx.memberArg(0);
        if (member != null) ctx.canTarget(member);
        ctx.botNeeds(Permission.BAN_MEMBERS);
        String reason = reason(ctx, 1);
        if (member != null) dm(user, ceza("🔨", ctx.guild.getName() + " sunucusundan yasaklandın", reason, null));
        ctx.guild.ban(user, 0, TimeUnit.SECONDS).reason(audit(ctx, reason)).complete();
        ctx.info(Embeds.HATA, "🔨 **" + user.getName() + "** yasaklandı.\n**Sebep:** " + reason);
        logAction(ctx, Embeds.HATA, "🔨 Yasaklama", user, reason, null, null);
    }

    private void unban(Ctx ctx) {
        ctx.need(1);
        long id = Ctx.idOf(ctx.arg(0));
        if (id == 0) throw new Ctx.UserError("Geçerli bir kullanıcı ID'si yaz. Kullanım: " + ctx.usage());
        ctx.botNeeds(Permission.BAN_MEMBERS);
        String reason = reason(ctx, 1);
        ctx.guild.unban(UserSnowflake.fromId(id)).reason(audit(ctx, reason)).complete();
        ctx.ok("<@" + id + "> kişisinin yasağı kaldırıldı.");
        ayarlar.log(ctx.guild, Embeds.log(Embeds.BASARI, "🕊️ Yasak kaldırıldı")
                .addField("Kullanıcı", "<@" + id + ">", true)
                .addField("Yetkili", ctx.member.getAsMention(), true)
                .addField("Sebep", reason, false).setFooter("Kullanıcı ID: " + id).build());
    }

    private void kick(Ctx ctx) {
        Member target = ctx.needMember(0);
        ctx.canTarget(target);
        ctx.botNeeds(Permission.KICK_MEMBERS);
        String reason = reason(ctx, 1);
        dm(target.getUser(), ceza("👢", ctx.guild.getName() + " sunucusundan atıldın", reason, null));
        ctx.guild.kick(target).reason(audit(ctx, reason)).complete();
        ctx.info(Embeds.HATA, "👢 **" + target.getUser().getName() + "** sunucudan atıldı.\n**Sebep:** " + reason);
        logAction(ctx, Embeds.HATA, "👢 Atma", target.getUser(), reason, null, null);
    }

    private void mute(Ctx ctx) {
        ctx.need(2);
        Member target = ctx.needMember(0);
        ctx.canTarget(target);
        ctx.botNeeds(Permission.MODERATE_MEMBERS);
        if (target.hasPermission(Permission.ADMINISTRATOR)) throw new Ctx.UserError("Yönetici yetkisi olan biri susturulamaz.");
        Duration d = Sure.parse(ctx.arg(1));
        if (d == null) throw new Ctx.UserError("Süreyi anlayamadım: `" + ctx.arg(1) + "`. Örnek: `10m`, `2h`, `1d12h`");
        if (d.compareTo(MAKS_SUSTURMA) > 0) throw new Ctx.UserError("En fazla 28 gün (`28d`) susturabilirsin.");
        String reason = reason(ctx, 2);
        Instant end = Instant.now().plus(d);
        target.timeoutFor(d).reason(audit(ctx, reason)).complete();
        dm(target.getUser(), ceza("🔇", ctx.guild.getName() + " sunucusunda susturuldun", reason,
                Sure.format(d) + " (" + TimeFormat.RELATIVE.format(end) + " biter)"));
        ctx.info(Embeds.HATA, "🔇 " + target.getAsMention() + " **" + Sure.format(d) + "** susturuldu.\n"
                + "**Bitiş:** " + TimeFormat.RELATIVE.format(end) + "\n**Sebep:** " + reason);
        logAction(ctx, Embeds.HATA, "🔇 Susturma", target.getUser(), reason, "Süre", Sure.format(d));
    }

    private void unmute(Ctx ctx) {
        Member target = ctx.needMember(0);
        ctx.botNeeds(Permission.MODERATE_MEMBERS);
        if (!target.isTimedOut()) throw new Ctx.UserError(target.getAsMention() + " zaten susturulmuş değil.");
        String reason = reason(ctx, 1);
        target.removeTimeout().reason(audit(ctx, reason)).complete();
        ctx.ok(target.getAsMention() + " kişisinin susturması kaldırıldı.");
        logAction(ctx, Embeds.BASARI, "🔊 Susturma kaldırıldı", target.getUser(), reason, null, null);
    }

    private void softban(Ctx ctx) {
        Member target = ctx.needMember(0);
        ctx.canTarget(target);
        ctx.botNeeds(Permission.BAN_MEMBERS);
        String reason = reason(ctx, 1);
        User user = target.getUser();
        dm(user, ceza("🧹", ctx.guild.getName() + " sunucusundan atıldın ve mesajların silindi", reason, null));
        ctx.guild.ban(user, 7, TimeUnit.DAYS).reason(audit(ctx, "Softban: " + reason)).complete();
        ctx.guild.unban(user).reason("Softban (yasak hemen kaldırıldı)").complete();
        ctx.info(Embeds.HATA, "🧹 **" + user.getName() + "** atıldı ve son 7 günlük mesajları silindi. Tekrar katılabilir.\n**Sebep:** " + reason);
        logAction(ctx, Embeds.HATA, "🧹 Softban", user, reason, null, null);
    }

    // ------------------------------------------------------------------
    // ⚠️ Uyarı
    // ------------------------------------------------------------------

    private void uyar(Ctx ctx) {
        ctx.need(2);
        Member target = ctx.needMember(0);
        ctx.canTarget(target);
        String reason = ctx.rest(1);
        int count = warnings.add(ctx.guild.getIdLong(), target.getIdLong(), ctx.member.getIdLong(), reason);
        String countText = warnLimit > 0 ? count + "/" + warnLimit : String.valueOf(count);
        ctx.info(Embeds.UYARI, "⚠️ " + target.getAsMention() + " uyarıldı. **(" + countText + ")**\n**Sebep:** " + reason);
        dm(target.getUser(), ceza("⚠️", ctx.guild.getName() + " sunucusunda uyarı aldın", reason, "Toplam uyarın: " + countText));
        logAction(ctx, Embeds.UYARI, "⚠️ Uyarı", target.getUser(), reason, "Toplam", countText);

        if (warnLimit > 0 && count % warnLimit == 0) {
            if (target.hasPermission(Permission.ADMINISTRATOR)
                    || !ctx.guild.getSelfMember().hasPermission(Permission.MODERATE_MEMBERS)) {
                ctx.reply(Embeds.hata(target.getAsMention() + " uyarı limitine ulaştı ama susturamıyorum (yetki yetersiz)."));
                return;
            }
            target.timeoutFor(warnPenalty).reason("Uyarı limiti doldu (" + count + ")").complete();
            ctx.info(Embeds.HATA, "🔇 " + target.getAsMention() + " " + count + " uyarıya ulaştığı için **"
                    + Sure.format(warnPenalty) + "** otomatik susturuldu.");
            logAction(ctx, Embeds.HATA, "🔇 Otomatik susturma", target.getUser(), "Uyarı limiti doldu", "Süre", Sure.format(warnPenalty));
        }
    }

    private void uyarilar(Ctx ctx) {
        ctx.need(1);
        long id = Ctx.idOf(ctx.arg(0));
        if (id == 0) throw new Ctx.UserError("Bir üye etiketle ya da ID yaz. Kullanım: " + ctx.usage());
        List<WarningStore.Warning> list = warnings.list(ctx.guild.getIdLong(), id);
        if (list.isEmpty()) {
            ctx.ok("<@" + id + "> kişisinin hiç uyarısı yok.");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            WarningStore.Warning w = list.get(i);
            String line = "**#" + (i + 1) + "** • " + TimeFormat.RELATIVE.format(w.time()) + " • <@" + w.moderatorId()
                    + ">\n> " + w.reason() + "\n";
            if (sb.length() + line.length() > 3900) {
                sb.append("… ve ").append(list.size() - i).append(" uyarı daha");
                break;
            }
            sb.append(line);
        }
        ctx.reply(new EmbedBuilder().setColor(Embeds.UYARI)
                .setTitle("⚠️ " + list.size() + " uyarı")
                .setDescription("<@" + id + ">\n\n" + sb)
                .setFooter("Silmek için: " + ctx.prefix + "uyarisil <@üye> [numara]").build());
    }

    private void uyarisil(Ctx ctx) {
        ctx.need(1);
        long id = Ctx.idOf(ctx.arg(0));
        if (id == 0) throw new Ctx.UserError("Bir üye etiketle ya da ID yaz. Kullanım: " + ctx.usage());
        String which = ctx.arg(1);
        if (which == null || which.equalsIgnoreCase("hepsi")) {
            int removed = warnings.clear(ctx.guild.getIdLong(), id);
            if (removed == 0) throw new Ctx.UserError("<@" + id + "> kişisinin zaten uyarısı yok.");
            ctx.ok("<@" + id + "> kişisinin tüm uyarıları silindi (" + removed + " adet).");
            return;
        }
        int n;
        try {
            n = Integer.parseInt(which.replace("#", ""));
        } catch (NumberFormatException e) {
            throw new Ctx.UserError("Uyarı numarası bir sayı olmalı. Numaraları `" + ctx.prefix + "uyarilar` ile görebilirsin.");
        }
        if (!warnings.remove(ctx.guild.getIdLong(), id, n)) {
            throw new Ctx.UserError("#" + n + " numaralı bir uyarı yok.");
        }
        ctx.ok("<@" + id + "> kişisinin #" + n + " numaralı uyarısı silindi.");
    }

    // ------------------------------------------------------------------
    // 📢 Kanal
    // ------------------------------------------------------------------

    private void temizle(Ctx ctx) {
        ctx.need(1);
        ctx.botNeeds(Permission.MESSAGE_MANAGE, Permission.MESSAGE_HISTORY);
        int amount;
        try {
            amount = Integer.parseInt(ctx.arg(0));
        } catch (NumberFormatException e) {
            throw new Ctx.UserError("Kaç mesaj silineceğini sayıyla yaz (1-100). Kullanım: " + ctx.usage());
        }
        if (amount < 1 || amount > 100) throw new Ctx.UserError("1 ile 100 arasında bir sayı yaz.");
        Member filter = ctx.size() > 1 ? ctx.memberArg(1) : null;
        long filterId = filter != null ? filter.getIdLong() : Ctx.idOf(ctx.arg(1));

        ctx.message.delete().complete();
        List<Message> history = ctx.channel.getHistory().retrievePast(100).complete();
        List<Message> toDelete = history.stream()
                .filter(m -> !m.isPinned())
                .filter(m -> filterId == 0 || m.getAuthor().getIdLong() == filterId)
                .limit(amount).toList();
        if (toDelete.isEmpty()) {
            ctx.channel.sendMessageEmbeds(Embeds.hata("Silinecek mesaj bulunamadı."))
                    .queue(m -> m.delete().queueAfter(5, TimeUnit.SECONDS, null, x -> { }));
            return;
        }
        List<CompletableFuture<Void>> futures = ctx.channel.purgeMessages(toDelete);
        CompletableFuture.allOf(futures.toArray(CompletableFuture<?>[]::new)).exceptionally(x -> null).join();
        long old = toDelete.stream().filter(m -> m.getTimeCreated().isBefore(OffsetDateTime.now().minusDays(14))).count();
        ctx.channel.sendMessageEmbeds(Embeds.basari("🧹 " + toDelete.size() + " mesaj silindi."
                        + (old > 0 ? " (" + old + " tanesi 14 günden eski olduğu için tek tek silindi)" : "")))
                .queue(m -> m.delete().queueAfter(5, TimeUnit.SECONDS, null, x -> { }));
        ayarlar.log(ctx.guild, Embeds.log(Embeds.GRI, "🧹 Mesajlar temizlendi")
                .addField("Kanal", ctx.channel.getAsMention(), true)
                .addField("Adet", String.valueOf(toDelete.size()), true)
                .addField("Kimin", filterId != 0 ? "<@" + filterId + ">" : "Herkes", true)
                .addField("Yetkili", ctx.member.getAsMention(), true).build());
    }

    private GuildChannel kanalArgVeyaBurasi(Ctx ctx, int i) {
        GuildChannel ch = ctx.channelArg(i);
        return ch != null ? ch : ctx.channel;
    }

    private void kilit(Ctx ctx, boolean lock) {
        GuildChannel ch = kanalArgVeyaBurasi(ctx, 0);
        int reasonFrom = ctx.channelArg(0) != null ? 1 : 0;
        if (!(ch instanceof IPermissionContainer container)) throw new Ctx.UserError("Bu kanal kilitlenemez.");
        ctx.botNeeds(Permission.MANAGE_CHANNEL, Permission.MANAGE_PERMISSIONS);
        String reason = lock ? reason(ctx, reasonFrom) : "Kilit açıldı";
        var override = container.upsertPermissionOverride(ctx.guild.getPublicRole());
        (lock ? override.deny(Permission.MESSAGE_SEND, Permission.MESSAGE_SEND_IN_THREADS, Permission.CREATE_PUBLIC_THREADS)
                : override.clear(Permission.MESSAGE_SEND, Permission.MESSAGE_SEND_IN_THREADS, Permission.CREATE_PUBLIC_THREADS))
                .reason(audit(ctx, reason)).complete();
        ctx.info(lock ? Embeds.HATA : Embeds.BASARI, lock
                ? "🔒 " + ch.getAsMention() + " kilitlendi." + (reason.equals(SEBEP_YOK) ? "" : "\n**Sebep:** " + reason)
                : "🔓 " + ch.getAsMention() + " kanalının kilidi açıldı.");
        ayarlar.log(ctx.guild, Embeds.log(lock ? Embeds.HATA : Embeds.BASARI, lock ? "🔒 Kanal kilitlendi" : "🔓 Kanal açıldı")
                .addField("Kanal", ch.getAsMention(), true).addField("Yetkili", ctx.member.getAsMention(), true).build());
    }

    private void gizle(Ctx ctx, boolean hide) {
        GuildChannel ch = kanalArgVeyaBurasi(ctx, 0);
        if (!(ch instanceof IPermissionContainer container)) throw new Ctx.UserError("Bu kanal gizlenemez.");
        ctx.botNeeds(Permission.MANAGE_CHANNEL, Permission.MANAGE_PERMISSIONS);
        // Bot kanalı görmeye devam etsin
        container.upsertPermissionOverride(ctx.guild.getSelfMember()).grant(Permission.VIEW_CHANNEL).complete();
        var override = container.upsertPermissionOverride(ctx.guild.getPublicRole());
        (hide ? override.deny(Permission.VIEW_CHANNEL) : override.clear(Permission.VIEW_CHANNEL))
                .reason(audit(ctx, hide ? "Kanal gizlendi" : "Kanal gösterildi")).complete();
        ctx.info(hide ? Embeds.GRI : Embeds.BASARI, hide
                ? "🙈 " + ch.getAsMention() + " herkesten gizlendi."
                : "👀 " + ch.getAsMention() + " tekrar herkese görünür.");
    }

    private void yavasmod(Ctx ctx) {
        ctx.need(1);
        GuildChannel ch = kanalArgVeyaBurasi(ctx, 1);
        if (!(ch instanceof ISlowmodeChannel slow)) throw new Ctx.UserError("Bu kanalda yavaş mod ayarlanamaz.");
        ctx.botNeeds(Permission.MANAGE_CHANNEL);
        int seconds;
        String a = ctx.arg(0).toLowerCase(java.util.Locale.ROOT);
        if (a.equals("kapat") || a.equals("0") || a.equals("off")) {
            seconds = 0;
        } else {
            Duration d = Sure.parse(a);
            if (d == null) throw new Ctx.UserError("Süreyi anlayamadım. Örnek: `10s`, `1m`, `kapat`");
            if (d.getSeconds() > 21600) throw new Ctx.UserError("Yavaş mod en fazla 6 saat (`6h`) olabilir.");
            seconds = (int) d.getSeconds();
        }
        slow.getManager().setSlowmode(seconds).reason(audit(ctx, "Yavaş mod")).complete();
        ctx.info(Embeds.BILGI, seconds == 0 ? "🐇 " + ch.getAsMention() + " yavaş mod kapatıldı."
                : "🐢 " + ch.getAsMention() + " yavaş mod: herkes **" + Sure.format(Duration.ofSeconds(seconds)) + "** arayla yazabilir.");
    }

    // ------------------------------------------------------------------
    // 👤 Üye
    // ------------------------------------------------------------------

    private void rol(Ctx ctx, boolean add) {
        ctx.need(2);
        Member target = ctx.needMember(0);
        Role role = ctx.needRole(1);
        ctx.botNeeds(Permission.MANAGE_ROLES);
        if (role.isPublicRole() || role.isManaged()) throw new Ctx.UserError("Bu rol verilip alınamaz.");
        if (!ctx.guild.getSelfMember().canInteract(role)) {
            throw new Ctx.UserError(role.getAsMention() + " rolü benim rolümün üstünde. Sunucu Ayarları → Roller'den bot rolünü yukarı taşı.");
        }
        boolean has = target.getRoles().contains(role);
        if (add && has) throw new Ctx.UserError(target.getAsMention() + " zaten " + role.getAsMention() + " rolüne sahip.");
        if (!add && !has) throw new Ctx.UserError(target.getAsMention() + " kişisinde " + role.getAsMention() + " rolü yok.");
        if (add) {
            ctx.guild.addRoleToMember(target, role).reason(audit(ctx, "Rol verildi")).complete();
            ctx.ok(target.getAsMention() + " kişisine " + role.getAsMention() + " rolü verildi.");
        } else {
            ctx.guild.removeRoleFromMember(target, role).reason(audit(ctx, "Rol alındı")).complete();
            ctx.ok(target.getAsMention() + " kişisinden " + role.getAsMention() + " rolü alındı.");
        }
        ayarlar.log(ctx.guild, Embeds.log(Embeds.BILGI, add ? "➕ Rol verildi" : "➖ Rol alındı")
                .addField("Üye", target.getAsMention(), true).addField("Rol", role.getAsMention(), true)
                .addField("Yetkili", ctx.member.getAsMention(), true).build());
    }

    private void isim(Ctx ctx) {
        Member target = ctx.needMember(0);
        ctx.botNeeds(Permission.NICKNAME_MANAGE);
        if (target.isOwner()) throw new Ctx.UserError("Sunucu sahibinin adı değiştirilemez.");
        if (!ctx.guild.getSelfMember().canInteract(target)) throw new Ctx.UserError("Bu kişinin rolü benim rolümden yüksek.");
        String name = ctx.rest(1);
        if (name.length() > 32) throw new Ctx.UserError("İsim en fazla 32 karakter olabilir.");
        target.modifyNickname(name.isEmpty() ? null : name).reason(audit(ctx, "İsim değişti")).complete();
        ctx.ok(name.isEmpty() ? target.getAsMention() + " kişisinin sunucu adı sıfırlandı."
                : target.getAsMention() + " kişisinin adı **" + name + "** oldu.");
    }

    private void kullanici(Ctx ctx) {
        Member target = ctx.size() > 0 ? ctx.memberArg(0) : ctx.member;
        if (target == null) throw new Ctx.UserError("Bu kişiyi sunucuda bulamadım.");
        User u = target.getUser();
        String roles = target.getRoles().stream().map(Role::getAsMention).collect(Collectors.joining(" "));
        int warnCount = warnings.list(ctx.guild.getIdLong(), target.getIdLong()).size();
        EmbedBuilder eb = new EmbedBuilder().setColor(Embeds.ANA)
                .setAuthor(u.getName(), null, u.getEffectiveAvatarUrl())
                .setThumbnail(target.getEffectiveAvatarUrl())
                .addField("Kullanıcı", target.getAsMention() + (u.isBot() ? " 🤖" : ""), true)
                .addField("ID", "`" + u.getId() + "`", true)
                .addField("Uyarı", String.valueOf(warnCount), true)
                .addField("Hesap açılışı", TimeFormat.DATE_SHORT.format(u.getTimeCreated()) + " (" + TimeFormat.RELATIVE.format(u.getTimeCreated()) + ")", true)
                .addField("Sunucuya katılış", TimeFormat.DATE_SHORT.format(target.getTimeJoined()) + " (" + TimeFormat.RELATIVE.format(target.getTimeJoined()) + ")", true)
                .addField("Durum", target.isTimedOut() ? "🔇 Susturulmuş (" + TimeFormat.RELATIVE.format(target.getTimeOutEnd()) + " biter)" : "Normal", true)
                .addField("Roller (" + target.getRoles().size() + ")", roles.isEmpty() ? "Yok" : Embeds.kisalt(roles, 1024), false);
        ctx.reply(eb.build());
    }

    // ------------------------------------------------------------------
    // Yardımcılar
    // ------------------------------------------------------------------

    private static String reason(Ctx ctx, int from) {
        String r = ctx.rest(from);
        return r.isEmpty() ? SEBEP_YOK : Embeds.kisalt(r, 400);
    }

    private static String audit(Ctx ctx, String reason) {
        return Embeds.kisalt(ctx.member.getUser().getName() + ": " + reason, 500);
    }

    private static MessageEmbed ceza(String emoji, String title, String reason, String extra) {
        EmbedBuilder eb = new EmbedBuilder().setColor(Embeds.HATA).setTitle(emoji + " " + title).addField("Sebep", reason, false);
        if (extra != null) eb.addField("Bilgi", extra, false);
        return eb.build();
    }

    /** Kişiye özel mesaj; kapalıysa sessizce geçer. */
    private static void dm(User user, MessageEmbed embed) {
        try {
            user.openPrivateChannel().flatMap(ch -> ch.sendMessageEmbeds(embed)).complete();
        } catch (Exception ignored) {
            // DM'leri kapalı
        }
    }

    private void logAction(Ctx ctx, int color, String title, User target, String reason, String extraName, String extraValue) {
        Guild g = ctx.guild;
        EmbedBuilder eb = Embeds.log(color, title)
                .setThumbnail(target.getEffectiveAvatarUrl())
                .addField("Kullanıcı", target.getAsMention() + " (`" + target.getName() + "`)", true)
                .addField("Yetkili", ctx.member.getAsMention(), true);
        if (extraName != null) eb.addField(extraName, extraValue, true);
        eb.addField("Sebep", reason, false).setFooter("Kullanıcı ID: " + target.getId());
        ayarlar.log(g, eb.build());
    }
}
