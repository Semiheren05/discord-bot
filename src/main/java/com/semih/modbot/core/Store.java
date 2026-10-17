package com.semih.modbot.core;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Bot kapanıp açılsa da kaybolmaması gereken bilgiler (ayarlar, çekilişler, etkinlikler...).
 * data/veri.properties dosyasına yazılır.
 */
public final class Store {

    private final Path file;
    private final Properties props = new Properties();

    public Store(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                props.load(reader);
            }
        }
    }

    public synchronized String get(String key) {
        return props.getProperty(key, "");
    }

    public synchronized long getLong(String key) {
        try {
            String v = props.getProperty(key, "");
            return v.isEmpty() ? 0L : Long.parseLong(v);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    public synchronized void set(String key, String value) {
        if (value == null || value.isEmpty()) {
            props.remove(key);
        } else {
            props.setProperty(key, value);
        }
        save();
    }

    public synchronized void set(String key, long value) {
        set(key, String.valueOf(value));
    }

    /** Bu önekle başlayan tüm anahtarları siler. */
    public synchronized void removePrefix(String prefix) {
        props.keySet().removeIf(k -> k.toString().startsWith(prefix));
        save();
    }

    /** Bu önekle başlayan anahtarlar. */
    public synchronized List<String> keys(String prefix) {
        List<String> list = new ArrayList<>();
        for (Object k : props.keySet()) {
            if (k.toString().startsWith(prefix)) list.add(k.toString());
        }
        return list;
    }

    /** Virgülle ayrılmış listeye ekler; zaten varsa false. */
    public synchronized boolean addToList(String key, String item) {
        List<String> list = list(key);
        if (list.contains(item)) return false;
        list.add(item);
        set(key, String.join(",", list));
        return true;
    }

    public synchronized boolean removeFromList(String key, String item) {
        List<String> list = list(key);
        boolean removed = list.remove(item);
        if (removed) set(key, String.join(",", list));
        return removed;
    }

    public synchronized List<String> list(String key) {
        List<String> list = new ArrayList<>();
        for (String s : get(key).split(",")) {
            if (!s.isBlank()) list.add(s.trim());
        }
        return list;
    }

    private void save() {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            props.store(writer, "ModBot verileri - elle duzenleme");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                // Windows'ta ATOMIC_MOVE bazen desteklenmez
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e2) {
                throw new UncheckedIOException(e2);
            }
        }
    }
}
