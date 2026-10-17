package com.semih.modbot;

import com.semih.modbot.command.Command;
import com.semih.modbot.command.CommandListener;
import com.semih.modbot.command.HelpMenu;
import com.semih.modbot.core.Ayarlar;
import com.semih.modbot.core.Config;
import com.semih.modbot.core.Store;
import com.semih.modbot.core.Sure;
import com.semih.modbot.core.WarningStore;
import com.semih.modbot.features.Bilgi;
import com.semih.modbot.features.Destek;
import com.semih.modbot.features.Durum;
import com.semih.modbot.features.Etkinlik;
import com.semih.modbot.features.KickYayin;
import com.semih.modbot.kick.KickClient;
import com.semih.modbot.features.LogListener;
import com.semih.modbot.features.Moderasyon;
import com.semih.modbot.features.YouTube;
import com.semih.modbot.features.Yazdir;
import com.semih.modbot.net.DohResolver;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.exceptions.InvalidTokenException;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        Path configPath = Path.of(args.length > 0 ? args[0] : "config.properties").toAbsolutePath();
        Config config;
        String token;
        try {
            config = Config.load(configPath);
            token = config.token();
        } catch (Exception e) {
            log.error(e.getMessage());
            System.exit(1);
            return;
        }

        boolean secureDns = config.bool("dns.sifreli", true);
        DohResolver.setEnabled(secureDns);

        Path dataDir = configPath.getParent().resolve("data");
        Store store = new Store(dataDir.resolve("veri.properties"));
        WarningStore warnings = new WarningStore(dataDir.resolve("uyarilar.tsv"));
        Ayarlar ayarlar = new Ayarlar(store);

        String prefix = config.get("onek", "!");
        Duration penalty = Sure.parse(config.get("uyari.ceza", "1h"));
        if (penalty == null) penalty = Duration.ofHours(1);

        Moderasyon moderasyon = new Moderasyon(ayarlar, warnings, config.integer("uyari.limit", 3), penalty);
        YouTube youtube = new YouTube(ayarlar, config.integer("youtube.kontrol.dakika", 5));
        Etkinlik etkinlik = new Etkinlik(store);
        KickYayin kick = new KickYayin(ayarlar,
                new KickClient(config.get("kick.client.id"), config.get("kick.client.secret")),
                config.integer("kick.kontrol.saniye", 120));
        Destek destek = new Destek(ayarlar);
        Bilgi bilgi = new Bilgi(ayarlar);

        List<Command> commands = new ArrayList<>();
        commands.addAll(moderasyon.commands());
        commands.addAll(new Yazdir().commands());
        commands.addAll(youtube.commands());
        commands.addAll(kick.commands());
        commands.addAll(etkinlik.commands());
        commands.addAll(destek.commands());
        HelpMenu help = new HelpMenu(prefix, commands);
        commands.add(help.command());
        commands.addAll(bilgi.commands());
        commands.sort(Comparator.comparing(Command::category)); // aynı kategoridekiler yazıldığı sırada kalır

        List<Long> extraOwners = new ArrayList<>();
        for (String s : config.get("sahip.id").split(",")) {
            if (s.trim().matches("\\d{15,25}")) extraOwners.add(Long.parseLong(s.trim()));
        }
        CommandListener listener = new CommandListener(prefix, commands, extraOwners);

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "zamanlayici");
            t.setDaemon(true);
            return t;
        });

        JDA jda;
        try {
            jda = JDABuilder.createDefault(token)
                    .enableIntents(GatewayIntent.GUILD_MEMBERS, GatewayIntent.MESSAGE_CONTENT)
                    .setMemberCachePolicy(MemberCachePolicy.ALL)
                    .setChunkingFilter(ChunkingFilter.ALL)
                    .setActivity(Activity.customStatus("💬 " + prefix + "yardim"))
                    .addEventListeners(listener, help, new LogListener(ayarlar), bilgi, etkinlik, destek)
                    .build();
        } catch (InvalidTokenException e) {
            log.error("Token geçersiz! Developer Portal'dan yeni token alıp config.properties'e yapıştır.");
            System.exit(1);
            return;
        }
        try {
            jda.awaitReady();
        } catch (IllegalStateException e) {
            log.error("Discord'a bağlanamadı. Developer Portal > Bot sayfasında 'SERVER MEMBERS INTENT' ve "
                    + "'MESSAGE CONTENT INTENT' açık mı? İkisini de açıp tekrar dene.");
            System.exit(1);
            return;
        }

        log.info("==============================================");
        log.info(" {} çevrimiçi! {} sunucuda, {} komut.", jda.getSelfUser().getName(), jda.getGuilds().size(), commands.size());
        log.info(" Discord'da {}yardim yaz. Botu kapatmak için bu pencereyi kapat.", prefix);
        log.info("==============================================");
        if (jda.getGuilds().isEmpty()) {
            log.info("Bot henüz hiçbir sunucuda değil. Davet linki: https://discord.com/oauth2/authorize?client_id={}"
                    + "&scope=bot&permissions=8", jda.getSelfUser().getApplicationId());
        }

        youtube.start(jda, scheduler);
        etkinlik.start(jda, scheduler);
        kick.start(jda, scheduler);
        new Durum(config.get("durum.youtube", ""), prefix, config.integer("durum.saniye", 30))
                .start(jda, scheduler);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            scheduler.shutdownNow();
            jda.shutdown();
        }, "kapanis"));
    }
}
