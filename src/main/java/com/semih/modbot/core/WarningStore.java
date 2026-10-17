package com.semih.modbot.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Uyarıları data/uyarilar.tsv dosyasında saklar.
 * Her satır: sunucuId, kullanıcıId, yetkiliId, zaman, sebep (TAB ile ayrılmış).
 */
public final class WarningStore {

    public record Warning(long guildId, long userId, long moderatorId, Instant time, String reason) {
    }

    private final Path file;
    private final List<Warning> warnings = new ArrayList<>();

    public WarningStore(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        if (Files.exists(file)) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] p = line.split("\t", 5);
                if (p.length < 5) {
                    continue;
                }
                try {
                    warnings.add(new Warning(Long.parseLong(p[0]), Long.parseLong(p[1]), Long.parseLong(p[2]),
                            Instant.ofEpochMilli(Long.parseLong(p[3])), p[4]));
                } catch (NumberFormatException ignored) {
                    // bozuk satırı atla
                }
            }
        }
    }

    /** Uyarı ekler ve kişinin bu sunucudaki toplam uyarı sayısını döner. */
    public synchronized int add(long guildId, long userId, long moderatorId, String reason) {
        String clean = reason.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
        warnings.add(new Warning(guildId, userId, moderatorId, Instant.now(), clean));
        save();
        return list(guildId, userId).size();
    }

    public synchronized List<Warning> list(long guildId, long userId) {
        List<Warning> result = new ArrayList<>();
        for (Warning w : warnings) {
            if (w.guildId() == guildId && w.userId() == userId) {
                result.add(w);
            }
        }
        return result;
    }

    /** Tek bir uyarıyı siler (numara 1'den başlar). Başarılıysa true. */
    public synchronized boolean remove(long guildId, long userId, int number) {
        List<Warning> userWarnings = list(guildId, userId);
        if (number < 1 || number > userWarnings.size()) {
            return false;
        }
        warnings.remove(userWarnings.get(number - 1));
        save();
        return true;
    }

    /** Kişinin tüm uyarılarını siler, kaç tane silindiğini döner. */
    public synchronized int clear(long guildId, long userId) {
        int before = warnings.size();
        warnings.removeIf(w -> w.guildId() == guildId && w.userId() == userId);
        int removed = before - warnings.size();
        if (removed > 0) {
            save();
        }
        return removed;
    }

    private void save() {
        List<String> lines = new ArrayList<>();
        for (Warning w : warnings) {
            lines.add(w.guildId() + "\t" + w.userId() + "\t" + w.moderatorId() + "\t"
                    + w.time().toEpochMilli() + "\t" + w.reason());
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.write(tmp, lines, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
