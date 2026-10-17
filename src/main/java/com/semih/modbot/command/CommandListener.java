package com.semih.modbot.command;

import com.semih.modbot.core.Embeds;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.dv8tion.jda.api.entities.ApplicationInfo;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** "!komut" mesajlarını yakalar, sadece bot sahibinin komutlarını çalıştırır. */
public final class CommandListener extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(CommandListener.class);

    private final String prefix;
    private final Map<String, Command> byName = new LinkedHashMap<>();
    private final List<Command> commands;
    private final Set<Long> owners = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "komut");
        t.setDaemon(true);
        return t;
    });

    public CommandListener(String prefix, List<Command> commands, Collection<Long> extraOwners) {
        this.prefix = prefix;
        this.commands = commands;
        this.owners.addAll(extraOwners);
        for (Command c : commands) {
            byName.put(normalize(c.name()), c);
            for (String a : c.aliases()) byName.putIfAbsent(normalize(a), c);
        }
    }

    public List<Command> commands() {
        return commands;
    }

    public String prefix() {
        return prefix;
    }

    public boolean isOwner(long userId) {
        return owners.contains(userId);
    }

    /** Türkçe karakterli yazılış da çalışsın: !uyarı = !uyari */
    static String normalize(String s) {
        return s.toLowerCase(java.util.Locale.ROOT)
                .replace('ı', 'i').replace('ş', 's').replace('ğ', 'g')
                .replace('ü', 'u').replace('ö', 'o').replace('ç', 'c').replace('İ', 'i')
                .replace("i̇", "i");
    }

    @Override
    public void onReady(ReadyEvent event) {
        // Bot sahibini Discord'dan öğren (Developer Portal'daki uygulama sahibi ya da takım üyeleri)
        event.getJDA().retrieveApplicationInfo().queue((ApplicationInfo info) -> {
            if (info.getTeam() != null) {
                info.getTeam().getMembers().forEach(m -> owners.add(m.getUser().getIdLong()));
            } else {
                owners.add(info.getOwner().getIdLong());
            }
            log.info("Bot sahibi: {} — komutları sadece sahip kullanabilir.",
                    info.getTeam() != null ? "takım üyeleri" : info.getOwner().getName());
        }, err -> log.error("Bot sahibi öğrenilemedi: {}", err.getMessage()));
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (!event.isFromGuild() || event.getAuthor().isBot() || event.isWebhookMessage()) return;
        Message message = event.getMessage();
        String content = message.getContentRaw();
        if (!content.startsWith(prefix) || content.length() <= prefix.length()) return;

        String body = content.substring(prefix.length());
        int space = indexOfWhitespace(body);
        String label = space < 0 ? body : body.substring(0, space);
        String argText = space < 0 ? "" : body.substring(space);
        Command command = byName.get(normalize(label));
        boolean owner = owners.contains(event.getAuthor().getIdLong());

        if (command == null) {
            if (owner && !label.isBlank() && Character.isLetter(label.charAt(0))) {
                message.replyEmbeds(Embeds.hata("Böyle bir komut yok. Tüm komutlar için `" + prefix + "yardim` yaz."))
                        .mentionRepliedUser(false).queue(m -> m.delete().queueAfter(8, TimeUnit.SECONDS, null, x -> { }));
            }
            return;
        }
        if (!owner) {
            message.replyEmbeds(Embeds.hata("Komutları sadece bot sahibi kullanabilir."))
                    .mentionRepliedUser(false)
                    .queue(m -> m.delete().queueAfter(5, TimeUnit.SECONDS, null, x -> { }), x -> { });
            return;
        }

        Ctx ctx = new Ctx(message, command, prefix, label, argText);
        executor.execute(() -> {
            try {
                command.handler().run(ctx);
            } catch (Ctx.UserError e) {
                ctx.reply(Embeds.hata(e.getMessage()));
            } catch (Exception e) {
                log.warn("{}{} komutunda hata: {}", prefix, command.name(), e.toString());
                ctx.reply(Embeds.hata(Yetki.hata(e)));
            }
        });
    }

    private static int indexOfWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) return i;
        }
        return -1;
    }
}
