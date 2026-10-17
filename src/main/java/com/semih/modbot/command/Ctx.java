package com.semih.modbot.command;

import com.semih.modbot.core.Embeds;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;

/** Bir komutun çalıştığı ortam: kim yazdı, nerede, hangi argümanlarla. */
public final class Ctx {

    /** Kullanıcıya gösterilecek hata. Komutu durdurur. */
    public static final class UserError extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public UserError(String message) {
            super(message);
        }
    }

    private static final Pattern ID = Pattern.compile("^<?[@#]?[!&]?(\\d{15,25})>?$");
    private static final Pattern TOKEN = Pattern.compile("\\S+");

    public final Message message;
    public final Guild guild;
    public final Member member;
    public final GuildMessageChannel channel;
    public final Command command;
    public final String prefix;
    public final String label;

    private final String argText;
    private final List<String> args = new ArrayList<>();
    private final List<Integer> starts = new ArrayList<>();

    public Ctx(Message message, Command command, String prefix, String label, String argText) {
        this.message = message;
        this.guild = message.getGuild();
        this.member = message.getMember();
        this.channel = message.getGuildChannel();
        this.command = command;
        this.prefix = prefix;
        this.label = label;
        this.argText = argText;
        Matcher m = TOKEN.matcher(argText);
        while (m.find()) {
            args.add(m.group());
            starts.add(m.start());
        }
    }

    public JDA jda() {
        return guild.getJDA();
    }

    public int size() {
        return args.size();
    }

    /** i. argüman, yoksa null. */
    public String arg(int i) {
        return i < args.size() ? args.get(i) : null;
    }

    /** i. argümandan sonuna kadar olan yazı (boşluklar korunur), yoksa "". */
    public String rest(int i) {
        return i < starts.size() ? argText.substring(starts.get(i)).strip() : "";
    }

    public String usage() {
        return "`" + prefix + command.name() + (command.usage().isEmpty() ? "" : " " + command.usage()) + "`";
    }

    /** Argüman eksikse kullanımı gösterip durdurur. */
    public void need(int count) {
        if (args.size() < count) {
            throw new UserError("Eksik bilgi. Kullanım: " + usage());
        }
    }

    // ---- Çözümleyiciler ----

    public static long idOf(String text) {
        if (text == null) return 0;
        Matcher m = ID.matcher(text.trim());
        if (!m.matches()) return 0;
        try {
            return Long.parseUnsignedLong(m.group(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** @üye ya da ID; sunucuda değilse null. */
    public Member memberArg(int i) {
        long id = idOf(arg(i));
        if (id == 0) return null;
        Member cached = guild.getMemberById(id);
        if (cached != null) return cached;
        try {
            return guild.retrieveMemberById(id).complete();
        } catch (ErrorResponseException e) {
            return null;
        }
    }

    /** @üye ya da ID zorunlu. */
    public Member needMember(int i) {
        need(i + 1);
        Member m = memberArg(i);
        if (m == null) throw new UserError("Bu kişiyi sunucuda bulamadım: `" + arg(i) + "`");
        return m;
    }

    /** Sunucuda olmayan biri de olabilir (ban, unban için). */
    public User needUser(int i) {
        need(i + 1);
        long id = idOf(arg(i));
        if (id == 0) throw new UserError("Geçerli bir kullanıcı etiketle ya da ID yaz. Kullanım: " + usage());
        try {
            return jda().retrieveUserById(id).complete();
        } catch (ErrorResponseException e) {
            throw new UserError("Böyle bir kullanıcı yok: `" + id + "`");
        }
    }

    /** @rol, rol ID'si ya da rol adı (i'den sonuna kadar). */
    public Role needRole(int i) {
        need(i + 1);
        long id = idOf(arg(i));
        Role role = id != 0 ? guild.getRoleById(id) : null;
        if (role == null) {
            String name = rest(i);
            List<Role> byName = guild.getRolesByName(name, true);
            if (!byName.isEmpty()) role = byName.get(0);
        }
        if (role == null) throw new UserError("Bu rolü bulamadım: `" + rest(i) + "`");
        return role;
    }

    /** #kanal ya da ID; yoksa null. */
    public GuildChannel channelArg(int i) {
        long id = idOf(arg(i));
        return id == 0 ? null : guild.getGuildChannelById(id);
    }

    public GuildMessageChannel textChannelArg(int i) {
        long id = idOf(arg(i));
        return id == 0 ? null : guild.getChannelById(GuildMessageChannel.class, id);
    }

    // ---- Yetki kontrolleri ----

    public void botNeeds(Permission... permissions) {
        Member self = guild.getSelfMember();
        for (Permission p : permissions) {
            if (!self.hasPermission(channel, p)) {
                throw new UserError("Bunu yapabilmem için bana **" + Yetki.ad(p) + "** yetkisi vermelisin.");
            }
        }
    }

    /** Hedef kişiye işlem yapılabilir mi? */
    public void canTarget(Member target) {
        if (target.getIdLong() == member.getIdLong()) throw new UserError("Bunu kendine yapamazsın.");
        if (target.getIdLong() == jda().getSelfUser().getIdLong()) throw new UserError("Bunu bana yapamazsın 😅");
        if (target.isOwner()) throw new UserError("Sunucu sahibine bunu yapamazsın.");
        if (!guild.getSelfMember().canInteract(target)) {
            throw new UserError("**" + target.getEffectiveName() + "** kişisinin rolü benim rolümden yüksek. "
                    + "Sunucu Ayarları → Roller'den bot rolünü yukarı taşı.");
        }
    }

    // ---- Cevaplar ----

    public void reply(MessageEmbed embed) {
        channel.sendMessageEmbeds(embed).setMessageReference(message).mentionRepliedUser(false)
                .setAllowedMentions(List.of()).queue(null, e -> channel.sendMessageEmbeds(embed).queue());
    }

    public void ok(String text) {
        reply(Embeds.basari(text));
    }

    public void info(int color, String text) {
        reply(Embeds.renkli(color, text));
    }
}
