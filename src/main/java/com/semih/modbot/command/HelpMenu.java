package com.semih.modbot.command;

import com.semih.modbot.core.Embeds;
import java.util.List;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

/**
 * Sayfalı yardım menüsü: 1. sayfa kategoriler, sonraki sayfalar her kategorinin komutları.
 * Butonlar: ⏮ ◀ ▶ ⏭ ✖ — sadece menüyü açan kişi kullanabilir.
 */
public final class HelpMenu extends ListenerAdapter {

    private static final String ID = "yardim";

    private final String prefix;
    private final List<Command> commands;

    public HelpMenu(String prefix, List<Command> commands) {
        this.prefix = prefix;
        this.commands = commands;
    }

    public static int pageCount() {
        return Category.values().length + 1;
    }

    /** !yardim [kategori|sayfa] */
    public Command command() {
        return Command.of("yardim", Category.BILGI, "[kategori|sayfa]", "Bu menüyü açar", ctx -> {
            int page = 1;
            String a = ctx.arg(0);
            if (a != null) {
                page = pageFor(a);
                if (page == 0) throw new Ctx.UserError("Böyle bir kategori yok. Kategoriler: "
                        + String.join(", ", java.util.Arrays.stream(Category.values()).map(c -> c.title).toList()));
            }
            ctx.channel.sendMessageEmbeds(page(page))
                    .setComponents(buttons(ctx.member.getIdLong(), page))
                    .setMessageReference(ctx.message).mentionRepliedUser(false)
                    .queue();
        }, "help", "yardım", "komutlar");
    }

    int pageFor(String text) {
        try {
            int n = Integer.parseInt(text);
            return n >= 1 && n <= pageCount() ? n : 0;
        } catch (NumberFormatException ignored) {
            // kategori adı
        }
        String t = CommandListener.normalize(text);
        for (Category c : Category.values()) {
            String title = CommandListener.normalize(c.title);
            if (title.startsWith(t) || CommandListener.normalize(c.name()).equals(t)) return c.page();
        }
        return 0;
    }

    public MessageEmbed page(int page) {
        EmbedBuilder eb = new EmbedBuilder().setColor(Embeds.ANA);
        if (page <= 1) {
            eb.setTitle("🛡️ Moderasyon Botu — Yardım");
            StringBuilder sb = new StringBuilder();
            sb.append("Ön ek: `").append(prefix).append("`\nKomutları sadece bot sahibi kullanabilir.\n\n");
            sb.append("**Kategoriler**\n");
            for (Category c : Category.values()) {
                long count = commands.stream().filter(x -> x.category() == c).count();
                sb.append(c.emoji).append(" **").append(c.title).append("** — ").append(count)
                        .append(" komut *(sayfa ").append(c.page()).append(")*\n");
            }
            sb.append("\n**Süre örnekleri**\n");
            sb.append("`30s` saniye • `10m` dakika • `2h` saat • `1d` gün • `1w` hafta • `1d12h` birleşik\n\n");
            sb.append("**Kullanım işaretleri**\n");
            sb.append("`<...>` zorunlu • `[...]` isteğe bağlı");
            eb.setDescription(sb.toString());
        } else {
            Category c = Category.values()[Math.min(page, pageCount()) - 2];
            eb.setTitle(c.emoji + " " + c.title + " komutları");
            StringBuilder sb = new StringBuilder();
            for (Command cmd : commands) {
                if (cmd.category() != c) continue;
                sb.append("`").append(prefix).append(cmd.name());
                if (!cmd.usage().isEmpty()) sb.append(' ').append(cmd.usage());
                sb.append("`\n").append(cmd.description());
                if (!cmd.aliases().isEmpty()) {
                    sb.append(" *(diğer: ").append(String.join(", ", cmd.aliases().stream()
                            .map(x -> prefix + x).toList())).append(")*");
                }
                sb.append("\n\n");
            }
            eb.setDescription(sb.toString().strip());
        }
        eb.setFooter("Sayfa " + Math.min(Math.max(page, 1), pageCount()) + "/" + pageCount() + " • Butonlarla sayfa değiştir");
        return eb.build();
    }

    public ActionRow buttons(long userId, int page) {
        int last = pageCount();
        String base = ID + ":" + userId + ":";
        return ActionRow.of(
                Button.secondary(base + 1 + ":ilk", Emoji.fromUnicode("⏮️")).withDisabled(page <= 1),
                Button.primary(base + Math.max(1, page - 1) + ":geri", Emoji.fromUnicode("◀️")).withDisabled(page <= 1),
                Button.primary(base + Math.min(last, page + 1) + ":ileri", Emoji.fromUnicode("▶️")).withDisabled(page >= last),
                Button.secondary(base + last + ":son", Emoji.fromUnicode("⏭️")).withDisabled(page >= last),
                Button.danger(base + "kapat", Emoji.fromUnicode("✖️")));
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith(ID + ":")) return;
        String[] p = id.split(":");
        if (p.length < 3) return;
        if (!p[1].equals(event.getUser().getId())) {
            event.replyEmbeds(Embeds.hata("Bu menüyü sadece açan kişi kullanabilir. Kendi menün için `" + prefix + "yardim` yaz."))
                    .setEphemeral(true).queue();
            return;
        }
        if (p[2].equals("kapat")) {
            event.deferEdit().queue();
            event.getMessage().delete().queue(null, x -> { });
            return;
        }
        int page;
        try {
            page = Integer.parseInt(p[2]);
        } catch (NumberFormatException e) {
            return;
        }
        event.editMessageEmbeds(page(page)).setComponents(buttons(event.getUser().getIdLong(), page)).queue();
    }
}
