package com.semih.modbot.net;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.spi.InetAddressResolver;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Şifreli DNS (DNS over HTTPS).
 *
 * Bazı internet sağlayıcıları Discord'u DNS ile engelliyor: "discord.com nerede?" sorusuna
 * yanlış adres veriyorlar. Bu sınıf botun tüm adres sorgularını Cloudflare'in (1.1.1.1)
 * şifreli DNS'ine sorar. Sadece bu Java programını etkiler, sunucunun DNS ayarlarına dokunmaz.
 * Şifreli DNS'e ulaşılamazsa normal DNS'e geri döner.
 */
public final class DohResolver implements InetAddressResolver {

    /** Sırayla denenir. IP adresiyle yazıldıkları için kendileri DNS sorgusu gerektirmez. */
    private static final List<String> ENDPOINTS = List.of(
            "https://1.1.1.1/dns-query?type=A&name=",
            "https://1.0.0.1/dns-query?type=A&name=",
            "https://8.8.8.8/resolve?type=A&name=");

    private static final Pattern OBJECT = Pattern.compile("\\{[^{}]*\\}");
    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");

    private static volatile boolean enabled = true;
    /** Testlerde gerçek internete çıkmadan denemek için değiştirilebilir. */
    static volatile Function<String, String> fetcher = DohResolver::httpGet;

    private static final ThreadLocal<Boolean> BUSY = ThreadLocal.withInitial(() -> false);
    private static final AtomicBoolean WARNED = new AtomicBoolean(false);

    private record Cached(InetAddress[] addresses, long expiresAt) {
    }

    record Answer(int status, List<String> ips, int ttl) {
    }

    private final InetAddressResolver builtin;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    DohResolver(InetAddressResolver builtin) {
        this.builtin = builtin;
    }

    /** config.properties > dns.sifreli=false ile kapatılabilir. */
    public static void setEnabled(boolean value) {
        enabled = value;
    }

    @Override
    public Stream<InetAddress> lookupByName(String host, LookupPolicy policy) throws UnknownHostException {
        boolean wantsIpv4 = (policy.characteristics() & LookupPolicy.IPV4) != 0;
        if (!enabled || BUSY.get() || !wantsIpv4 || isLocal(host)) {
            return builtin.lookupByName(host, policy);
        }
        String key = host.toLowerCase(Locale.ROOT);
        Cached cached = cache.get(key);
        if (cached != null && cached.expiresAt() > System.currentTimeMillis()) {
            return Arrays.stream(cached.addresses());
        }

        BUSY.set(true);
        try {
            for (String endpoint : ENDPOINTS) {
                String json;
                try {
                    json = fetcher.apply(endpoint + URLEncoder.encode(key, StandardCharsets.UTF_8));
                } catch (RuntimeException e) {
                    json = null;
                }
                if (json == null) {
                    continue; // bu sunucuya ulaşılamadı, sıradakini dene
                }
                Answer answer = parse(json);
                if (answer.status() == 3) {
                    throw new UnknownHostException(host + " (böyle bir adres yok)");
                }
                if (answer.status() != 0) {
                    continue;
                }
                if (answer.ips().isEmpty()) {
                    break; // IPv4 kaydı yok, normal DNS'e bırak
                }
                InetAddress[] addresses = new InetAddress[answer.ips().size()];
                for (int i = 0; i < addresses.length; i++) {
                    addresses[i] = InetAddress.getByAddress(host, toBytes(answer.ips().get(i)));
                }
                long ttlMillis = Math.max(30, Math.min(answer.ttl(), 3600)) * 1000L;
                cache.put(key, new Cached(addresses, System.currentTimeMillis() + ttlMillis));
                return Arrays.stream(addresses);
            }
        } finally {
            BUSY.set(false);
        }

        if (WARNED.compareAndSet(false, true)) {
            System.err.println("[ModBot] Şifreli DNS'e ulaşılamadı, normal DNS kullanılıyor (" + host + ")");
        }
        return builtin.lookupByName(host, policy);
    }

    @Override
    public String lookupByAddress(byte[] addr) throws UnknownHostException {
        return builtin.lookupByAddress(addr);
    }

    private static boolean isLocal(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return !h.contains(".") || h.equals("localhost") || h.endsWith(".local") || h.endsWith(".localdomain")
                || h.endsWith(".lan") || h.endsWith(".home") || h.endsWith(".internal");
    }

    /** DoH JSON cevabını okur: {"Status":0, "Answer":[{"type":1,"TTL":300,"data":"1.2.3.4"}, ...]} */
    static Answer parse(String json) {
        int status = intField(json, "Status", -1);
        List<String> ips = new ArrayList<>();
        int ttl = Integer.MAX_VALUE;
        int start = json.indexOf("\"Answer\"");
        if (start >= 0) {
            Matcher m = OBJECT.matcher(json);
            m.region(start, json.length());
            while (m.find()) {
                String obj = m.group();
                if (intField(obj, "type", -1) != 1) {
                    continue; // sadece A (IPv4) kayıtları; CNAME vb. atla
                }
                String data = stringField(obj, "data");
                if (data != null && IPV4.matcher(data).matches()) {
                    ips.add(data);
                    ttl = Math.min(ttl, intField(obj, "TTL", 300));
                }
            }
        }
        return new Answer(status, ips, ips.isEmpty() ? 0 : ttl);
    }

    private static int intField(String json, String name, int fallback) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : fallback;
    }

    private static String stringField(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static byte[] toBytes(String ip) throws UnknownHostException {
        Matcher m = IPV4.matcher(ip);
        if (!m.matches()) {
            throw new UnknownHostException(ip);
        }
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            int part = Integer.parseInt(m.group(i + 1));
            if (part > 255) {
                throw new UnknownHostException(ip);
            }
            bytes[i] = (byte) part;
        }
        return bytes;
    }

    /** HttpClient ilk ihtiyaçta oluşturulur. */
    private static final class Http {
        static final HttpClient CLIENT = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    private static String httpGet(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("Accept", "application/dns-json")
                    .GET()
                    .build();
            HttpResponse<String> response = Http.CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? response.body() : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Testler için: önbellekteki adres (yoksa null). */
    String cachedFirst(String host) {
        Cached c = cache.get(host.toLowerCase(Locale.ROOT));
        return c == null ? null : c.addresses()[0].getHostAddress();
    }
}
