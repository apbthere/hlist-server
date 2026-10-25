package com.abcconsulting.slme.server.photos;

import com.abcconsulting.slme.server.web.SafeDownloader;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gets a picture from a web address: either the image itself (an address copied from a web page's photo) or a
 * product page, whose product image is read from its schema.org Product data or its {@code og:image}/
 * {@code twitter:image} tags. Downloads go
 * through {@link SafeDownloader} (HTTPS, public addresses only, size- and time-limited). Failures are
 * IllegalArgumentExceptions whose message tells the user what to do instead.
 */
public class PhotoImporter {

    /** Prefer formats the server can read; image servers such as Costco's convert AVIF/WebP on request. */
    private static final String ACCEPT_IMAGE = "image/jpeg,image/png,image/gif;q=0.9,*/*;q=0.1";
    private static final String ACCEPT_ANY = "image/jpeg,image/png,image/gif;q=0.9,text/html;q=0.8,*/*;q=0.1";
    /** Product images often come scaled down (e.g. ?width=350); ask for this width where the address allows. */
    static final int PREFERRED_WIDTH = 1200;
    private static final String SAVE_INSTEAD = " Save the picture to Photos and choose it from there instead.";

    private static final Pattern META_TAG = Pattern.compile("<meta\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINK_TAG = Pattern.compile("<link\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))");
    private static final Pattern WIDTH_PARAMETER = Pattern.compile("(?i)([?&](?:width|w|wid)=)(\\d+)");
    private static final Set<String> IMAGE_PROPERTIES = Set.of("og:image:secure_url", "og:image", "og:image:url", "twitter:image");
    /** Stand-ins sites show when a product has no picture, e.g. Instacart's "missing-item-....png". */
    private static final Pattern PLACEHOLDER = Pattern.compile("(?i)(missing|placeholder|no[-_]?image)[^/]*$");

    private static final Pattern TITLE_TAG = Pattern.compile("<title[^>]*>([^<]*)</title>", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHARACTER_REFERENCE = Pattern.compile("&(#[xX][0-9a-fA-F]+|#\\d+|amp|lt|gt|quot|apos|nbsp);");
    private static final List<String> TITLE_PROPERTIES = List.of("og:title", "twitter:title");
    private static final int MAX_TITLE_LENGTH = 255;
    private static final Pattern JSON_LD = Pattern.compile(
            "<script[^>]*type\\s*=\\s*[\"']application/ld\\+json[\"'][^>]*>(.*?)</script>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Set<String> BRAND_PROPERTIES = Set.of("product:brand", "og:brand", "brand");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * A downloaded picture and, when it came from a product page, the product's name (without the brand) and
     * brand; either may be null.
     */
    public record Fetched(byte[] picture, String title, String brand) {
    }

    private final SafeDownloader downloader;

    public PhotoImporter(SafeDownloader downloader) {
        this.downloader = downloader;
    }

    /** The picture's bytes (JPEG, PNG or GIF), ready for {@link ImageProcessor}, and the page's product name. */
    public Fetched fetchPicture(String address) {
        URI start = parse(address);
        // An image's own address may ask for a small size; try the larger one first.
        java.util.Optional<URI> larger = larger(start);
        if (larger.isPresent()) {
            try {
                SafeDownloader.Download image = download(larger.get(), ACCEPT_IMAGE);
                if (ImageProcessor.readable(image.body())) {
                    return new Fetched(image.body(), null, null);
                }
            } catch (IllegalArgumentException failed) {
                // fall back to the address as given
            }
        }
        SafeDownloader.Download first = download(start, ACCEPT_ANY);
        if (ImageProcessor.readable(first.body())) {
            return new Fetched(first.body(), null, null);
        }
        unsupportedFormat(first.body());
        if (!looksLikeHtml(first)) {
            throw new IllegalArgumentException("There's no picture at that address." + SAVE_INSTEAD);
        }
        String html = new String(first.body(), StandardCharsets.UTF_8);
        List<URI> candidates = productImages(html, first.url());
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("That page doesn't name a product picture." + SAVE_INSTEAD);
        }
        byte[] unsupported = null;
        for (URI candidate : candidates) {
            SafeDownloader.Download image;
            try {
                image = download(candidate, ACCEPT_IMAGE);
            } catch (IllegalArgumentException failed) {
                continue;
            }
            if (ImageProcessor.readable(image.body())) {
                String brand = productBrand(html);
                return new Fetched(image.body(), withoutBrand(productTitle(html), brand), brand);
            }
            unsupported = image.body();
        }
        if (unsupported != null) {
            unsupportedFormat(unsupported);
        }
        throw new IllegalArgumentException("The page's product picture couldn't be downloaded." + SAVE_INSTEAD);
    }

    private URI parse(String address) {
        String trimmed = address == null ? "" : address.trim();
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("That isn't a web address.");
        }
        if (!downloader.allowedScheme(uri)) {
            throw new IllegalArgumentException("Enter a web address starting with https://");
        }
        return uri;
    }

    private SafeDownloader.Download download(URI url, String accept) {
        SafeDownloader.Download download;
        try {
            download = downloader.download(url, PhotoService.MAX_UPLOAD_BYTES, accept);
        } catch (IOException | RuntimeException failed) {
            throw new IllegalArgumentException("Couldn't open that address." + SAVE_INSTEAD, failed);
        }
        if (download.status() != 200) {
            throw new IllegalArgumentException("The site refused the request (HTTP " + download.status() + ")." + SAVE_INSTEAD);
        }
        if (download.truncated()) {
            throw new IllegalArgumentException("That picture is too big to download." + SAVE_INSTEAD);
        }
        return download;
    }

    private static boolean looksLikeHtml(SafeDownloader.Download download) {
        if (download.contentType().contains("html")) {
            return true;
        }
        String start = new String(download.body(), 0, Math.min(download.body().length, 512), StandardCharsets.UTF_8)
                .stripLeading().toLowerCase(Locale.ROOT);
        return start.startsWith("<!doctype html") || start.startsWith("<html");
    }

    private static void unsupportedFormat(byte[] bytes) {
        String start = new String(bytes, 0, Math.min(bytes.length, 32), StandardCharsets.ISO_8859_1);
        if ((start.startsWith("RIFF") && start.contains("WEBP")) || start.contains("ftypavif") || start.contains("ftypheic")) {
            throw new IllegalArgumentException("That picture is in a format HList can't read (WebP, AVIF or HEIC)." + SAVE_INSTEAD);
        }
    }

    /** The page's product images, best first, each also asked for at {@link #PREFERRED_WIDTH} where possible. */
    static List<URI> productImages(String html, URI page) {
        // The Product's own image comes first: og:image is sometimes the site's banner (Whole Foods). Pages built in
        // the browser (Instacart storefronts such as delivery.publix.com) may have no image tags at all.
        List<String> found = new ArrayList<>(jsonLdProductImages(html));
        Matcher meta = META_TAG.matcher(html);
        while (meta.find()) {
            Map<String, String> attributes = attributes(meta.group());
            String key = attributes.getOrDefault("property", attributes.getOrDefault("name", "")).toLowerCase(Locale.ROOT);
            if (IMAGE_PROPERTIES.contains(key) && attributes.containsKey("content")) {
                found.add(attributes.get("content"));
            }
        }
        Matcher link = LINK_TAG.matcher(html);
        while (link.find()) {
            Map<String, String> attributes = attributes(link.group());
            if ("image_src".equalsIgnoreCase(attributes.getOrDefault("rel", "")) && attributes.containsKey("href")) {
                found.add(attributes.get("href"));
            }
        }
        // Last, images the page preloads: an Instacart storefront's JSON-LD names a placeholder (The Fresh Market),
        // but the page preloads the product's photo.
        link = LINK_TAG.matcher(html);
        while (link.find()) {
            Map<String, String> attributes = attributes(link.group());
            if ("preload".equalsIgnoreCase(attributes.getOrDefault("rel", ""))
                    && "image".equalsIgnoreCase(attributes.getOrDefault("as", "")) && attributes.containsKey("href")) {
                found.add(attributes.get("href"));
            }
        }
        Set<URI> candidates = new LinkedHashSet<>();
        for (String value : found) {
            try {
                URI image = page.resolve(unescape(value.trim()));
                if (image.getPath() != null && PLACEHOLDER.matcher(image.getPath()).find()) {
                    continue;
                }
                larger(image).ifPresent(candidates::add);
                candidates.add(image);
            } catch (IllegalArgumentException invalid) {
                // skip malformed addresses
            }
        }
        return List.copyOf(candidates);
    }

    /**
     * The product's name from the page: its og:title or twitter:title, else its {@code <title>} without the site's
     * name (" | Costco"). Null when there's none.
     */
    static String productTitle(String html) {
        String title = null;
        Matcher meta = META_TAG.matcher(html);
        while (meta.find() && title == null) {
            Map<String, String> attributes = attributes(meta.group());
            String key = attributes.getOrDefault("property", attributes.getOrDefault("name", "")).toLowerCase(Locale.ROOT);
            if (TITLE_PROPERTIES.contains(key) && attributes.containsKey("content")) {
                // Some sites add their name: "Organic Red Beets | Grocery Pickup & Delivery | Whole Foods Market".
                title = attributes.get("content").replaceFirst("\\s+\\|\\s.*$", "");
            }
        }
        if (title == null || title.isBlank()) {
            JsonNode product = jsonLdProduct(html);
            title = product == null ? null : product.path("name").asString(null);
        }
        if (title == null || title.isBlank()) {
            Matcher tag = TITLE_TAG.matcher(html);
            title = tag.find() ? tag.group(1).replaceFirst("\\s+[|\\u2013\\u2014-]\\s+[^|\\u2013\\u2014-]+$", "") : null;
        }
        if (title == null) {
            return null;
        }
        String clean = unescape(title).replace('\u00a0', ' ').replaceAll("\\s+", " ").strip();
        if (clean.isEmpty()) {
            return null;
        }
        return clean.length() > MAX_TITLE_LENGTH ? clean.substring(0, MAX_TITLE_LENGTH).strip() : clean;
    }

    /**
     * The product's brand: from the page's schema.org Product data (JSON-LD, which store sites publish for search
     * engines), else from a brand meta tag. Null when the page doesn't say.
     */
    static String productBrand(String html) {
        Matcher script = JSON_LD.matcher(html);
        while (script.find()) {
            try {
                String brand = brandIn(JSON.readTree(script.group(1).strip()));
                if (brand != null) {
                    return brand;
                }
            } catch (JacksonException malformed) {
                // ignore blocks that aren't valid JSON
            }
        }
        Matcher meta = META_TAG.matcher(html);
        while (meta.find()) {
            Map<String, String> attributes = attributes(meta.group());
            String key = attributes.getOrDefault("property",
                    attributes.getOrDefault("name", attributes.getOrDefault("itemprop", ""))).toLowerCase(Locale.ROOT);
            String brand = BRAND_PROPERTIES.contains(key) ? clean(attributes.get("content")) : null;
            if (brand != null) {
                return brand;
            }
        }
        return null;
    }

    /** The brand of the first Product anywhere in a JSON-LD document (including @graph and lists). */
    private static String brandIn(JsonNode node) {
        if (node.isArray()) {
            for (JsonNode element : node) {
                String brand = brandIn(element);
                if (brand != null) {
                    return brand;
                }
            }
            return null;
        }
        if (!node.isObject()) {
            return null;
        }
        if (isProduct(node.get("@type"))) {
            String brand = brandName(node.get("brand"));
            if (brand != null) {
                return brand;
            }
        }
        for (JsonNode child : node.values()) {
            if (child.isContainer()) {
                String brand = brandIn(child);
                if (brand != null) {
                    return brand;
                }
            }
        }
        return null;
    }

    /** The first schema.org Product in the page's JSON-LD, or null. */
    private static JsonNode jsonLdProduct(String html) {
        Matcher script = JSON_LD.matcher(html);
        while (script.find()) {
            try {
                JsonNode product = productIn(JSON.readTree(script.group(1).strip()));
                if (product != null) {
                    return product;
                }
            } catch (JacksonException malformed) {
                // ignore blocks that aren't valid JSON
            }
        }
        return null;
    }

    private static JsonNode productIn(JsonNode node) {
        if (node.isObject() && isProduct(node.get("@type"))) {
            return node;
        }
        if (node.isContainer()) {
            for (JsonNode child : node.isArray() ? node : node.values()) {
                JsonNode product = productIn(child);
                if (product != null) {
                    return product;
                }
            }
        }
        return null;
    }

    /** The JSON-LD Product's image addresses: a string, a list of them, or ImageObjects with a url. */
    static List<String> jsonLdProductImages(String html) {
        JsonNode product = jsonLdProduct(html);
        List<String> images = new ArrayList<>();
        if (product != null) {
            JsonNode image = product.path("image");
            for (JsonNode entry : image.isArray() ? image : List.of(image)) {
                String address = entry.isObject() ? entry.path("url").asString(entry.path("contentUrl").asString(null))
                        : entry.isString() ? entry.asString() : null;
                if (address != null && !address.isBlank()) {
                    images.add(address);
                }
            }
        }
        return images;
    }

    private static boolean isProduct(JsonNode type) {
        if (type == null) {
            return false;
        }
        if (type.isArray()) {
            for (JsonNode element : type) {
                if (isProduct(element)) {
                    return true;
                }
            }
            return false;
        }
        String name = type.asString("");
        return name.equals("Product") || name.endsWith("/Product") || name.equals("ProductGroup");
    }

    private static String brandName(JsonNode brand) {
        if (brand == null || brand.isNull()) {
            return null;
        }
        if (brand.isArray()) {
            return brand.isEmpty() ? null : brandName(brand.get(0));
        }
        if (brand.isObject()) {
            return brand.has("name") ? brandName(brand.get("name")) : null;
        }
        String name = clean(brand.asString(""));
        // Whole Foods' produce: "Fresh Produce (Brands May Vary)" isn't a brand.
        return name == null || name.toLowerCase(Locale.ROOT).contains("brands may vary") ? null : name;
    }

    private static String clean(String text) {
        if (text == null) {
            return null;
        }
        String clean = unescape(text).replace('\u00a0', ' ').replaceAll("\\s+", " ").strip();
        return clean.isEmpty() ? null : clean;
    }

    /** "Jojo's Dark Chocolate Bites" by "Jojo's" is "Dark Chocolate Bites"; other names are kept as they are. */
    static String withoutBrand(String title, String brand) {
        if (title == null || brand == null) {
            return title;
        }
        String comparable = title.replace('\u2019', '\'').toLowerCase(Locale.ROOT);
        String prefix = brand.replace('\u2019', '\'').toLowerCase(Locale.ROOT);
        if (!comparable.startsWith(prefix) || comparable.length() == prefix.length()
                || Character.isLetterOrDigit(comparable.charAt(prefix.length()))) {
            return title;
        }
        String rest = title.substring(prefix.length()).replaceFirst("^[\\s\u00ae\u2122:,\u2013\u2014-]+", "");
        return rest.isEmpty() ? title : rest;
    }

    /** The same image address asking for {@link #PREFERRED_WIDTH} if it requests a smaller width. */
    static java.util.Optional<URI> larger(URI image) {
        String text = image.toString();
        Matcher width = WIDTH_PARAMETER.matcher(text);
        if (!width.find() || Integer.parseInt(width.group(2)) >= PREFERRED_WIDTH) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(URI.create(text.substring(0, width.start(2)) + PREFERRED_WIDTH + text.substring(width.end(2))));
    }

    private static Map<String, String> attributes(String tag) {
        Map<String, String> attributes = new HashMap<>();
        Matcher attribute = ATTRIBUTE.matcher(tag);
        while (attribute.find()) {
            String value = attribute.group(2) != null ? attribute.group(2)
                    : attribute.group(3) != null ? attribute.group(3) : attribute.group(4);
            attributes.putIfAbsent(attribute.group(1).toLowerCase(Locale.ROOT), value);
        }
        return attributes;
    }

    /** Decodes HTML character references such as {@code &amp;} and {@code &#x27;}. */
    static String unescape(String value) {
        return CHARACTER_REFERENCE.matcher(value).replaceAll(reference -> {
            String name = reference.group(1);
            String character = switch (name) {
                case "amp" -> "&";
                case "lt" -> "<";
                case "gt" -> ">";
                case "quot" -> "\"";
                case "apos" -> "'";
                case "nbsp" -> "\u00a0";
                default -> {
                    int codePoint = name.charAt(1) == 'x' || name.charAt(1) == 'X'
                            ? Integer.parseInt(name.substring(2), 16) : Integer.parseInt(name.substring(1));
                    yield Character.isValidCodePoint(codePoint) ? Character.toString(codePoint) : "";
                }
            };
            return Matcher.quoteReplacement(character);
        });
    }
}
