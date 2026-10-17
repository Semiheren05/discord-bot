package com.semih.modbot.command;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;

/** Discord yetki adlarının ve hatalarının Türkçesi. */
public final class Yetki {

    private Yetki() {
    }

    public static String ad(Permission p) {
        return switch (p) {
            case BAN_MEMBERS -> "Üyeleri Yasakla";
            case KICK_MEMBERS -> "Üyeleri At";
            case MODERATE_MEMBERS -> "Üyelere Zaman Aşımı Uygula";
            case MESSAGE_MANAGE -> "Mesajları Yönet";
            case MESSAGE_HISTORY -> "Mesaj Geçmişini Oku";
            case MANAGE_CHANNEL -> "Kanalları Yönet";
            case MANAGE_PERMISSIONS -> "İzinleri Yönet";
            case MANAGE_ROLES -> "Rolleri Yönet";
            case NICKNAME_MANAGE -> "Kullanıcı Adlarını Yönet";
            case MESSAGE_SEND -> "Mesaj Gönder";
            case MESSAGE_EMBED_LINKS -> "Bağlantı Yerleştir";
            case MESSAGE_ATTACH_FILES -> "Dosya Ekle";
            case MESSAGE_MENTION_EVERYONE -> "@everyone, @here ve Tüm Rollerden Bahset";
            case VIEW_CHANNEL -> "Kanalı Görüntüle";
            default -> p.getName();
        };
    }

    /** Discord hatalarını anlaşılır Türkçeye çevirir. */
    public static String hata(Throwable err) {
        Throwable t = err;
        while (t.getCause() != null && !(t instanceof ErrorResponseException)) t = t.getCause();
        if (t instanceof ErrorResponseException e) {
            return switch (e.getErrorResponse()) {
                case MISSING_PERMISSIONS, MISSING_ACCESS -> "Bunun için yetkim yok (rolüm hedefin altında ya da izin eksik).";
                case UNKNOWN_BAN -> "Bu kullanıcı yasaklı değil.";
                case UNKNOWN_USER -> "Böyle bir kullanıcı bulunamadı.";
                case UNKNOWN_MEMBER -> "Bu kişi sunucuda değil.";
                case UNKNOWN_MESSAGE -> "Mesaj bulunamadı (silinmiş olabilir).";
                case UNKNOWN_CHANNEL -> "Kanal bulunamadı.";
                default -> e.getMeaning();
            };
        }
        return err.getMessage() == null ? err.toString() : err.getMessage();
    }
}
