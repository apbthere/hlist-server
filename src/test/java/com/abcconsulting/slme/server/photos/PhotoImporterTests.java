package com.abcconsulting.slme.server.photos;

import com.abcconsulting.slme.server.web.AddressPolicy;
import com.abcconsulting.slme.server.web.SafeDownloader;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the importer against a small local web site. */
class PhotoImporterTests {

	private record Page(String contentType, byte[] body) {
	}

	private HttpServer server;
	private final Map<String, Page> pages = new ConcurrentHashMap<>();
	private final List<String> requested = new CopyOnWriteArrayList<>();
	private final List<String> accepts = new CopyOnWriteArrayList<>();

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			String target = exchange.getRequestURI().toString();
			requested.add(target);
			accepts.add(String.valueOf(exchange.getRequestHeaders().getFirst("Accept")));
			Page page = pages.get(target);
			int status = page == null ? 404 : 200;
			byte[] body = page == null ? "<h1>Not found</h1>".getBytes(StandardCharsets.UTF_8) : page.body();
			exchange.getResponseHeaders().set("Content-Type", page == null ? "text/html" : page.contentType());
			exchange.sendResponseHeaders(status, body.length);
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

	private String site() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	private PhotoImporter.Fetched fetchPage(String path) {
		return new PhotoImporter(new SafeDownloader(address -> true, false)).fetchPicture(site() + path);
	}

	private byte[] fetch(String path) {
		return fetchPage(path).picture();
	}

	private void html(String path, String body) {
		pages.put(path, new Page("text/html; charset=utf-8", body.getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	void downloadsAnImageAddressAskingForReadableFormats() {
		byte[] jpeg = TestImages.jpeg(40, 30);
		pages.put("/bites.jpg", new Page("image/jpeg", jpeg));

		assertArrayEquals(jpeg, fetch("/bites.jpg"));
		assertTrue(accepts.getFirst().startsWith("image/jpeg,image/png,image/gif"), accepts.getFirst());
	}

	@Test
	void takesTheProductPictureFromAPageAskingForALargerSize() {
		byte[] large = TestImages.jpeg(120, 90);
		html("/p/bites", """
				<html><head>
				<meta name="description" content="Bites">
				<meta content="/assets/bites.avif?width=350&amp;q=80" property="og:image">
				</head><body></body></html>""");
		pages.put("/assets/bites.avif?width=1200&q=80", new Page("image/jpeg", large));

		assertArrayEquals(large, fetch("/p/bites"));
		assertEquals(List.of("/p/bites", "/assets/bites.avif?width=1200&q=80"), requested);
	}

	@Test
	void fallsBackToTheOriginalSizeWhenTheLargerOneIsMissing() {
		byte[] small = TestImages.jpeg(35, 35);
		html("/p/bites", "<meta property='og:image' content='" + site() + "/img.jpg?w=350'>");
		pages.put("/img.jpg?w=350", new Page("image/jpeg", small));

		assertArrayEquals(small, fetch("/p/bites"));
		assertEquals(List.of("/p/bites", "/img.jpg?w=1200", "/img.jpg?w=350"), requested);
	}

	@Test
	void asksForALargerSizeOfAnImageAddress() {
		byte[] large = TestImages.jpeg(120, 90);
		pages.put("/img.jpg?width=1200", new Page("image/jpeg", large));

		assertArrayEquals(large, fetch("/img.jpg?width=350"));
		assertEquals(List.of("/img.jpg?width=1200"), requested);
	}

	@Test
	void usesTheTwitterImageWhenThereIsNoOpenGraphImage() {
		byte[] png = TestImages.transparentPng(20, 20);
		html("/p/other", "<meta name=\"twitter:image\" content=\"pictures/other.png\">");
		pages.put("/p/pictures/other.png", new Page("image/png", png));

		assertArrayEquals(png, fetch("/p/other"));
	}

	@Test
	void explainsWhatWentWrong() {
		html("/p/plain", "<html><head><title>No picture</title></head></html>");
		pages.put("/photo.webp", new Page("image/webp", "RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1)));
		pages.put("/notes.txt", new Page("text/plain", "hello".getBytes(StandardCharsets.UTF_8)));

		assertTrue(message(() -> fetch("/p/plain")).contains("doesn't name a product picture"));
		assertTrue(message(() -> fetch("/photo.webp")).contains("format HList can't read"));
		assertTrue(message(() -> fetch("/notes.txt")).contains("no picture at that address"));
		assertTrue(message(() -> fetch("/missing")).contains("HTTP 404"));
	}

	@Test
	void refusesPrivateAndNonHttpsAddresses() {
		PhotoImporter importer = new PhotoImporter(new SafeDownloader(AddressPolicy.PUBLIC_ONLY));

		assertTrue(message(() -> importer.fetchPicture("http://example.com/a.jpg")).contains("https://"));
		assertTrue(message(() -> importer.fetchPicture("file:///etc/passwd")).contains("https://"));
		assertTrue(message(() -> importer.fetchPicture("https://127.0.0.1:" + server.getAddress().getPort() + "/a.jpg"))
				.contains("Couldn't open"));
		assertTrue(requested.isEmpty());
	}

	@Test
	void namesTheProductFromThePage() {
		html("/p/bites", """
				<title>Jojo&#x27;s Bites, 14.4 oz | Costco</title>
				<meta property="og:title" content="Jojo&#x27;s Dark Chocolate Bites, 14.4&nbsp;oz &amp; more"/>
				<meta property="og:image" content="/bites.jpg">""");
		pages.put("/bites.jpg", new Page("image/jpeg", TestImages.jpeg(10, 10)));

		assertEquals("Jojo's Dark Chocolate Bites, 14.4 oz & more", fetchPage("/p/bites").title());
		assertEquals(null, fetchPage("/bites.jpg").title());
	}

	@Test
	void takesTheBrandFromTheProductDataAndDropsItFromTheName() {
		html("/p/bites", """
				<meta property="og:title" content="Jojo&#x27;s Dark Chocolate Bites, 14.4 oz"/>
				<meta property="og:image" content="/bites.jpg">
				<script type="application/ld+json">{"@context":"https://schema.org","@type":"BreadcrumbList","itemListElement":[]}</script>
				<script type="application/ld+json">
				{"@context":"https://schema.org","@graph":[{"@type":"Organization","name":"Costco"},
				  {"@type":["Product"],"name":"Bites","brand":{"@type":"Brand","name":"Jojo's"}}]}
				</script>""");
		pages.put("/bites.jpg", new Page("image/jpeg", TestImages.jpeg(10, 10)));

		PhotoImporter.Fetched fetched = fetchPage("/p/bites");
		assertEquals("Jojo's", fetched.brand());
		assertEquals("Dark Chocolate Bites, 14.4 oz", fetched.title());
	}

	@Test
	void findsTheBrandInOtherShapes() {
		assertEquals("Kirkland Signature", PhotoImporter.productBrand(
				"<script type='application/ld+json'>[{\"@type\":\"Product\",\"brand\":\"Kirkland Signature\"}]</script>"));
		assertEquals("Horizon", PhotoImporter.productBrand("<meta property=\"product:brand\" content=\"Horizon\">"));
		assertEquals("Great Value", PhotoImporter.productBrand(
				"<script type=\"application/ld+json\">{not json</script><meta itemprop=\"brand\" content=\"Great Value\">"));
		assertEquals(null, PhotoImporter.productBrand("<script type=\"application/ld+json\">{\"@type\":\"Organization\",\"brand\":\"X\"}</script>"));
	}

	@Test
	void dropsOnlyALeadingBrandFromTheName() {
		assertEquals("Almonds, 3 lb", PhotoImporter.withoutBrand("Kirkland Signature Almonds, 3 lb", "Kirkland Signature"));
		assertEquals("Dark Chocolate Bites", PhotoImporter.withoutBrand("Jojo\u2019s® Dark Chocolate Bites", "Jojo's"));
		assertEquals("Organic Milk", PhotoImporter.withoutBrand("Horizon - Organic Milk", "Horizon"));
		assertEquals("Horizonal Shelf", PhotoImporter.withoutBrand("Horizonal Shelf", "Horizon"));
		assertEquals("Whole Milk by Horizon", PhotoImporter.withoutBrand("Whole Milk by Horizon", "Horizon"));
		assertEquals("Horizon", PhotoImporter.withoutBrand("Horizon", "Horizon"));
	}

	@Test
	void fallsBackToThePageTitleWithoutTheSiteName() {
		assertEquals("Kirkland Signature Almonds, 3 lb", PhotoImporter.productTitle(
				"<html><head><title>\n  Kirkland Signature Almonds, 3 lb | Costco\n</title></head><svg><title>Warehouse</title></svg>"));
		assertEquals("Store-brand milk", PhotoImporter.productTitle("<title>Store-brand milk - Walmart.com</title>"));
		assertEquals(null, PhotoImporter.productTitle("<html><body>No title</body></html>"));
	}

	@Test
	void readsPagesThatOnlyDescribeTheProductInJsonLd() {
		// Like Publix's Instacart storefront (delivery.publix.com): no og: tags, a Product in JSON-LD.
		String html = "<html><head><title>Publix Milk, Whole Same-Day Delivery or Pickup | Publix</title>"
				+ "<script type=\"application/ld+json\">{\"@context\":\"https://schema.org\",\"@type\":\"Product\","
				+ "\"name\":\"Publix Milk, Whole\",\"brand\":{\"@type\":\"Brand\",\"name\":\"Publix\"},"
				+ "\"image\":[\"https://images.test/product-image/large_milk.png\"]}</script></head></html>";
		assertEquals(List.of(URI.create("https://images.test/product-image/large_milk.png")),
				PhotoImporter.productImages(html, URI.create("https://delivery.publix.test/products/1")));
		assertEquals("Publix Milk, Whole", PhotoImporter.productTitle(html));
		assertEquals("Milk, Whole", PhotoImporter.withoutBrand(PhotoImporter.productTitle(html), PhotoImporter.productBrand(html)));
		assertEquals(List.of("https://images.test/a.jpg", "https://images.test/b.jpg"), PhotoImporter.jsonLdProductImages(
				"<script type=\"application/ld+json\">{\"@graph\":[{\"@type\":\"WebPage\"},{\"@type\":\"Product\",\"image\":"
						+ "[{\"@type\":\"ImageObject\",\"url\":\"https://images.test/a.jpg\"},\"https://images.test/b.jpg\"]}]}</script>"));
	}

	@Test
	void asksForLargerSizesOnlyWhenTheAddressRequestsASmallerOne() {
		assertEquals(URI.create("https://cdn.test/a.avif?width=1200"), PhotoImporter.larger(URI.create("https://cdn.test/a.avif?width=350")).orElseThrow());
		assertTrue(PhotoImporter.larger(URI.create("https://cdn.test/a.avif?width=2000")).isEmpty());
		assertTrue(PhotoImporter.larger(URI.create("https://cdn.test/a.avif")).isEmpty());
	}

	private static String message(Runnable action) {
		return assertThrows(IllegalArgumentException.class, action::run).getMessage();
	}
}
