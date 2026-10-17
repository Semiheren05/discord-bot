package com.semih.modbot.command;

import java.util.List;

/**
 * Bir ! komutu.
 *
 * @param name        komut adı (ön eksiz)
 * @param aliases     diğer yazılışları
 * @param category    yardım menüsündeki kategori
 * @param usage       kullanım, örn. "<@üye> <süre> [sebep]"
 * @param description kısa açıklama
 * @param handler     çalıştırılacak kod
 */
public record Command(String name, List<String> aliases, Category category, String usage, String description,
                      Handler handler) {

    @FunctionalInterface
    public interface Handler {
        void run(Ctx ctx) throws Exception;
    }

    public static Command of(String name, Category category, String usage, String description, Handler handler,
                             String... aliases) {
        return new Command(name, List.of(aliases), category, usage, description, handler);
    }
}
