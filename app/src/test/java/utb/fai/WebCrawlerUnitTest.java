package utb.fai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Testy pomocných funkcí bez sítě. */
class WebCrawlerUnitTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @DisplayName("Odkazy se normalizují (bez fragmentu, malý host, cesta /, ../)")
    @CsvSource({
            "http://Example.COM/a/b#sekce, http://example.com/a/b",
            "https://example.com, https://example.com/",
            "HTTPS://example.com/en/, https://example.com/en/",
            "http://e.com/a?x=1&y=2#top, http://e.com/a?x=1&y=2",
            "http://e.com/a/../b/./c, http://e.com/b/c",
            "http://e.com:8080/x, http://e.com:8080/x",
    })
    void normalizesLinks(String href, String expected) {
        assertEquals(expected, WebCrawler.normalizeLink(href).toString());
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("Odkazy, které nejsou http(s) stránky, se přeskočí")
    @NullAndEmptySource
    @ValueSource(strings = {
            "mailto:info@utb.cz",
            "javascript:void(0)",
            "tel:+420576031111",
            "ftp://example.com/file.txt",
            "/relativni/cesta",
            "http:///bez-hostu",
            "http://bad host/",
    })
    void rejectsNonHttpLinks(String href) {
        assertNull(WebCrawler.normalizeLink(href));
    }

    @Test
    @DisplayName("Startovní URL bez schématu dostane http://")
    void startUrlWithoutScheme() throws URISyntaxException {
        assertEquals("http://example.com/", WebCrawler.normalizeStartURI("example.com").toString());
        assertEquals("https://www.utb.cz/en/", WebCrawler.normalizeStartURI(" https://www.utb.cz/en/ ").toString());
    }

    @Test
    @DisplayName("Nepodporované startovní URL vyhodí výjimku")
    void unsupportedStartUrl() {
        assertThrows(URISyntaxException.class, () -> WebCrawler.normalizeStartURI("ftp://example.com/"));
    }

    @Test
    @DisplayName("topWords řadí sestupně, při shodě abecedně a vrací max. n slov")
    void topWordsSortsAndLimits() {
        Map<String, Long> counts = Map.of(
                "the", 10L, "and", 7L, "of", 7L, "a", 3L, "zebra", 1L);

        List<Map.Entry<String, Long>> top = WebCrawler.topWords(counts, 4);

        assertEquals(List.of(Map.entry("the", 10L), Map.entry("and", 7L), Map.entry("of", 7L), Map.entry("a", 3L)),
                top);
    }

    @Test
    @DisplayName("topWords zvládne méně slov než n i prázdnou mapu")
    void topWordsSmallInput() {
        assertEquals(List.of(Map.entry("jedno", 1L)), WebCrawler.topWords(Map.of("jedno", 1L), 20));
        assertTrue(WebCrawler.topWords(Map.of(), 20).isEmpty());
    }
}
