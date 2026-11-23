package utb.fai;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Web crawler - vyhledá 20 nejčastějších slov na webu.
 *
 * Použití: java -jar app-all.jar <URL> [maxDepth] [debugLevel]
 * <ul>
 * <li>args[0] - URL, na které začíná analýza (např. http://yahoo.com)</li>
 * <li>args[1] - maximální hloubka zanoření (nepovinné, výchozí 2; 0 = jen
 * startovní stránka)</li>
 * <li>args[2] - úroveň ladění (nepovinné, výchozí 0; ladicí výpisy jdou na
 * System.err)</li>
 * </ul>
 *
 * Na System.out se vypíše pouze výsledná tabulka "slovo;počet".
 */
public class App {

	static final int DEFAULT_MAX_DEPTH = 2;
	static final int TOP_WORDS = 20;

	public static void main(String[] args) {
		// výsledek vždy v UTF-8, aby se správně vypsala i slova s diakritikou
		PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);

		if (args.length < 1 || args[0].isBlank()) {
			System.err.println("Missing parameter - start URL");
			System.err.println("Usage: java -jar app-all.jar <URL> [maxDepth=" + DEFAULT_MAX_DEPTH + "] [debugLevel=0]");
			return;
		}

		URI startURI;
		try {
			startURI = WebCrawler.normalizeStartURI(args[0]);
		} catch (Exception e) {
			System.err.println("Invalid start URL: " + args[0]);
			return;
		}

		int maxDepth = parseIntArg(args, 1, DEFAULT_MAX_DEPTH, "maxDepth");
		int debugLevel = parseIntArg(args, 2, 0, "debugLevel");
		if (maxDepth < 0) {
			System.err.println("maxDepth must not be negative, using 0");
			maxDepth = 0;
		}

		Map<String, Long> wordCounts;
		try {
			WebCrawler crawler = new WebCrawler(maxDepth, debugLevel);
			wordCounts = crawler.crawl(startURI);
		} catch (Exception e) {
			System.err.println("Unexpected error, printing what we have: " + e);
			if (debugLevel > 0)
				e.printStackTrace();
			wordCounts = Map.of();
		}

		List<Map.Entry<String, Long>> top = WebCrawler.topWords(wordCounts, TOP_WORDS);
		System.err.flush();
		for (Map.Entry<String, Long> entry : top) {
			out.println(entry.getKey() + ";" + entry.getValue());
		}
		out.flush();
	}

	private static int parseIntArg(String[] args, int index, int defaultValue, String name) {
		if (args.length <= index)
			return defaultValue;
		try {
			return Integer.parseInt(args[index].trim());
		} catch (NumberFormatException e) {
			System.err.println("Invalid " + name + " '" + args[index] + "', using " + defaultValue);
			return defaultValue;
		}
	}
}
