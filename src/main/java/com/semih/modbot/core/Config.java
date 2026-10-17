package com.semih.modbot.core;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** config.properties dosyasını okur. Türkçe karakterler için UTF-8 kullanır. */
public final class Config {

    private final Properties props;

    private Config(Properties props) {
        this.props = props;
    }

    public static Config load(Path path) throws IOException {
        if (!Files.exists(path)) {
            throw new IOException("Ayar dosyası bulunamadı: " + path.toAbsolutePath()
                    + "\n  -> config.example.properties dosyasını config.properties adıyla kopyalayıp doldur.");
        }
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        return new Config(props);
    }

    /** Değer yoksa ya da boşsa varsayılanı döner. */
    public String get(String key, String fallback) {
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    public String get(String key) {
        return get(key, "");
    }

    /** Discord ID'si okur. Boşsa ya da hatalıysa 0 döner (özellik kapalı demek). */
    public long id(String key) {
        String value = get(key);
        if (value.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseUnsignedLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + key + "' geçerli bir ID değil: " + value);
        }
    }

    public int integer(String key, int fallback) {
        String value = get(key);
        if (value.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + key + "' bir sayı olmalı: " + value);
        }
    }

    public boolean bool(String key, boolean fallback) {
        String value = get(key);
        if (value.isEmpty()) {
            return fallback;
        }
        return value.equalsIgnoreCase("true") || value.equalsIgnoreCase("evet") || value.equals("1");
    }

    /** Token önce DISCORD_TOKEN ortam değişkeninden, yoksa dosyadan okunur. */
    public String token() {
        String env = System.getenv("DISCORD_TOKEN");
        String token = (env != null && !env.isBlank()) ? env.trim() : get("token");
        if (token.isEmpty()) {
            throw new IllegalStateException("Token boş! config.properties içindeki 'token=' satırını doldur.");
        }
        return token;
    }
}
