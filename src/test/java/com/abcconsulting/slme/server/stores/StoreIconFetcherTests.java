package com.abcconsulting.slme.server.stores;

import com.abcconsulting.slme.server.web.AddressPolicy;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the fetcher against a small local web site. */
class StoreIconFetcherTests {

	private record Page(int status, String contentType, byte[] body, String location) {
	}

	private static final byte[] PNG_HEADER = ImageTypeTests.bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A);

	private HttpServer server;
	private final Map<String, Page> pages = new ConcurrentHashMap<>();
	private final List<String> requested = new CopyOnWriteArrayList<>();

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			String path = exchange.getRequestURI().getPath();
			requested.add(path);
			Page page = pages.getOrDefault(path, new Page(404, "text/html", "<h1>Not found</h1>".getBytes(StandardCharsets.UTF_8), null));
			exchange.getResponseHeaders().set("Content-Type", page.contentType());
			if (page.location() != null) {
				exchange.getResponseHeaders().set("Location", page.location());
			}
			exchange.sendResponseHeaders(page.status(), page.body().length == 0 ? -1 : page.body().length);
			try (OutputStream body = exchange.getResponseBody()) {
				body.write(page.body());
			}
		});
		server.start();
	}

	@AfterEach
	void stopServer() {
		server.stop(0);
	}

	private Store store() {
		return new Store("test", "Test", List.of("test"), URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
	}

	private Optional<StoreIconFetcher.FetchedIcon> fetch() {
		return new StoreIconFetcher(address -> true, false).fetch(store());
	}

	private static byte[] png(String marker) {
		byte[] tail = marker.getBytes(StandardCharsets.UTF_8);
		byte[] image = new byte[PNG_HEADER.length + tail.length];
		System.arraycopy(PNG_HEADER, 0, image, 0, PNG_HEADER.length);
		System.arraycopy(tail, 0, image, PNG_HEADER.length, tail.length);
		return image;
	}

	private void html(String path, String body) {
		pages.put(path, new Page(200, "text/html", body.getBytes(StandardCharsets.UTF_8), null));
	}

	private void image(String path, byte[] body) {
		pages.put(path, new Page(200, "image/png", body, null));
	}

	@Test
	void takesTheLargestDeclaredIconThatIsReallyAnImage() {
		html("/", """
				<link rel="icon" href="/favicon.ico">
				<link rel="icon" href="/big.png" sizes="512x512">
				<link rel="apple-touch-icon" href="/touch.png">
				""");
		// The largest one is an HTML error page served with status 200: skipped.
		pages.put("/big.png", new Page(200, "image/png", "<html>oops</html>".getBytes(StandardCharsets.UTF_8), null));
		image("/touch.png", png("touch"));

		StoreIconFetcher.FetchedIcon icon = fetch().orElseThrow();
		assertEquals("image/png", icon.contentType());
		assertTrue(icon.source().toString().endsWith("/touch.png"));
	}

	@Test
	void fallsBackToStandardPathsWhenThePageIsBlocked() {
		pages.put("/", new Page(403, "text/html", "denied".getBytes(StandardCharsets.UTF_8), null));
		image("/apple-touch-icon-precomposed.png", png("precomposed"));
		image("/favicon.ico", ImageTypeTests.bytes(0, 0, 1, 0, 1, 0));

		assertTrue(fetch().orElseThrow().source().toString().endsWith("/apple-touch-icon-precomposed.png"),
				"a standard home-screen icon beats the favicon");
	}

	@Test
	void followsRedirects() {
		html("/", "<link rel=\"apple-touch-icon\" href=\"/old-touch.png\">");
		pages.put("/old-touch.png", new Page(301, "text/html", new byte[0], "/assets/touch.png"));
		image("/assets/touch.png", png("moved"));

		assertTrue(fetch().orElseThrow().source().toString().endsWith("/assets/touch.png"));
	}

	@Test
	void rejectsOversizedIconsAndGivesUpWhenNothingIsUsable() {
		html("/", "<link rel=\"apple-touch-icon\" href=\"/huge.png\">");
		image("/huge.png", png("x".repeat(StoreIconFetcher.MAX_ICON_BYTES)));

		assertEquals(Optional.empty(), fetch());
	}

	@Test
	void thePublicOnlyPolicyNeverContactsTheLocalMachine() {
		html("/", "<link rel=\"apple-touch-icon\" href=\"/touch.png\">");
		image("/touch.png", png("touch"));

		assertEquals(Optional.empty(), new StoreIconFetcher(AddressPolicy.PUBLIC_ONLY, false).fetch(store()));
		assertEquals(List.of(), requested, "no request reached the local server");
	}

	@Test
	void plainHttpIsRefusedOutsideTests() {
		html("/", "<link rel=\"apple-touch-icon\" href=\"/touch.png\">");
		image("/touch.png", png("touch"));

		assertEquals(Optional.empty(), new StoreIconFetcher(address -> true).fetch(store()));
		assertEquals(List.of(), requested);
	}
}
