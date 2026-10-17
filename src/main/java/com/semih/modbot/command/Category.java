package com.semih.modbot.command;

/** Yardım menüsündeki kategoriler; sıra = sayfa sırası (kapak sayfası 1, sonra 2..9). */
public enum Category {
    CEZA("🔨", "Ceza"),
    UYARI("⚠️", "Uyarı"),
    KANAL("📢", "Kanal"),
    UYE("👤", "Üye"),
    YOUTUBE("📺", "YouTube & Kick"),
    ETKINLIK("🎉", "Etkinlik"),
    DESTEK("🎫", "Destek"),
    BILGI("⚙️", "Bilgi & Ayar");

    public final String emoji;
    public final String title;

    Category(String emoji, String title) {
        this.emoji = emoji;
        this.title = title;
    }

    /** Yardım menüsündeki sayfa numarası. */
    public int page() {
        return ordinal() + 2;
    }
}
