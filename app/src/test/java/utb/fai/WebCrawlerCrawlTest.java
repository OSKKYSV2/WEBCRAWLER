package utb.fai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Testy prohledávání proti lokálnímu HTTP serveru. */
class WebCrawlerCrawlTest {

    private TestServer server;

    @BeforeEach
    void startServer() throws Exception {
        server = new TestServer();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    private Map<String, Long> crawl(String path, int maxDepth) throws InterruptedException {
        return new WebCrawler(maxDepth, 0).crawl(server.uri(path));
    }

    private static String page(String body) {
        return "<!DOCTYPE html><html><head><title>Titulek</title></head><body>" + body + "</body></html>";
    }

    // ---------------------------------------------------------------- slova

    @Test
    @DisplayName("Počítá jen viditelný text - bez skriptů, stylů, komentářů a atributů")
    void countsOnlyVisibleText() throws Exception {
        server.html("/", "<html><head><title>T</title>"
                + "<style>.skryte { color: red }</style>"
                + "<script>var skript = 'neviditelne';</script></head>"
                + "<body><!-- komentar -->"
                + "<p title=\"atribut\">Kůň KŮŇ kůň, pes!</p>"
                + "<img alt=\"obrazek\" src=\"x.png\">"
                + "<script>document.write('dalsiskript')</script>"
                + "</body></html>");

        Map<String, Long> words = crawl("/", 0);

        assertEquals(Map.of("kůň", 3L, "pes", 1L), words);
    }

    @Test
    @DisplayName("Sousední odkazy se neslepí do jednoho slova")
    void adjacentInlineElementsAreSeparateWords() throws Exception {
        server.html("/", page("<span>Gmail</span><span>Obrázky</span><b>Google</b>"));

        Map<String, Long> words = crawl("/", 0);

        assertEquals(Map.of("gmail", 1L, "obrázky", 1L, "google", 1L), words);
    }

    @Test
    @DisplayName("Slovo = písmena; čísla a interpunkce slova oddělují")
    void wordsAreLetterSequences() throws Exception {
        server.html("/", page("<p>don't stop-me now 2024 PCB's e-mail</p>"));

        Map<String, Long> words = crawl("/", 0);

        assertEquals(Map.of("don", 1L, "t", 1L, "stop", 1L, "me", 1L, "now", 1L,
                "pcb", 1L, "s", 1L, "e", 1L, "mail", 1L), words);
    }

    // ---------------------------------------------------------------- hloubka a odkazy

    @Test
    @DisplayName("Hloubka 0 analyzuje jen startovní stránku")
    void depthZeroDoesNotFollowLinks() throws Exception {
        server.html("/", page("start <a href=\"/b\">odkaz</a>"))
                .html("/b", page("druha"));

        Map<String, Long> words = crawl("/", 0);

        assertEquals(Map.of("start", 1L, "odkaz", 1L), words);
        assertEquals(0, server.hits("/b"));
    }

    @Test
    @DisplayName("Odkazy se procházejí přesně do maximální hloubky")
    void followsLinksUpToMaxDepth() throws Exception {
        server.html("/", page("nula <a href=\"/one\">x</a>"))
                .html("/one", page("jedna <a href=\"two\">x</a>"))
                .html("/two", page("dva <a href=\"/three\">x</a>"))
                .html("/three", page("tri"));

        Map<String, Long> words = crawl("/", 2);

        assertEquals(1L, words.get("nula"));
        assertEquals(1L, words.get("jedna"));
        assertEquals(1L, words.get("dva"));
        assertNull(words.get("tri"));
        assertEquals(0, server.hits("/three"));
    }

    @Test
    @DisplayName("Každá stránka se analyzuje jen jednou (cykly, fragmenty, duplicitní odkazy)")
    void visitsEachPageOnlyOnce() throws Exception {
        server.html("/", page("domov <a href=\"/b\">x</a> <a href=\"/b#cast\">x</a> <a href=\"/b#jina\">x</a>"
                + " <a href=\"http://127.0.0.1:" + server.uri("/").getPort() + "/b\">x</a>"))
                .html("/b", page("bstrana <a href=\"/\">zpet</a> <a href=\"/b\">sama</a> <a href=\"/c\">dal</a>"))
                .html("/c", page("cstrana <a href=\"/\">zpet</a> <a href=\"/b\">b</a>"));

        Map<String, Long> words = crawl("/", 5);

        assertEquals(1, server.hits("/"));
        assertEquals(1, server.hits("/b"));
        assertEquals(1, server.hits("/c"));
        assertEquals(1L, words.get("domov"));
        assertEquals(1L, words.get("bstrana"));
        assertEquals(1L, words.get("cstrana"));
    }

    @Test
    @DisplayName("Přesměrování na už navštívenou stránku se nepočítá dvakrát")
    void redirectToVisitedPageIsNotCountedTwice() throws Exception {
        server.html("/", page("unikat <a href=\"/presmeruj\">x</a>"))
                .redirect("/presmeruj", "/");

        Map<String, Long> words = crawl("/", 1);

        assertEquals(1L, words.get("unikat"));
    }

    @Test
    @DisplayName("Rozbité odkazy, ne-HTML soubory a mailto: crawl neshodí")
    void brokenAndNonHtmlLinksAreSkipped() throws Exception {
        server.html("/", page("ok"
                + " <a href=\"/neexistuje\">a</a>"
                + " <a href=\"/chyba\">b</a>"
                + " <a href=\"/obrazek.png\">c</a>"
                + " <a href=\"mailto:info@utb.cz\">d</a>"
                + " <a href=\"javascript:void(0)\">e</a>"
                + " <a href=\"http://127.0.0.1:1/\">f</a>"))
                .page("/chyba", 500, "text/html", "chyba".getBytes(StandardCharsets.UTF_8), 0)
                .page("/obrazek.png", 200, "image/png", new byte[] { (byte) 0x89, 'P', 'N', 'G' }, 0);

        Map<String, Long> words = crawl("/", 1);

        assertEquals(1L, words.get("ok"));
        assertNull(words.get("chyba"));
        assertNull(words.get("png"));
    }

    @Test
    @DisplayName("Nedostupná startovní stránka vrátí prázdný výsledek")
    void unreachableStartPage() throws Exception {
        Map<String, Long> words = new WebCrawler(2, 0).crawl(java.net.URI.create("http://127.0.0.1:1/"));

        assertTrue(words.isEmpty());
    }

    // ---------------------------------------------------------------- kódování

    @Test
    @DisplayName("Kódování z HTTP hlavičky (ISO-8859-2)")
    void charsetFromHttpHeader() throws Exception {
        Charset latin2 = Charset.forName("ISO-8859-2");
        server.encoded("/", page("Příliš žluťoučký kůň"), latin2, "text/html; charset=ISO-8859-2");

        assertEquals(Map.of("příliš", 1L, "žluťoučký", 1L, "kůň", 1L), crawl("/", 0));
    }

    @Test
    @DisplayName("Kódování ze značky <meta charset> (windows-1250)")
    void charsetFromMetaCharset() throws Exception {
        Charset cp1250 = Charset.forName("windows-1250");
        server.encoded("/", "<html><head><meta charset=\"windows-1250\"></head>"
                + "<body>úpěl ďábelské ódy</body></html>", cp1250, "text/html");

        assertEquals(Map.of("úpěl", 1L, "ďábelské", 1L, "ódy", 1L), crawl("/", 0));
    }

    @Test
    @DisplayName("Kódování ze značky <meta http-equiv=Content-Type> (ISO-8859-2)")
    void charsetFromMetaHttpEquiv() throws Exception {
        Charset latin2 = Charset.forName("ISO-8859-2");
        server.encoded("/", "<html><head>"
                + "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=iso-8859-2\">"
                + "</head><body>šťastný čáp</body></html>", latin2, "text/html");

        assertEquals(Map.of("šťastný", 1L, "čáp", 1L), crawl("/", 0));
    }

    @Test
    @DisplayName("Charset v HTTP hlavičce má přednost před <meta>")
    void httpHeaderHasPriorityOverMeta() throws Exception {
        Charset cp1250 = Charset.forName("windows-1250");
        server.encoded("/", "<html><head><meta charset=\"UTF-8\"></head><body>řeřicha</body></html>",
                cp1250, "text/html; charset=windows-1250");

        assertEquals(Map.of("řeřicha", 1L), crawl("/", 0));
    }

    @Test
    @DisplayName("Každá stránka může mít jiné kódování")
    void differentEncodingsPerPage() throws Exception {
        server.encoded("/", "<html><head><meta charset=\"windows-1250\"></head><body>kůň"
                + " <a href=\"/latin2\">x</a> <a href=\"/utf8\">x</a></body></html>",
                Charset.forName("windows-1250"), "text/html")
                .encoded("/latin2", page("kůň"), Charset.forName("ISO-8859-2"), "text/html; charset=ISO-8859-2")
                .html("/utf8", page("kůň"));

        Map<String, Long> words = crawl("/", 1);

        assertEquals(3L, words.get("kůň"));
    }

    // ---------------------------------------------------------------- vlákna a limity

    @Test
    @DisplayName("Stránky se stahují paralelně (Executor)")
    void pagesAreDownloadedInParallel() throws Exception {
        StringBuilder links = new StringBuilder("start");
        for (int i = 0; i < 10; i++) {
            links.append(" <a href=\"/p").append(i).append("\">x</a>");
            server.slow("/p" + i, page("paralelne"), 400);
        }
        server.html("/", page(links.toString()));

        long start = System.currentTimeMillis();
        Map<String, Long> words = crawl("/", 1);
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(10L, words.get("paralelne"));
        // sériově by 10 stránek po 400 ms trvalo nejméně 4 s
        assertTrue(elapsed < 3000, "crawl took " + elapsed + " ms, pages are not fetched in parallel");
    }

    @Test
    @DisplayName("Crawl skončí po časovém limitu i když server neodpovídá")
    void stopsAfterTimeLimit() throws Exception {
        server.slow("/", page("pomalu"), 4000);

        long start = System.currentTimeMillis();
        Map<String, Long> words = new WebCrawler(2, 0, 500).crawl(server.uri("/"));
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed < 2500, "crawl took " + elapsed + " ms despite a 500 ms limit");
        assertFalse(words.containsKey("pomalu"));
    }
}
