package utb.fai;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Malý lokální HTTP server pro testy - testy tak nezávisí na internetu.
 * Stránky se registrují podle přesné cesty, neznámá cesta vrací 404.
 */
final class TestServer implements AutoCloseable {

    private record Page(int status, String contentType, byte[] body, long delayMs, String location) {
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, Page> pages = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();

    TestServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    /** HTML stránka v UTF-8 (charset v HTTP hlavičce). */
    TestServer html(String path, String html) {
        return page(path, 200, "text/html; charset=UTF-8", html.getBytes(StandardCharsets.UTF_8), 0);
    }

    /** HTML stránka zakódovaná v {@code charset}, s vlastní hlavičkou Content-Type. */
    TestServer encoded(String path, String html, Charset charset, String contentType) {
        return page(path, 200, contentType, html.getBytes(charset), 0);
    }

    /** HTML stránka, která odpoví až po {@code delayMs} ms. */
    TestServer slow(String path, String html, long delayMs) {
        return page(path, 200, "text/html; charset=UTF-8", html.getBytes(StandardCharsets.UTF_8), delayMs);
    }

    TestServer redirect(String path, String location) {
        pages.put(path, new Page(302, null, new byte[0], 0, location));
        return this;
    }

    TestServer page(String path, int status, String contentType, byte[] body, long delayMs) {
        pages.put(path, new Page(status, contentType, body, delayMs, null));
        return this;
    }

    String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    URI uri(String path) {
        return URI.create(url(path));
    }

    /** Kolikrát crawler stáhl danou cestu. */
    int hits(String path) {
        AtomicInteger count = hits.get(path);
        return count == null ? 0 : count.get();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getRawPath();
            hits.computeIfAbsent(path, p -> new AtomicInteger()).incrementAndGet();
            Page page = pages.get(path);
            if (page == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            if (page.delayMs() > 0) {
                try {
                    Thread.sleep(page.delayMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (page.contentType() != null)
                exchange.getResponseHeaders().set("Content-Type", page.contentType());
            if (page.location() != null)
                exchange.getResponseHeaders().set("Location", page.location());
            if (page.body().length == 0) {
                exchange.sendResponseHeaders(page.status(), -1);
            } else {
                exchange.sendResponseHeaders(page.status(), page.body().length);
                exchange.getResponseBody().write(page.body());
            }
        } finally {
            exchange.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
