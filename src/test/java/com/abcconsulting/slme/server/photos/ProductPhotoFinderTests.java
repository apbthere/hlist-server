package com.abcconsulting.slme.server.photos;

import com.abcconsulting.slme.server.web.SafeDownloader;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the finder against a local stand-in for the Open Food Facts search. */
class ProductPhotoFinderTests {

	private HttpServer server;
	/** Response bodies by query; unknown queries find nothing. */
	private final Map<String, String> results = new ConcurrentHashMap<>();
	private final Map<String, String> legacyResults = new ConcurrentHashMap<>();
	/** How many times the older search answers "busy" before answering. */
	private final java.util.concurrent.atomic.AtomicInteger legacyBusyAnswers = new java.util.concurrent.atomic.AtomicInteger();
	private static final String HAS_PHOTO = " states_tags:\"en:front-photo-selected\"";
	private final List<String> queries = new CopyOnWriteArrayList<>();
	private volatile int status = 200;
	private Instant now = Instant.parse("2026-10-02T12:00:00Z");

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/search", exchange -> {
			String raw = exchange.getRequestURI().getRawQuery();
			String query = URLDecoder.decode(raw.replaceFirst("^q=([^&]*).*$", "$1"), StandardCharsets.UTF_8);
			assertTrue(query.endsWith(HAS_PHOTO), query);
			query = query.substring(0, query.length() - HAS_PHOTO.length());
			queries.add(query);
			byte[] body = results.getOrDefault(query, "{\"hits\":[]}").getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(status, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.createContext("/cgi/search.pl", exchange -> {
			String terms = URLDecoder.decode(exchange.getRequestURI().getRawQuery().replaceFirst("^search_terms=([^&]*).*$", "$1"), StandardCharsets.UTF_8);
			queries.add("legacy: " + terms);
			byte[] body = legacyResults.getOrDefault(terms, "{\"products\":[]}").getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(legacyBusyAnswers.getAndDecrement() > 0 ? 503 : status, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.start();
	}

	@AfterEach
	void stopServer() {
		server.stop(0);
	}

	private ProductPhotoFinder finder() {
		Clock clock = new Clock() {
			@Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
			@Override public Clock withZone(java.time.ZoneId zone) { return this; }
			@Override public Instant instant() { return now; }
		};
		return new ProductPhotoFinder(new SafeDownloader(address -> true, false, "HList-test"),
				URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/search"),
				URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/cgi/search.pl"), clock,
				List.of(Duration.ZERO, Duration.ZERO));
	}

	private static String hit(String name, Object brands, String image) {
		String brandJson = brands instanceof List<?> list
				? list.stream().map(b -> "\"" + b + "\"").reduce((a, b) -> a + "," + b).map(b -> "[" + b + "]").orElse("[]")
				: brands == null ? "null" : "\"" + brands + "\"";
		return "{\"product_name\":\"" + name + "\",\"brands\":" + brandJson
				+ (image == null ? "" : ",\"image_front_url\":\"" + image + "\"") + "}";
	}

	private static String hits(String... hits) {
		return "{\"count\":" + hits.length + ",\"hits\":[" + String.join(",", hits) + "]}";
	}

	private static final String US = " countries_tags:\"en:united-states\"";

	@Test
	void searchesTheBrandAsSpeltThenSpeltSlightlyDifferently() {
		results.put("brands:\"Publix\" Whole milk" + US, hits(
				hit("Whole Milk", List.of("Publix"), "https://img.test/041/front_en.9.400.jpg"),
				hit("Whole milk, no photo", List.of("Publix"), null)));
		results.put("brands:publix~1 Whole milk" + US, hits(
				hit("Whole Milk", List.of("Publix"), "https://img.test/041/front_en.9.400.jpg"),
				hit("Organic Whole Milk", "Publix GreenWise, Publix", "https://img.test/042/front_en.3.400.jpg")));

		List<ProductPhotoFinder.Suggestion> found = finder().find("Whole milk", "Publix");

		assertEquals(List.of("brands:\"Publix\" Whole milk" + US, "brands:publix~1 Whole milk" + US), queries);
		assertEquals(List.of(
				new ProductPhotoFinder.Suggestion("Whole Milk", "Publix", "https://img.test/041/front_en.9.400.jpg", "https://img.test/041/front_en.9.full.jpg"),
				new ProductPhotoFinder.Suggestion("Organic Whole Milk", "Publix GreenWise", "https://img.test/042/front_en.3.400.jpg", "https://img.test/042/front_en.3.full.jpg")),
				found);
	}

	@Test
	void offersOnlyProductsWithTheItemsWordsAndASimilarBrand() {
		// "Mapple Hill" as typed in the brand list; the close-spelling search also finds other brands and products.
		results.put("brands:mapple~1 Greek yogurt" + US, hits(
				hit("Unsweetened plain whole milk greek yogurt", List.of("Maple Hill Creamery"), "https://img.test/mh/front_en.6.400.jpg"),
				hit("Fat Free Greek Dressing", List.of("Maple Grove Farms Of Vermont"), "https://img.test/mg/front_en.1.400.jpg"),
				hit("Greek Yogurt", List.of("Chobani"), "https://img.test/ch/front_en.2.400.jpg")));

		List<ProductPhotoFinder.Suggestion> found = finder().find("Greek yogurt", "Mapple Hill");

		assertEquals(List.of("Unsweetened plain whole milk greek yogurt"), found.stream().map(ProductPhotoFinder.Suggestion::name).toList());
	}

	@Test
	void fallsBackToAnyBrandWhenTheBrandHasNone() {
		results.put("Greek yogurt" + US, hits(
				hit("Nonfat Greek Yogurt", List.of("Chobani"), "https://img.test/ch/front_en.2.400.jpg"),
				hit("Maple syrup", List.of("Bio"), "https://img.test/ms/front_en.2.400.jpg")));

		List<ProductPhotoFinder.Suggestion> found = finder().find("Greek yogurt", "Unknown Dairy");

		assertEquals(List.of("brands:\"Unknown Dairy\" Greek yogurt" + US, "brands:unknown~1 Greek yogurt" + US,
				"brands:\"Unknown Dairy\" Greek yogurt", "brands:unknown~1 Greek yogurt", "legacy: Greek yogurt Unknown Dairy",
				"Greek yogurt" + US), queries);
		assertEquals(List.of("Nonfat Greek Yogurt"), found.stream().map(ProductPhotoFinder.Suggestion::name).toList());
	}

	@Test
	void looksBeyondUsProductsForTheBrandBeforeTryingOtherBrands() {
		// Products without a country don't match the US filter.
		results.put("brands:jojo's~1 Dark chocolate bites", hits(
				hit("Dark Chocolate Bites", List.of("Jojo’s"), "https://img.test/jj/front_en.58.400.jpg")));

		List<ProductPhotoFinder.Suggestion> found = finder().find("Dark chocolate bites", "Jojo's");

		assertEquals(List.of("Dark Chocolate Bites"), found.stream().map(ProductPhotoFinder.Suggestion::name).toList());
		assertEquals(4, queries.size());
	}

	@Test
	void asksTheOlderSearchForTheBrandBeforeTryingOtherBrands() {
		legacyResults.put("Dark chocolate pistachio bites Jojo's", "{\"products\":["
				+ hit("Dark Chocolate Pistachio Almond Cranberry Bites", "Jojo's", "https://img.test/jj/front_en.58.400.jpg") + ","
				+ hit("Pistachio Bites", "Other Brand", "https://img.test/ob/front_en.1.400.jpg") + "]}");

		List<ProductPhotoFinder.Suggestion> found = finder().find("Dark chocolate pistachio bites", "Jojo's");

		assertEquals(List.of("Dark Chocolate Pistachio Almond Cranberry Bites"), found.stream().map(ProductPhotoFinder.Suggestion::name).toList());
		assertEquals("legacy: Dark chocolate pistachio bites Jojo's", queries.getLast());
	}

	@Test
	void asksTheOlderSearchAgainWhenItsBusy() {
		legacyResults.put("Bread flour King Arthur", "{\"products\":["
				+ hit("Unbleached Bread Flour", "King Arthur Baking Company", "https://img.test/ka/front_en.4.400.jpg") + "]}");
		legacyBusyAnswers.set(2);

		List<ProductPhotoFinder.Suggestion> found = finder().find("Bread flour", "King Arthur");

		assertEquals(List.of("Unbleached Bread Flour"), found.stream().map(ProductPhotoFinder.Suggestion::name).toList());
		assertEquals(3, queries.stream().filter(query -> query.startsWith("legacy: ")).count());
	}

	@Test
	void givesUpOnTheOlderSearchWhenItStaysBusy() {
		legacyBusyAnswers.set(5);
		results.put("Bread flour" + US, hits(hit("Bread Flour", List.of("Pillsbury"), "https://img.test/pb/front_en.1.400.jpg")));

		List<ProductPhotoFinder.Suggestion> found = finder().find("Bread flour", "King Arthur");

		assertEquals(List.of("Bread Flour"), found.stream().map(ProductPhotoFinder.Suggestion::name).toList());
		assertEquals(3, queries.stream().filter(query -> query.startsWith("legacy: ")).count());
	}

	@Test
	void comparesBrandsAndWordsLoosely() {
		assertTrue(ProductPhotoFinder.similarBrand("Maple Hill Creamery", "Mapple Hill"));
		assertTrue(ProductPhotoFinder.similarBrand("JOJO'S", "Jojo’s"));
		assertTrue(ProductPhotoFinder.similarBrand("Kirkland Signature", "Kirkland"));
		assertFalse(ProductPhotoFinder.similarBrand("Maple Grove Farms Of Vermont", "Mapple Hill"));
		assertFalse(ProductPhotoFinder.similarBrand(null, "Publix"));
		List<String> yogurt = List.of("greek", "yogurt");
		assertTrue(ProductPhotoFinder.matchesName(new ProductPhotoFinder.Suggestion("Greek Yogurts, plain", null, "", ""), yogurt));
		assertFalse(ProductPhotoFinder.matchesName(new ProductPhotoFinder.Suggestion("Greek Dressing", null, "", ""), yogurt));
	}

	@Test
	void keepsQuerySyntaxOutOfTheSearch() {
		finder().find("Mac & cheese (family size)", "Kraft \"Heinz\"");

		assertEquals(List.of("brands:\"Kraft Heinz\" Mac cheese family size" + US, "brands:kraft~1 Mac cheese family size" + US,
				"brands:\"Kraft Heinz\" Mac cheese family size", "brands:kraft~1 Mac cheese family size",
				"legacy: Mac cheese family size Kraft Heinz", "Mac cheese family size" + US), queries);
	}

	@Test
	void stopsOnceThereAreEnough() {
		String[] many = new String[10];
		for (int i = 0; i < many.length; i++) {
			many[i] = hit("Almonds " + i, List.of("Kirkland Signature"), "https://img.test/" + i + "/front_en.1.400.jpg");
		}
		results.put("brands:\"Kirkland Signature\" almonds" + US, hits(many));

		List<ProductPhotoFinder.Suggestion> found = finder().find("almonds", "Kirkland Signature");

		assertEquals(ProductPhotoFinder.MAX_SUGGESTIONS, found.size());
		assertEquals(1, queries.size());
	}

	@Test
	void remembersResultsForADay() {
		results.put("bananas" + US, hits(hit("Bananas", null, "https://img.test/b/front_en.1.400.jpg")));
		ProductPhotoFinder finder = finder();

		finder.find("Bananas", null);
		finder.find("bananas", "");
		assertEquals(1, queries.size());

		now = now.plus(Duration.ofDays(1)).plusSeconds(1);
		finder.find("bananas", null);
		assertEquals(2, queries.size());
	}

	@Test
	void findsNothingWhenTheSearchFailsAndTriesAgainNextTime() {
		status = 503;
		ProductPhotoFinder finder = finder();

		assertTrue(finder.find("bananas", null).isEmpty());
		assertTrue(finder.find("bananas", null).isEmpty());
		assertEquals(2, queries.size());
		assertTrue(finder.find("  ", null).isEmpty());
		assertEquals(2, queries.size());
	}

	@Test
	void namesTheFullSizePhoto() {
		assertEquals("https://img.test/front_en.58.full.jpg", ProductPhotoFinder.fullSize("https://img.test/front_en.58.400.jpg"));
		assertEquals("https://img.test/other.png", ProductPhotoFinder.fullSize("https://img.test/other.png"));
	}
}
