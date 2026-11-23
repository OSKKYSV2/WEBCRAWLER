package utb.fai;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;

/**
 * Vícevláknový web crawler. Každá stránka se stahuje a analyzuje jako
 * samostatná úloha v Executoru. Pro parsování HTML se používá JSoup.
 */
public class WebCrawler {

    /** Časový limit celého prohledávání - aplikace vždy skončí a vypíše výsledek. */
    static final long TIME_LIMIT_MS = 10_000;
    /** Maximální počet stránek, které se naplánují ke stažení. */
    static final int MAX_PAGES = 1000;
    /** Časový limit pro stažení jedné stránky. */
    static final int PAGE_TIMEOUT_MS = 5_000;
    /** Maximální velikost těla jedné stránky. */
    static final int MAX_BODY_SIZE = 5 * 1024 * 1024;
    static final String USER_AGENT = "Mozilla/5.0 (compatible; UTB-FAI-WebCrawler/1.0)";

    /** Slovo = souvislá posloupnost písmen (včetně diakritiky). */
    private static final Pattern WORD = Pattern.compile("\\p{L}+");

    private final int maxDepth;
    private final int debugLevel;
    private final long timeLimitMs;

    /** Množina navštívených (naplánovaných) URI - klíč je normalizované URI. */
    private final ConcurrentHashMap<String, Boolean> visitedURIs = new ConcurrentHashMap<>();
    /** Absolutní četnost slov přes všechny stránky. */
    private final ConcurrentHashMap<String, LongAdder> wordCounts = new ConcurrentHashMap<>();

    private final ExecutorService executor;
    /** Počet naplánovaných a dosud nedokončených úloh. */
    private final AtomicInteger pendingTasks = new AtomicInteger();
    private final CountDownLatch finished = new CountDownLatch(1);
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicInteger analyzedPages = new AtomicInteger();
    private final AtomicInteger failedPages = new AtomicInteger();

    public WebCrawler(int maxDepth, int debugLevel) {
        this(maxDepth, debugLevel, TIME_LIMIT_MS);
    }

    WebCrawler(int maxDepth, int debugLevel, long timeLimitMs) {
        this.maxDepth = maxDepth;
        this.debugLevel = debugLevel;
        this.timeLimitMs = timeLimitMs;
        int threads = Math.max(4, Runtime.getRuntime().availableProcessors() * 2);
        // vlákna jsou daemon, aby případné zaseknuté spojení nebránilo ukončení JVM
        this.executor = Executors.newFixedThreadPool(threads, runnable -> {
            Thread t = new Thread(runnable, "crawler");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Prohledá web od zadaného URI a vrátí četnost slov.
     */
    public Map<String, Long> crawl(URI startURI) throws InterruptedException {
        long start = System.currentTimeMillis();
        schedule(new URIinfo(startURI, 0));

        boolean completed = finished.await(timeLimitMs, TimeUnit.MILLISECONDS);
        stopped.set(true);
        executor.shutdownNow();
        executor.awaitTermination(500, TimeUnit.MILLISECONDS);

        if (!completed)
            System.err.printf("Time limit %.1f s reached, stopping the crawl.%n", timeLimitMs / 1000.0);
        if (debugLevel > 0 || !completed)
            System.err.printf("Analyzed %d page(s), %d failed, %d URI(s) found, %.1f s%n",
                    analyzedPages.get(), failedPages.get(), visitedURIs.size(),
                    (System.currentTimeMillis() - start) / 1000.0);

        Map<String, Long> result = new HashMap<>();
        wordCounts.forEach((word, count) -> result.put(word, count.sum()));
        return result;
    }

    /**
     * Naplánuje stažení URI, pokud ještě nebylo navštíveno.
     */
    private void schedule(URIinfo info) {
        if (stopped.get())
            return;
        String key = info.uri.toString();
        if (visitedURIs.size() >= MAX_PAGES || visitedURIs.putIfAbsent(key, Boolean.TRUE) != null)
            return;

        pendingTasks.incrementAndGet();
        try {
            executor.execute(() -> {
                try {
                    processPage(info);
                } finally {
                    taskDone();
                }
            });
        } catch (RejectedExecutionException e) {
            taskDone(); // executor už byl ukončen
        }
    }

    private void taskDone() {
        if (pendingTasks.decrementAndGet() == 0)
            finished.countDown();
    }

    /**
     * Stáhne a zanalyzuje jednu stránku: spočítá slova a naplánuje nalezené
     * odkazy.
     */
    private void processPage(URIinfo info) {
        if (stopped.get())
            return;
        if (debugLevel > 0)
            System.err.println("Analyzing " + info.uri + " (depth " + info.depth + ")");
        try {
            Connection.Response response = Jsoup.connect(info.uri.toString())
                    .userAgent(USER_AGENT)
                    .timeout(PAGE_TIMEOUT_MS)
                    .maxBodySize(MAX_BODY_SIZE)
                    .followRedirects(true)
                    .execute();

            // po přesměrování nesmíme analyzovat stránku, kterou už zpracovalo jiné vlákno
            URI finalURI = normalizeLink(response.url().toString());
            if (finalURI != null && !finalURI.equals(info.uri)
                    && visitedURIs.putIfAbsent(finalURI.toString(), Boolean.TRUE) != null) {
                if (debugLevel > 1)
                    System.err.println("  " + info.uri + " redirects to already visited " + finalURI);
                return;
            }

            // Kódování: JSoup vezme charset z HTTP hlavičky Content-Type,
            // a pokud tam chybí, zjistí ho ze značky <meta charset> /
            // <meta http-equiv="Content-Type"> (případně z BOM), jinak UTF-8.
            String headerCharset = response.charset();
            Document doc = response.parse();
            if (debugLevel > 1)
                System.err.println("  charset: " + (headerCharset != null
                        ? headerCharset + " (HTTP header)"
                        : doc.charset() + " (META tag / default)"));

            countWords(doc);
            analyzedPages.incrementAndGet();

            if (info.depth < maxDepth)
                scheduleLinks(doc, info.depth + 1);
        } catch (Exception e) {
            failedPages.incrementAndGet();
            if (debugLevel > 0)
                System.err.println("Error loading " + info.uri + ": " + e);
        }
    }

    /**
     * Spočítá slova v textu viditelném pro uživatele (bez skriptů, stylů a
     * značek).
     */
    private void countWords(Document doc) {
        Element root = doc.body() != null ? doc.body() : doc;

        // Text bereme po jednotlivých textových uzlech: Element.text() by slepil
        // sousední inline prvky bez mezery (např. <a>Gmail</a><a>Obrázky</a>).
        // Obsah <script> a <style> nejsou TextNode, takže se nepočítá.
        Map<String, Integer> local = new HashMap<>();
        NodeTraversor.traverse((node, depth) -> {
            if (node instanceof TextNode textNode) {
                Matcher m = WORD.matcher(textNode.text().toLowerCase(Locale.ROOT));
                while (m.find())
                    local.merge(m.group(), 1, Integer::sum);
            }
        }, root);

        // lokální výsledek stránky se přičte do sdílené mapy najednou
        local.forEach((word, count) -> wordCounts.computeIfAbsent(word, w -> new LongAdder()).add(count));
    }

    private void scheduleLinks(Document doc, int depth) {
        for (Element link : doc.select("a[href], frame[src], iframe[src]")) {
            if (stopped.get())
                return;
            String href = link.hasAttr("href") ? link.absUrl("href") : link.absUrl("src");
            URI uri = normalizeLink(href);
            if (uri != null) {
                if (debugLevel > 1 && !visitedURIs.containsKey(uri.toString()))
                    System.err.println("  Adding URI: " + uri);
                schedule(new URIinfo(uri, depth));
            }
        }
    }

    /**
     * Vrátí normalizované absolutní http(s) URI bez fragmentu, nebo null pro
     * odkazy, které nemá smysl procházet (mailto:, javascript:, ...).
     */
    static URI normalizeLink(String href) {
        if (href == null || href.isBlank())
            return null;
        try {
            URI uri = new URI(href.trim());
            String scheme = uri.getScheme();
            if (scheme == null || uri.isOpaque() || uri.getHost() == null)
                return null;
            scheme = scheme.toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https"))
                return null;
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            StringBuilder sb = new StringBuilder();
            sb.append(scheme).append("://").append(uri.getRawAuthority().toLowerCase(Locale.ROOT)).append(path);
            if (uri.getRawQuery() != null)
                sb.append('?').append(uri.getRawQuery());
            return new URI(sb.toString()).normalize();
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Normalizuje startovní URL; pokud chybí schéma, doplní http://.
     */
    static URI normalizeStartURI(String url) throws URISyntaxException {
        String s = url.trim();
        if (!s.matches("(?i)^[a-z][a-z0-9+.-]*://.*"))
            s = "http://" + s;
        URI uri = normalizeLink(s);
        if (uri == null)
            throw new URISyntaxException(url, "Unsupported URL");
        return uri;
    }

    /**
     * Vrátí n nejčastějších slov seřazených sestupně podle četnosti
     * (při shodě abecedně).
     */
    static List<Map.Entry<String, Long>> topWords(Map<String, Long> counts, int n) {
        List<Map.Entry<String, Long>> entries = new ArrayList<>(counts.entrySet());
        entries.sort(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey()));
        return entries.subList(0, Math.min(n, entries.size()));
    }
}
