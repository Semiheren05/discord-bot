package com.semih.modbot.features;

import com.semih.modbot.command.Category;
import com.semih.modbot.command.Command;
import com.semih.modbot.command.Ctx;
import com.semih.modbot.core.Ayarlar;
import com.semih.modbot.core.Embeds;
import java.util.List;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.utils.TimeFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bilgi & Ayar komutları (yardım menüsü hariç) ve hoş geldin / otorol. */
public final class Bilgi extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(Bilgi.class);

    private final Ayarlar ayarlar;

    public Bilgi(Ayarlar ayarlar) {
        this.ayarlar = ayarlar;
    }

    public List<Command> commands() {
        return List.of(
                Command.of("ping", Category.BILGI, "", "Botun gecikmesini gösterir", this::ping),
                Command.of("sunucu", Category.BILGI, "", "Sunucu bilgilerini ve botun ayarlarını gösterir", this::sunucu, "server"),
                Command.of("log", Category.BILGI, "<#kanal|kapat>", "Log kanalını ayarlar (silinen mesajlar, giriş-çıkış, cezalar)", this::logKomut),
                Command.of("hosgeldin", Category.BILGI, "<#kanal|kapat> [mesaj]",
                        "Hoş geldin kanalı ve mesajı. {kullanici} {isim} {sunucu} {uye_sayisi} kullanılabilir", this::hosgeldin, "welcome"),
                Command.of("otorol", Category.BILGI, "<@rol|kapat>", "Yeni gelenlere otomatik verilecek rol", this::otorol, "autorole"));
    }

    private void ping(Ctx ctx) {
        long gateway = ctx.jda().getGatewayPing();
        long rest = ctx.jda().getRestPing().complete();
        ctx.info(Embeds.BILGI, "🏓 Pong!\n**Gateway:** " + gateway + " ms\n**API:** " + rest + " ms");
    }

    private void sunucu(Ctx ctx) {
        Guild g = ctx.guild;
        Member owner = g.retrieveOwner().complete();
        EmbedBuilder eb = new EmbedBuilder().setColor(Embeds.ANA)
                .setTitle("🏠 " + g.getName())
                .setThumbnail(g.getIconUrl())
                .addField("Sahip", owner.getAsMention(), true)
                .addField("Üye", String.valueOf(g.getMemberCount()), true)
                .addField("Kuruluş", TimeFormat.DATE_SHORT.format(g.getTimeCreated()), true)
                .addField("Kanal", g.getTextChannels().size() + " yazı • " + g.getVoiceChannels().size() + " ses", true)
                .addField("Rol", String.valueOf(g.getRoles().size()), true)
                .addField("Takviye", g.getBoostCount() + " (seviye " + g.getBoostTier().getKey() + ")", true)
                .addField("⚙️ Bot ayarları",
                        "**Log:** " + kanal(g, Ayarlar.LOG) + "\n"
                                + "**Hoş geldin:** " + kanal(g, Ayarlar.HOSGELDIN_KANAL) + "\n"
                                + "**Otorol:** " + (ayarlar.id(g, Ayarlar.OTOROL) == 0 ? "Kapalı" : "<@&" + ayarlar.id(g, Ayarlar.OTOROL) + ">"),
                        false)
                .setFooter("ID: " + g.getId());
        ctx.reply(eb.build());
    }

    private String kanal(Guild g, String key) {
        long id = ayarlar.id(g, key);
        return id == 0 ? "Kapalı" : "<#" + id + ">";
    }

    private void logKomut(Ctx ctx) {
        ctx.need(1);
        if (ctx.arg(0).equalsIgnoreCase("kapat")) {
            ayarlar.set(ctx.guild, Ayarlar.LOG, "");
            ctx.ok("Log kapatıldı.");
            return;
        }
        GuildMessageChannel ch = ctx.textChannelArg(0);
        if (ch == null) throw new Ctx.UserError("Bir kanal etiketle. Kullanım: " + ctx.usage());
        checkChannel(ctx, ch);
        ayarlar.set(ctx.guild, Ayarlar.LOG, ch.getId());
        ctx.ok("Log kanalı " + ch.getAsMention() + " olarak ayarlandı. Bu kanalı sadece yetkililer görebilsin.");
    }

    private void hosgeldin(Ctx ctx) {
        ctx.need(1);
        if (ctx.arg(0).equalsIgnoreCase("kapat")) {
            ayarlar.set(ctx.guild, Ayarlar.HOSGELDIN_KANAL, "");
            ctx.ok("Hoş geldin mesajı kapatıldı.");
            return;
        }
        GuildMessageChannel ch = ctx.textChannelArg(0);
        if (ch == null) throw new Ctx.UserError("Bir kanal etiketle. Kullanım: " + ctx.usage());
        checkChannel(ctx, ch);
        ayarlar.set(ctx.guild, Ayarlar.HOSGELDIN_KANAL, ch.getId());
        String msg = ctx.rest(1);
        if (!msg.isEmpty()) ayarlar.set(ctx.guild, Ayarlar.HOSGELDIN_MESAJ, msg);
        String current = ayarlar.get(ctx.guild, Ayarlar.HOSGELDIN_MESAJ);
        ctx.ok("Hoş geldin kanalı " + ch.getAsMention() + ".\n**Mesaj:** "
                + (current.isEmpty() ? Ayarlar.VARSAYILAN_HOSGELDIN : current));
    }

    private void otorol(Ctx ctx) {
        ctx.need(1);
        if (ctx.arg(0).equalsIgnoreCase("kapat")) {
            ayarlar.set(ctx.guild, Ayarlar.OTOROL, "");
            ctx.ok("Otorol kapatıldı.");
            return;
        }
        Role role = ctx.needRole(0);
        Member self = ctx.guild.getSelfMember();
        if (role.isPublicRole() || role.isManaged()) throw new Ctx.UserError("Bu rol otomatik verilemez.");
        if (role.hasPermission(Permission.ADMINISTRATOR)) throw new Ctx.UserError("Yönetici yetkili bir rolü herkese veremem, çok tehlikeli.");
        if (!self.hasPermission(Permission.MANAGE_ROLES)) throw new Ctx.UserError("Bana **Rolleri Yönet** yetkisi vermelisin.");
        if (!self.canInteract(role)) throw new Ctx.UserError(role.getAsMention() + " rolü benim rolümün üstünde. Bot rolünü yukarı taşı.");
        ayarlar.set(ctx.guild, Ayarlar.OTOROL, role.getId());
        ctx.ok("Yeni gelenlere otomatik " + role.getAsMention() + " rolü verilecek.");
    }

    private static void checkChannel(Ctx ctx, GuildMessageChannel ch) {
        Member self = ctx.guild.getSelfMember();
        if (!self.hasPermission(ch, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS)) {
            throw new Ctx.UserError(ch.getAsMention() + " kanalını göremiyorum ya da yazamıyorum. Kanal izinlerinden bana "
                    + "**Kanalı Görüntüle**, **Mesaj Gönder** ve **Bağlantı Yerleştir** izni ver.");
        }
    }

    // ---- Hoş geldin ve otorol ----

    @Override
    public void onGuildMemberJoin(GuildMemberJoinEvent event) {
        Member member = event.getMember();
        if (member.getUser().isBot()) return;
        Guild g = event.getGuild();

        long roleId = ayarlar.id(g, Ayarlar.OTOROL);
        Role role = roleId == 0 ? null : g.getRoleById(roleId);
        if (role != null && g.getSelfMember().canInteract(role) && g.getSelfMember().hasPermission(Permission.MANAGE_ROLES)) {
            g.addRoleToMember(member, role).reason("Otorol").queue(null, e -> log.warn("Otorol verilemedi: {}", e.getMessage()));
        }

        GuildMessageChannel ch = ayarlar.channel(g, Ayarlar.HOSGELDIN_KANAL);
        if (ch == null) return;
        String text = ayarlar.get(g, Ayarlar.HOSGELDIN_MESAJ);
        if (text.isEmpty()) text = Ayarlar.VARSAYILAN_HOSGELDIN;
        ch.sendMessage(member.getAsMention())
                .setEmbeds(new EmbedBuilder().setColor(Embeds.ANA)
                        .setTitle("👋 Hoş geldin!")
                        .setDescription(fill(text, member.getUser(), g))
                        .setThumbnail(member.getUser().getEffectiveAvatarUrl())
                        .setFooter(g.getName() + " • " + g.getMemberCount() + ". üye").build())
                .queue(null, e -> log.warn("Hoş geldin mesajı gönderilemedi: {}", e.getMessage()));
    }

    static String fill(String template, User user, Guild g) {
        return template.replace("\\n", "\n")
                .replace("{kullanici}", user.getAsMention())
                .replace("{isim}", user.getEffectiveName())
                .replace("{sunucu}", g.getName())
                .replace("{uye_sayisi}", String.valueOf(g.getMemberCount()));
    }
}
