package com.semih.modbot.kick;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

/**
 * Kick kanalının yayın durumu.
 *
 * 1) Resmi API (önerilen): config.properties'e kick.client.id ve kick.client.secret yazılırsa
 *    https://api.kick.com/public/v1/channels?slug=... kullanılır. Engellenmez.
 * 2) Yedek: kick.com/api/v2/channels/... (resmi değil; Kick'in koruması sık sık engelleyebilir).
 */
public final class KickClient {

    /** Yayın bilgisi. live=false ise diğer alanlar boş olabilir. */
    public record Status(boolean live, String title, String category, int viewers, String startTime,
                         String thumbnail, String displayName) {
    }

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";

    private final String clientId;
    private final String clientSecret;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private String token;
    private long tokenExpires;

    public KickClient(String clientId, String clientSecret) {
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
    }

    public boolean hasOfficialApi() {
        return !clientId.isEmpty() && !clientSecret.isEmpty();
    }

    public Status status(String slug) throws IOException, InterruptedException {
        return hasOfficialApi() ? official(slug) : unofficial(slug);
    }

    // ---- Resmi API ----

    private synchronized String token() throws IOException, InterruptedException {
        if (token != null && System.currentTimeMillis() < tokenExpires - 60_000) return token;
        String body = "grant_type=client_credentials"
                + "&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://id.kick.com/oauth/token"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new IOException("Kick giriş yapılamadı (" + res.statusCode() + "). kick.client.id / kick.client.secret doğru mu?");
        }
        DataObject o = DataObject.fromJson(res.body());
        token = o.getString("access_token", null);
        long expiresIn;
        try {
            expiresIn = Long.parseLong(String.valueOf(o.opt("expires_in").orElse("3600")));
        } catch (NumberFormatException e) {
            expiresIn = 3600;
        }
        tokenExpires = System.currentTimeMillis() + expiresIn * 1000;
        if (token == null) throw new IOException("Kick token alınamadı.");
        return token;
    }

    private Status official(String slug) throws IOException, InterruptedException {
        HttpResponse<String> res = getOfficial(slug);
        if (res.statusCode() == 401) {
            synchronized (this) {
                token = null; // süresi dolmuş, yenile
            }
            res = getOfficial(slug);
        }
        if (res.statusCode() != 200) throw new IOException("Kick API " + res.statusCode() + " döndü.");
        return parseOfficial(res.body(), slug);
    }

    private HttpResponse<String> getOfficial(String slug) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.kick.com/public/v1/channels?slug="
                        + URLEncoder.encode(slug, StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token())
                .header("Accept", "application/json")
                .GET().build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    static Status parseOfficial(String json, String slug) throws IOException {
        DataArray data = DataObject.fromJson(json).optArray("data").orElse(DataArray.empty());
        if (data.length() == 0) throw new IOException("Kick'te '" + slug + "' adında bir kanal bulunamadı.");
        DataObject ch = data.getObject(0);
        DataObject stream = ch.optObject("stream").orElse(DataObject.empty());
        String category = ch.optObject("category").map(c -> c.getString("name", "")).orElse("");
        return new Status(stream.getBoolean("is_live", false), ch.getString("stream_title", ""), category,
                stream.getInt("viewer_count", 0), stream.getString("start_time", ""),
                stream.getString("thumbnail", null), ch.getString("slug", slug));
    }

    // ---- Yedek (resmi olmayan) ----

    private Status unofficial(String slug) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://kick.com/api/v2/channels/"
                        + URLEncoder.encode(slug, StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 404) throw new IOException("Kick'te '" + slug + "' adında bir kanal bulunamadı.");
        if (res.statusCode() != 200 || !res.body().trim().startsWith("{")) {
            throw new IOException("Kick botu engelledi (" + res.statusCode() + "). config.properties'e kick.client.id ve "
                    + "kick.client.secret yazarak resmi API'yi kullan (README'de nasıl alınacağı yazıyor).");
        }
        return parseUnofficial(res.body(), slug);
    }

    static Status parseUnofficial(String json, String slug) {
        DataObject o = DataObject.fromJson(json);
        String name = o.optObject("user").map(u -> u.getString("username", slug)).orElse(slug);
        DataObject ls = o.optObject("livestream").orElse(null);
        if (ls == null) return new Status(false, "", "", 0, "", null, name);
        String category = ls.optArray("categories").filter(a -> a.length() > 0)
                .map(a -> a.getObject(0).getString("name", "")).orElse("");
        String thumb = ls.optObject("thumbnail").map(t -> t.getString("url", null)).orElse(null);
        String start = ls.getString("start_time", ls.getString("created_at", ""));
        return new Status(ls.getBoolean("is_live", true), ls.getString("session_title", ""), category,
                ls.getInt("viewer_count", 0), start, thumb, name);
    }
}
