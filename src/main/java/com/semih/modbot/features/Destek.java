package com.semih.modbot.features;

import com.semih.modbot.command.Command;
import com.semih.modbot.command.Ctx;
import com.semih.modbot.command.Yetki;
import com.semih.modbot.core.Ayarlar;
import com.semih.modbot.core.Embeds;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.utils.FileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Destek (ticket) sistemi: panel mesajındaki butona basan üyeye özel bir kanal açılır.
 * Kanalı sadece o üye, destek rolü ve bot görür. Kapatılınca konuşma dökümü log kanalına gönderilir.
 */
public final class Destek extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(Destek.class);
    private static final String ROL = "ticket.rol";
    private static final String KATEGORI = "ticket.kategori";
    private static final String SAYAC = "ticket.sayac";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private static final EnumSet<Permission> USER_PERMS = EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND,
            Permission.MESSAGE_HISTORY, Permission.MESSAGE_ATTACH_FILES, Permission.MESSAGE_EMBED_LINKS);

    private final Ayarlar ayarlar;

    public Destek(Ayarlar ayarlar) {
        this.ayarlar = ayarlar;
    }

    public List<Command> commands() {
        return List.of(
                Command.of("ticket-kur", com.semih.modbot.command.Category.DESTEK, "[#kanal] [@destek-rolü]",
                        "Destek panelini gönderir; butona basan üyeye özel kanal açılır", this::kur, "destek-kur", "ticketpanel"),
                Command.of("ticket-kapat", com.semih.modbot.command.Category.DESTEK, "[sebep]", "Bulunduğun destek kanalını kapatır (döküm log kanalına gider)", this::kapatKomut, "kapat", "close"),
                Command.of("ticket-ekle", com.semih.modbot.command.Category.DESTEK, "<@üye>", "Destek kanalına birini ekler", ctx -> uye(ctx, true), "ekle"),
                Command.of("ticket-cikar", com.semih.modbot.command.Category.DESTEK, "<@üye>", "Destek kanalından birini çıkarır", ctx -> uye(ctx, false), "cikar"));
    }

    // ------------------------------------------------------------------
    // Komutlar
    // ------------------------------------------------------------------

    private void kur(Ctx ctx) {
        Guild g = ctx.guild;
        GuildMessageChannel panel = ctx.channel;
        int next = 0;
        GuildMessageChannel given = ctx.textChannelArg(0);
        if (given != null) {
            panel = given;
            next = 1;
        }
        if (ctx.arg(next) != null) {
            Role role = ctx.needRole(next);
            ayarlar.set(g, ROL, role.getId());
        }
        Member self = g.getSelfMember();
        if (!self.hasPermission(Permission.MANAGE_CHANNEL, Permission.MANAGE_ROLES)) {
            throw new Ctx.UserError("Destek kanalı açabilmem için sunucuda **Kanalları Yönet** ve **Rolleri Yönet** yetkim olmalı.");
        }
        Category cat = kategori(g);
        long roleId = ayarlar.id(g, ROL);
        panel.sendMessageEmbeds(new EmbedBuilder().setColor(Embeds.ANA)
                        .setTitle("🎫 Destek")
                        .setDescription("Bir sorunun mu var ya da yetkililere mi ulaşmak istiyorsun?\n"
                                + "Aşağıdaki butona bas, sana özel bir destek kanalı açılsın.\n\n"
                                + "Kanalı sadece sen ve yetkililer görebilir.")
                        .setFooter(g.getName(), g.getIconUrl()).build())
                .setComponents(ActionRow.of(Button.primary("ticket:ac", "Destek Talebi Aç").withEmoji(Emoji.fromUnicode("🎫"))))
                .complete();
        ctx.ok("Destek paneli " + panel.getAsMention() + " kanalına gönderildi.\n"
                + "**Kategori:** " + cat.getName() + "\n**Destek rolü:** " + (roleId == 0 ? "yok (sadece yöneticiler görür)" : "<@&" + roleId + ">")
                + (ayarlar.id(g, Ayarlar.LOG) == 0 ? "\n💡 Kapatılan taleplerin dökümü için `" + ctx.prefix + "log #kanal` ile log kanalı ayarla." : ""));
    }

    private void kapatKomut(Ctx ctx) {
        if (!isTicket(ctx.channel)) throw new Ctx.UserError("Bu komut sadece destek kanallarında çalışır.");
        String reason = ctx.rest(0);
        ctx.info(Embeds.HATA, "🔒 Bu destek kanalı 5 saniye içinde kapatılacak.");
        close((TextChannel) ctx.channel, ctx.member.getUser(), reason.isEmpty() ? "Belirtilmedi" : reason);
    }

    private void uye(Ctx ctx, boolean add) {
        if (!isTicket(ctx.channel)) throw new Ctx.UserError("Bu komut sadece destek kanallarında çalışır.");
        Member target = ctx.needMember(0);
        TextChannel ch = (TextChannel) ctx.channel;
        if (add) {
            ch.upsertPermissionOverride(target).grant(USER_PERMS).complete();
            ctx.ok(target.getAsMention() + " bu destek kanalına eklendi.");
        } else {
            if (String.valueOf(target.getIdLong()).equals(ownerOf(ch))) throw new Ctx.UserError("Talebi açan kişi çıkarılamaz.");
            var po = ch.getPermissionOverride(target);
            if (po != null) po.delete().complete();
            ctx.ok(target.getAsMention() + " bu destek kanalından çıkarıldı.");
        }
    }

    // ------------------------------------------------------------------
    // Butonlar
    // ------------------------------------------------------------------

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (id.equals("ticket:ac")) {
            open(event);
        } else if (id.equals("ticket:kapat")) {
            if (!(event.getChannel() instanceof TextChannel ch) || !isTicket(ch)) return;
            Member m = event.getMember();
            boolean allowed = m != null && (String.valueOf(m.getIdLong()).equals(ownerOf(ch))
                    || m.hasPermission(Permission.MANAGE_CHANNEL) || hasSupportRole(m));
            if (!allowed) {
                event.replyEmbeds(Embeds.hata("Bu talebi sadece açan kişi ya da yetkililer kapatabilir.")).setEphemeral(true).queue();
                return;
            }
            event.replyEmbeds(Embeds.renkli(Embeds.HATA, "🔒 Bu destek kanalı 5 saniye içinde kapatılacak.")).queue();
            new Thread(() -> close(ch, event.getUser(), "Buton ile kapatıldı"), "ticket-kapat").start();
        }
    }

    private void open(ButtonInteractionEvent event) {
        Guild g = event.getGuild();
        Member m = event.getMember();
        if (g == null || m == null) return;
        // Zaten açık talebi var mı?
        for (TextChannel ch : g.getTextChannels()) {
            if (isTicket(ch) && String.valueOf(m.getIdLong()).equals(ownerOf(ch))) {
                event.replyEmbeds(Embeds.hata("Zaten açık bir destek talebin var: " + ch.getAsMention())).setEphemeral(true).queue();
                return;
            }
        }
        event.deferReply(true).queue();
        new Thread(() -> {
            try {
                Category cat = kategori(g);
                long num = ayarlar.store.getLong(Ayarlar.key(g.getIdLong(), SAYAC)) + 1;
                ayarlar.set(g, SAYAC, String.valueOf(num));
                String name = String.format("destek-%04d-%s", num, slug(m.getUser().getName()));
                long self = g.getSelfMember().getIdLong();
                var action = g.createTextChannel(name, cat)
                        .setTopic("ticket:" + m.getId() + " • " + m.getUser().getName() + " tarafından açıldı")
                        .addPermissionOverride(g.getPublicRole(), null, EnumSet.of(Permission.VIEW_CHANNEL))
                        .addMemberPermissionOverride(self, Permission.getRaw(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND,
                                Permission.MESSAGE_HISTORY, Permission.MANAGE_CHANNEL, Permission.MESSAGE_EMBED_LINKS,
                                Permission.MESSAGE_ATTACH_FILES, Permission.MESSAGE_MANAGE), 0)
                        .addPermissionOverride(m, USER_PERMS, null);
                long roleId = ayarlar.id(g, ROL);
                Role role = roleId == 0 ? null : g.getRoleById(roleId);
                if (role != null) action = action.addPermissionOverride(role, USER_PERMS, null);
                TextChannel ch = action.reason("Destek talebi: " + m.getUser().getName()).complete();
                ch.sendMessage(m.getAsMention() + (role != null ? " " + role.getAsMention() : ""))
                        .setEmbeds(new EmbedBuilder().setColor(Embeds.ANA)
                                .setTitle("🎫 Destek talebi #" + num)
                                .setDescription("Merhaba " + m.getAsMention() + "! Sorununu buraya yaz, yetkililer en kısa sürede dönecek.\n\n"
                                        + "İşin bitince aşağıdaki butonla talebi kapatabilirsin.").build())
                        .setComponents(ActionRow.of(Button.danger("ticket:kapat", "Talebi Kapat").withEmoji(Emoji.fromUnicode("🔒"))))
                        .complete();
                event.getHook().editOriginalEmbeds(Embeds.basari("Destek kanalın açıldı: " + ch.getAsMention())).queue();
                ayarlar.log(g, Embeds.log(Embeds.BILGI, "🎫 Destek talebi açıldı")
                        .addField("Üye", m.getAsMention(), true).addField("Kanal", ch.getAsMention(), true).build());
            } catch (Exception e) {
                log.warn("Destek kanalı açılamadı: {}", e.toString());
                event.getHook().editOriginalEmbeds(Embeds.hata("Destek kanalı açılamadı: " + Yetki.hata(e))).queue();
            }
        }, "ticket-ac").start();
    }

    /** Kanalı kapatır: dökümü log kanalına atar, 5 saniye sonra siler. */
    private void close(TextChannel ch, User closer, String reason) {
        Guild g = ch.getGuild();
        String owner = ownerOf(ch);
        try {
            List<Message> messages = new ArrayList<>();
            var history = ch.getIterableHistory().cache(false);
            for (Message msg : history) {
                messages.add(msg);
                if (messages.size() >= 1000) break;
            }
            Collections.reverse(messages);
            StringBuilder sb = new StringBuilder();
            sb.append("Destek talebi: #").append(ch.getName()).append('\n');
            sb.append("Açan: ").append(owner).append(" • Kapatan: ").append(closer.getName()).append(" • Sebep: ").append(reason).append("\n\n");
            ZoneId zone = ZoneId.systemDefault();
            for (Message msg : messages) {
                sb.append('[').append(msg.getTimeCreated().atZoneSameInstant(zone).format(TIME)).append("] ")
                        .append(msg.getAuthor().getName()).append(": ").append(msg.getContentDisplay());
                for (Message.Attachment a : msg.getAttachments()) sb.append(" [ek: ").append(a.getUrl()).append(']');
                if (!msg.getEmbeds().isEmpty() && msg.getContentDisplay().isEmpty()) sb.append("[embed]");
                sb.append('\n');
            }
            GuildMessageChannel logCh = ayarlar.channel(g, Ayarlar.LOG);
            if (logCh != null) {
                logCh.sendMessageEmbeds(Embeds.log(Embeds.GRI, "🔒 Destek talebi kapatıldı")
                                .addField("Kanal", ch.getName(), true)
                                .addField("Açan", owner.isEmpty() ? "?" : "<@" + owner + ">", true)
                                .addField("Kapatan", closer.getAsMention(), true)
                                .addField("Sebep", Embeds.kisalt(reason, 1000), false)
                                .addField("Mesaj", String.valueOf(messages.size()), true).build())
                        .addFiles(FileUpload.fromData(sb.toString().getBytes(StandardCharsets.UTF_8), ch.getName() + ".txt"))
                        .complete();
            }
        } catch (Exception e) {
            log.warn("Destek dökümü alınamadı: {}", e.toString());
        }
        ch.delete().reason("Destek talebi kapatıldı: " + reason).queueAfter(5, TimeUnit.SECONDS, null,
                e -> log.warn("Destek kanalı silinemedi: {}", e.getMessage()));
    }

    // ------------------------------------------------------------------
    // Yardımcılar
    // ------------------------------------------------------------------

    private Category kategori(Guild g) {
        long id = ayarlar.id(g, KATEGORI);
        Category cat = id == 0 ? null : g.getCategoryById(id);
        if (cat == null) {
            List<Category> byName = g.getCategoriesByName("Destek", true);
            cat = byName.isEmpty()
                    ? g.createCategory("Destek")
                        .addPermissionOverride(g.getPublicRole(), null, EnumSet.of(Permission.VIEW_CHANNEL))
                        .addMemberPermissionOverride(g.getSelfMember().getIdLong(),
                                Permission.getRaw(Permission.VIEW_CHANNEL, Permission.MANAGE_CHANNEL), 0)
                        .complete()
                    : byName.get(0);
            ayarlar.set(g, KATEGORI, cat.getId());
        }
        return cat;
    }

    private static boolean isTicket(GuildMessageChannel ch) {
        return ch instanceof TextChannel tc && tc.getTopic() != null && tc.getTopic().startsWith("ticket:");
    }

    private static String ownerOf(TextChannel ch) {
        String topic = ch.getTopic();
        if (topic == null || !topic.startsWith("ticket:")) return "";
        String rest = topic.substring("ticket:".length());
        int sp = rest.indexOf(' ');
        return sp < 0 ? rest : rest.substring(0, sp);
    }

    private boolean hasSupportRole(Member m) {
        long roleId = ayarlar.id(m.getGuild(), ROL);
        return roleId != 0 && m.getRoles().stream().anyMatch(r -> r.getIdLong() == roleId);
    }

    static String slug(String name) {
        String s = name.toLowerCase(Locale.ROOT).replace('ı', 'i').replace('ş', 's').replace('ğ', 'g')
                .replace('ü', 'u').replace('ö', 'o').replace('ç', 'c').replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (s.isEmpty()) s = "uye";
        return s.length() > 20 ? s.substring(0, 20) : s;
    }
}
