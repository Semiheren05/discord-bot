package com.semih.modbot.features;

import com.semih.modbot.command.Category;
import com.semih.modbot.command.Command;
import com.semih.modbot.command.Ctx;
import com.semih.modbot.core.Embeds;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;

/**
 * !yazdir #kanal [başlık] + .txt dosyası → dosyanın içeriğini o kanala yardım menüsündeki gibi
 * renkli çerçeveli (embed) mesaj olarak yazar. Kurallar, duyurular, bilgi metinleri için.
 */
public final class Yazdir {

    static final int MAX_DESC = 4000;          // Discord sınırı 4096
    private static final int MAX_FILE = 200_000;

    public List<Command> commands() {
        return List.of(Command.of("yazdir", Category.KANAL, "<#kanal> [başlık] + .txt dosyası",
                "Eklediğin .txt dosyasını (ya da yazdığın metni) kanala çerçeveli mesaj olarak yazar. "
                        + "Dosyanın ilk satırı `# Başlık` ise başlık olur; uzunsa birkaç parçaya bölünür.",
                this::yazdir, "yaz", "metin", "duyuru"));
    }

    private void yazdir(Ctx ctx) throws Exception {
        ctx.need(1);
        GuildMessageChannel target = ctx.textChannelArg(0);
        if (target == null) throw new Ctx.UserError("Yazılacak kanalı etiketle. Kullanım: " + ctx.usage());
        Member self = ctx.guild.getSelfMember();
        if (!self.hasPermission(target, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS)) {
            throw new Ctx.UserError(target.getAsMention() + " kanalına yazamıyorum. Bana **Mesaj Gönder** ve **Bağlantı Yerleştir** izni ver.");
        }

        String title = null;
        String text;
        Message.Attachment file = ctx.message.getAttachments().stream()
                .filter(a -> {
                    String ext = a.getFileExtension();
                    return ext == null || ext.equalsIgnoreCase("txt") || ext.equalsIgnoreCase("md");
                })
                .findFirst().orElse(null);
        if (file != null) {
            if (file.getSize() > MAX_FILE) throw new Ctx.UserError("Dosya çok büyük (en fazla 200 KB).");
            byte[] data;
            try (InputStream in = file.getProxy().download().get(30, TimeUnit.SECONDS)) {
                data = in.readAllBytes();
            }
            text = decode(data);
            String t = ctx.rest(1);
            if (!t.isEmpty()) title = t;
        } else if (!ctx.message.getAttachments().isEmpty()) {
            throw new Ctx.UserError("Sadece .txt (ya da .md) dosyası ekleyebilirsin.");
        } else {
            text = ctx.rest(1);
            if (text.isEmpty()) {
                throw new Ctx.UserError("Mesaja bir .txt dosyası ekle ya da komuttan sonra metni yaz.\n"
                        + "Örnek: `" + ctx.prefix + "yazdir #kurallar Sunucu Kuralları` + kurallar.txt");
            }
        }

        text = text.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (title == null && text.startsWith("# ")) {
            int nl = text.indexOf('\n');
            title = (nl < 0 ? text.substring(2) : text.substring(2, nl)).strip();
            text = nl < 0 ? "" : text.substring(nl + 1).strip();
        }
        if (title != null && title.length() > 256) title = title.substring(0, 255) + "…";
        if (text.isEmpty() && title == null) throw new Ctx.UserError("Dosya boş.");

        List<String> parts = split(text, MAX_DESC);
        if (parts.isEmpty()) parts = List.of("​");
        if (parts.size() > 20) throw new Ctx.UserError("Metin çok uzun (" + parts.size() + " parça). En fazla 20 parça yazabilirim.");
        for (int i = 0; i < parts.size(); i++) {
            EmbedBuilder eb = new EmbedBuilder().setColor(Embeds.ANA).setDescription(parts.get(i));
            if (i == 0 && title != null) eb.setTitle(title);
            if (i == parts.size() - 1) eb.setFooter(ctx.guild.getName(), ctx.guild.getIconUrl());
            target.sendMessageEmbeds(eb.build()).complete();
        }
        if (target.getIdLong() != ctx.channel.getIdLong()) {
            ctx.ok(target.getAsMention() + " kanalına yazıldı" + (parts.size() > 1 ? " (" + parts.size() + " parça)." : "."));
        } else {
            ctx.message.delete().queue(null, x -> { });
        }
    }

    /** Önce UTF-8 dener; olmazsa Windows Türkçe (Not Defteri'nin eski kaydetme biçimi). */
    static String decode(byte[] data) {
        int off = (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) ? 3 : 0;
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE) {
            return new String(data, 2, data.length - 2, StandardCharsets.UTF_16LE);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data, off, data.length - off)).toString();
        } catch (CharacterCodingException e) {
            return new String(data, Charset.forName("windows-1254"));
        }
    }

    /** Paragraf > satır > kelime sınırından böler. */
    static List<String> split(String text, int max) {
        List<String> parts = new ArrayList<>();
        String rest = text.strip();
        while (!rest.isEmpty()) {
            if (rest.length() <= max) {
                parts.add(rest);
                break;
            }
            int cut = rest.lastIndexOf("\n\n", max);
            if (cut < max / 3) cut = rest.lastIndexOf('\n', max);
            if (cut < max / 3) cut = rest.lastIndexOf(' ', max);
            if (cut < max / 3) cut = max;
            parts.add(rest.substring(0, cut).strip());
            rest = rest.substring(cut).strip();
        }
        return parts;
    }
}
