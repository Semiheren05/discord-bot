package com.semih.modbot.features;

import com.semih.modbot.core.Ayarlar;
import com.semih.modbot.core.Embeds;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.events.message.MessageBulkDeleteEvent;
import net.dv8tion.jda.api.events.message.MessageDeleteEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.MessageUpdateEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.utils.TimeFormat;

/**
 * Log kanalına yazar: silinen ve düzenlenen mesajlar, sunucuya giren ve çıkan üyeler.
 * Discord silinen mesajın içeriğini göndermediği için son mesajları hafızada tutarız.
 */
public final class LogListener extends ListenerAdapter {

    private static final int CACHE_SIZE = 5000;

    private record CachedMessage(long authorId, String authorName, String avatarUrl, String content,
                                 List<String> attachments) {
    }

    private final Ayarlar modLog;
    private final Map<Long, CachedMessage> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, CachedMessage> eldest) {
                    return size() > CACHE_SIZE;
                }
            });

    public LogListener(Ayarlar modLog) {
        this.modLog = modLog;
    }

    /** Bot kendisi bir mesajı sildiğinde (örn. davet linki) ayrıca "silindi" logu düşmesin diye. */
    public void forget(long messageId) {
        cache.remove(messageId);
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (!event.isFromGuild() || event.getAuthor().isBot() || event.isWebhookMessage()) {
            return;
        }
        cache.put(event.getMessageIdLong(), snapshot(event.getMessage()));
    }

    @Override
    public void onMessageUpdate(MessageUpdateEvent event) {
        if (!event.isFromGuild() || event.getAuthor().isBot() || modLog.isLogChannel(event.getGuild(), event.getChannel().getIdLong())) {
            return;
        }
        Message message = event.getMessage();
        CachedMessage old = cache.get(message.getIdLong());
        String newContent = message.getContentRaw();
        if (old != null && old.content().equals(newContent)) {
            return; // içerik değişmemiş (link önizlemesi yüklenince de bu olay gelir)
        }
        cache.put(message.getIdLong(), snapshot(message));

        User author = event.getAuthor();
        String before = old != null ? old.content() : "*(bilinmiyor — mesaj bot açılmadan önce atılmış)*";
        modLog.log(event.getGuild(), Embeds.log(Embeds.BILGI, "✏️ Mesaj düzenlendi")
                .setAuthor(author.getName(), null, author.getEffectiveAvatarUrl())
                .setDescription(author.getAsMention() + " • " + event.getChannel().getAsMention()
                        + " • [Mesaja git](" + message.getJumpUrl() + ")")
                .addField("Önce", Embeds.kisalt(before, 1024), false)
                .addField("Sonra", Embeds.kisalt(newContent, 1024), false)
                .setFooter("Kullanıcı ID: " + author.getId())
                .build());
    }

    @Override
    public void onMessageDelete(MessageDeleteEvent event) {
        if (!event.isFromGuild() || modLog.isLogChannel(event.getGuild(), event.getChannel().getIdLong())) {
            return;
        }
        CachedMessage cached = cache.remove(event.getMessageIdLong());
        if (cached == null) {
            return; // içeriğini bilmediğimiz mesajları (bot mesajları, eski mesajlar) loglamıyoruz
        }
        EmbedBuilder eb = Embeds.log(Embeds.HATA, "🗑️ Mesaj silindi")
                .setAuthor(cached.authorName(), null, cached.avatarUrl())
                .setDescription("<@" + cached.authorId() + "> • " + event.getChannel().getAsMention())
                .addField("İçerik", Embeds.kisalt(cached.content(), 1024), false)
                .setFooter("Kullanıcı ID: " + cached.authorId());
        if (!cached.attachments().isEmpty()) {
            eb.addField("Ekler", Embeds.kisalt(String.join("\n", cached.attachments()), 1024), false);
        }
        modLog.log(event.getGuild(), eb.build());
    }

    @Override
    public void onMessageBulkDelete(MessageBulkDeleteEvent event) {
        // /temizle zaten kendi logunu atıyor, burada sadece hafızayı temizliyoruz
        for (String id : event.getMessageIds()) {
            cache.remove(Long.parseLong(id));
        }
    }

    @Override
    public void onGuildMemberJoin(GuildMemberJoinEvent event) {
        User user = event.getUser();
        OffsetDateTime created = user.getTimeCreated();
        boolean newAccount = created.isAfter(OffsetDateTime.now().minusDays(7));
        modLog.log(event.getGuild(), Embeds.log(Embeds.BASARI, "📥 Üye katıldı")
                .setThumbnail(user.getEffectiveAvatarUrl())
                .setDescription(user.getAsMention() + " (`" + user.getName() + "`)"
                        + (user.isBot() ? " 🤖" : ""))
                .addField("Hesap açılışı", TimeFormat.RELATIVE.format(created)
                        + (newAccount ? "\n⚠️ **Yeni hesap**" : ""), true)
                .addField("Üye sayısı", String.valueOf(event.getGuild().getMemberCount()), true)
                .setFooter("Kullanıcı ID: " + user.getId())
                .build());
    }

    @Override
    public void onGuildMemberRemove(GuildMemberRemoveEvent event) {
        User user = event.getUser();
        Member member = event.getMember();
        EmbedBuilder eb = Embeds.log(Embeds.GRI, "📤 Üye ayrıldı")
                .setThumbnail(user.getEffectiveAvatarUrl())
                .setDescription(user.getAsMention() + " (`" + user.getName() + "`)")
                .setFooter("Kullanıcı ID: " + user.getId());
        if (member != null) {
            eb.addField("Katılmıştı", TimeFormat.RELATIVE.format(member.getTimeJoined()), true);
            String roles = member.getRoles().stream().map(Role::getAsMention).collect(Collectors.joining(" "));
            if (!roles.isEmpty()) {
                eb.addField("Rolleri", Embeds.kisalt(roles, 1024), false);
            }
        }
        modLog.log(event.getGuild(), eb.build());
    }

    private static CachedMessage snapshot(Message message) {
        List<String> attachments = new ArrayList<>();
        for (Message.Attachment a : message.getAttachments()) {
            attachments.add("[" + a.getFileName() + "](" + a.getUrl() + ")");
        }
        User author = message.getAuthor();
        return new CachedMessage(author.getIdLong(), author.getName(), author.getEffectiveAvatarUrl(),
                message.getContentRaw(), attachments);
    }
}
