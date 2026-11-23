package utb.fai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Testy parametrů a formátu výstupu aplikace (jak ho kontroluje NATT). */
class AppTest {

    private TestServer server;

    @BeforeEach
    void startServer() throws Exception {
        server = new TestServer();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    /** Spustí aplikaci a vrátí řádky standardního výstupu. */
    private static List<String> run(String... args) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            App.run(args, out, WebCrawler.TIME_LIMIT_MS);
        }
        String text = bytes.toString(StandardCharsets.UTF_8);
        return text.isEmpty() ? List.of() : List.of(text.split("\\R"));
    }

    private static void assertResultFormat(List<String> lines) {
        assertTrue(lines.size() <= App.TOP_WORDS, "more than 20 lines: " + lines.size());
        long previous = Long.MAX_VALUE;
        for (String line : lines) {
            assertTrue(line.matches("\\p{L}+;\\d+"), "line is not 'word;count': " + line);
            long count = Long.parseLong(line.substring(line.indexOf(';') + 1));
            assertTrue(count <= previous, "output is not sorted descending at: " + line);
            previous = count;
        }
    }

    @Test
    @DisplayName("Vypíše přesně 20 nejčastějších slov ve formátu slovo;počet sestupně")
    void printsTop20WordsSortedDescending() {
        // slovo "wa" 1x, "wb" 2x, ... "wy" 25x
        StringBuilder text = new StringBuilder();
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            String word = "w" + (char) ('a' + i);
            text.append((word + " ").repeat(i + 1));
        }
        for (int i = 24; i >= 5; i--)
            expected.add("w" + (char) ('a' + i) + ";" + (i + 1));
        server.html("/", "<html><body><p>" + text + "</p></body></html>");

        List<String> lines = run(server.url("/"), "0");

        assertEquals(expected, lines);
        assertResultFormat(lines);
    }

    @Test
    @DisplayName("Slova s diakritikou se vypíšou v UTF-8")
    void printsDiacritics() {
        server.html("/", "<html><body>Žluťoučký kůň kůň</body></html>");

        assertEquals(List.of("kůň;2", "žluťoučký;1"), run(server.url("/"), "0"));
    }

    @Test
    @DisplayName("Výchozí hloubka zanoření je 2")
    void defaultMaxDepthIsTwo() {
        server.html("/", "<a href=\"/one\">nula</a>")
                .html("/one", "<a href=\"/two\">jedna</a>")
                .html("/two", "<a href=\"/three\">dva</a>")
                .html("/three", "tri");

        List<String> lines = run(server.url("/"));

        assertEquals(List.of("dva;1", "jedna;1", "nula;1"), lines);
        assertEquals(0, server.hits("/three"));
    }

    @Test
    @DisplayName("Neplatná hloubka se nahradí výchozí hodnotou")
    void invalidDepthFallsBackToDefault() {
        server.html("/", "<a href=\"/one\">nula</a>")
                .html("/one", "<a href=\"/two\">jedna</a>")
                .html("/two", "<a href=\"/three\">dva</a>")
                .html("/three", "tri");

        assertEquals(List.of("dva;1", "jedna;1", "nula;1"), run(server.url("/"), "abc"));
    }

    @Test
    @DisplayName("Ladicí výpisy (debugLevel 2) nejdou na standardní výstup")
    void debugOutputDoesNotPolluteStdout() {
        server.html("/", "<html><body>alfa alfa beta <a href=\"/b\">gama</a></body></html>")
                .html("/b", "<html><body>beta</body></html>");

        List<String> lines = run(server.url("/"), "1", "2");

        assertEquals(List.of("alfa;2", "beta;2", "gama;1"), lines);
    }

    @Test
    @DisplayName("Bez parametrů aplikace nespadne a nic nevypíše")
    void missingUrlDoesNotCrash() {
        assertEquals(List.of(), run());
        assertEquals(List.of(), run(""));
    }

    @Test
    @DisplayName("Nedostupný web - aplikace nespadne")
    void unreachableHostDoesNotCrash() {
        assertEquals(List.of(), run("http://127.0.0.1:1/", "0", "0"));
    }
}
