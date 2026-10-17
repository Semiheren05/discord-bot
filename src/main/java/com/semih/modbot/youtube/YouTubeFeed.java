package com.semih.modbot.youtube;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

/**
 * YouTube'un herkese açık RSS akışını okur. API anahtarı gerekmez.
 * Akış: https://www.youtube.com/feeds/videos.xml?channel_id=UC...
 */
public final class YouTubeFeed {

    public record Video(String id, String title, String url, String author, Instant published) {
    }

    private static final String ATOM = "http://www.w3.org/2005/Atom";
    private static final String YT = "http://www.youtube.com/xml/schemas/2015";
    private static final String CHANNEL_ID = "UC[0-9A-Za-z_-]{22}";
    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";

    /** Kanal sayfasında ID'yi bulmak için sırayla denenecek kalıplar. */
    private static final List<Pattern> ID_PATTERNS = List.of(
            Pattern.compile("feeds/videos\\.xml\\?channel_id=(" + CHANNEL_ID + ")"),
            Pattern.compile("\"externalId\":\"(" + CHANNEL_ID + ")\""),
            Pattern.compile("<meta itemprop=\"identifier\" content=\"(" + CHANNEL_ID + ")\""),
            Pattern.compile("youtube\\.com/channel/(" + CHANNEL_ID + ")"));

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final HttpClient noRedirect = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /**
     * "@kanaladi", "https://www.youtube.com/@kanaladi" ya da "UC..." kabul eder,
     * kanal ID'sini (UC...) döner.
     */
    public String resolveChannelId(String input) throws IOException, InterruptedException {
        String value = input.trim();
        if (value.matches(CHANNEL_ID)) {
            return value;
        }
        Matcher direct = Pattern.compile("channel/(" + CHANNEL_ID + ")").matcher(value);
        if (direct.find()) {
            return direct.group(1);
        }
        String handle = value;
        int idx = handle.indexOf("youtube.com/");
        if (idx >= 0) {
            handle = handle.substring(idx + "youtube.com/".length());
        }
        handle = handle.split("[/?#]")[0];
        if (!handle.startsWith("@")) {
            handle = "@" + handle;
        }
        String html = get("https://www.youtube.com/" + handle).body();
        for (Pattern pattern : ID_PATTERNS) {
            Matcher m = pattern.matcher(html);
            if (m.find()) {
                return m.group(1);
            }
        }
        throw new IOException("'" + input + "' kanalının ID'si bulunamadı. config.properties'te youtube.kanal "
                + "değerine UC ile başlayan kanal ID'sini yaz (README'de nasıl bulunacağı yazıyor).");
    }

    /** Kanalın son ~15 videosunu döner (en yeni başta). */
    public List<Video> fetch(String channelId) throws IOException, InterruptedException {
        HttpResponse<String> response = get("https://www.youtube.com/feeds/videos.xml?channel_id=" + channelId);
        return parse(response.body());
    }

    /**
     * Video Short mu? youtube.com/shorts/ID adresi Short'larda açılır,
     * normal videolarda /watch adresine yönlendirir.
     */
    public boolean isShort(Video video) {
        if (video.url().contains("/shorts/")) {
            return true;
        }
        try {
            HttpRequest request = request("https://www.youtube.com/shorts/" + video.id()).method("HEAD",
                    HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<Void> response = noRedirect.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static final List<Pattern> SUB_PATTERNS = List.of(
            Pattern.compile("\"subscriberCountText\"\\s*:\\s*\\{[^}]*?\"(?:simpleText|content)\"\\s*:\\s*\"([^\"]+)\""),
            Pattern.compile("\"content\"\\s*:\\s*\"([0-9][0-9.,]*\\s*(?:B|Mn|bin|K|M|Mio)?\\s*(?:abone|subscribers?))\""),
            Pattern.compile("([0-9][0-9.,]*\\s*(?:B|Mn|bin)?\\s*abone)"));

    /** Abone sayısı yazısı (örn. "1,2 B abone"). Bulunamazsa null. YouTube sayfası değişirse çalışmayabilir. */
    public String subscribers(String channelId) {
        try {
            String html = get("https://www.youtube.com/channel/" + channelId).body();
            for (Pattern p : SUB_PATTERNS) {
                Matcher m = p.matcher(html);
                if (m.find()) {
                    String t = m.group(1).replace("\\u00a0", " ").replace('\u00A0', ' ').trim();
                    if (!t.isEmpty() && t.length() <= 40) return t;
                }
            }
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    static List<Video> parse(String xml) throws IOException {
        Document doc;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            // Güvenlik: dış varlık (XXE) yüklemeyi kapat
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler()); // hataları konsola basma, istisna olarak fırlat
            doc = builder.parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new IOException("YouTube akışı okunamadı: " + e.getMessage(), e);
        }

        List<Video> videos = new ArrayList<>();
        NodeList entries = doc.getElementsByTagNameNS(ATOM, "entry");
        for (int i = 0; i < entries.getLength(); i++) {
            Element entry = (Element) entries.item(i);
            String id = text(entry, YT, "videoId");
            if (id.isEmpty()) {
                continue;
            }
            String link = "";
            NodeList links = entry.getElementsByTagNameNS(ATOM, "link");
            for (int j = 0; j < links.getLength(); j++) {
                Element l = (Element) links.item(j);
                if ("alternate".equals(l.getAttribute("rel"))) {
                    link = l.getAttribute("href");
                    break;
                }
            }
            if (link.isEmpty()) {
                link = "https://www.youtube.com/watch?v=" + id;
            }
            Instant published;
            try {
                published = OffsetDateTime.parse(text(entry, ATOM, "published")).toInstant();
            } catch (Exception e) {
                published = Instant.now();
            }
            videos.add(new Video(id, text(entry, ATOM, "title"), link, text(entry, ATOM, "name"), published));
        }
        return videos;
    }

    private static String text(Element parent, String ns, String tag) {
        NodeList list = parent.getElementsByTagNameNS(ns, tag);
        return list.getLength() == 0 ? "" : list.item(0).getTextContent().trim();
    }

    private HttpResponse<String> get(String url) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request(url).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("YouTube " + response.statusCode() + " döndü: " + url);
        }
        return response;
    }

    private static HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "tr-TR,tr;q=0.9,en;q=0.8")
                // AB çerez onay sayfasına takılmamak için
                .header("Cookie", "SOCS=CAI; CONSENT=YES+1");
    }
}
