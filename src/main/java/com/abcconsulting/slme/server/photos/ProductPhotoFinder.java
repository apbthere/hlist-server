package com.abcconsulting.slme.server.photos;

import com.abcconsulting.slme.server.web.SafeDownloader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Finds photos of packaged products by name and brand in Open Food Facts, the open product database. With a brand,
 * it searches that brand (also spelt slightly differently, so "Mapple Hill" finds Maple Hill Creamery) among
 * products sold in the United States, then among all products (not every product says where it's sold), and only
 * if that finds nothing, any brand sold in the United States. The brand is also looked up with the older search
 * service, whose index has products the newer one misses (it allows fewer searches, so it's used only then, and
 * asked again when it's busy). Every result's name must
 * contain the item's words, and a brand search's results must be of a similar brand, so loosely related products
 * (maple syrup for "Greek yogurt by Maple Hill") aren't offered. Results are cached for a day.
 */
public class ProductPhotoFinder {

    private static final Logger log = LoggerFactory.getLogger(ProductPhotoFinder.class);

    static final int MAX_SUGGESTIONS = 8;
    private static final int PAGE_SIZE = 24;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_CACHED = 500;
    private static final Duration CACHE_TIME = Duration.ofDays(1);
    /** Characters with a meaning in the search's query syntax. */
    private static final String QUERY_SYNTAX = "[+\\-!(){}\\[\\]^\"~*?:\\\\/&|]";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String COUNTRY_FILTER = " countries_tags:\"en:united-states\"";
    /** Only products with a front photo, so every result can be offered. */
    private static final String HAS_PHOTO_FILTER = " states_tags:\"en:front-photo-selected\"";
    private static final List<Duration> LEGACY_RETRY_DELAYS = List.of(Duration.ofSeconds(1), Duration.ofSeconds(3));
    private static final Set<String> FILLER_WORDS = Set.of("and", "the", "with", "for", "of", "in", "by", "a", "an", "or");

    /** A product photo: a small one to show, and the full-size one to download when it's chosen. */
    public record Suggestion(String name, String brand, String thumbnailUrl, String imageUrl) {
    }

    private record Cached(List<Suggestion> suggestions, Instant until) {
    }

    private final SafeDownloader downloader;
    private final URI searchUrl;
    private final URI legacySearchUrl;
    private final Clock clock;
    private final List<Duration> legacyRetryDelays;
    private final Map<String, Cached> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
            return size() > MAX_CACHED;
        }
    };

    public ProductPhotoFinder(SafeDownloader downloader, URI searchUrl, URI legacySearchUrl, Clock clock) {
        this(downloader, searchUrl, legacySearchUrl, clock, LEGACY_RETRY_DELAYS);
    }

    ProductPhotoFinder(SafeDownloader downloader, URI searchUrl, URI legacySearchUrl, Clock clock,
            List<Duration> legacyRetryDelays) {
        this.legacyRetryDelays = legacyRetryDelays;
        this.downloader = downloader;
        this.searchUrl = searchUrl;
        this.legacySearchUrl = legacySearchUrl;
        this.clock = clock;
    }

    /** Up to {@link #MAX_SUGGESTIONS} photos of products matching the name and (optional) brand, best first. */
    public List<Suggestion> find(String name, String brand) {
        String words = words(name);
        String brandWords = words(brand);
        if (words.isEmpty()) {
            return List.of();
        }
        String key = words.toLowerCase(Locale.ROOT) + "|" + brandWords.toLowerCase(Locale.ROOT);
        synchronized (cache) {
            Cached cached = cache.get(key);
            if (cached != null && cached.until().isAfter(clock.instant())) {
                return cached.suggestions();
            }
        }
        List<String> required = significantWords(words);
        Map<String, Suggestion> found = new LinkedHashMap<>();
        boolean complete = true;
        if (!brandWords.isEmpty()) {
            List<String> brandQueries = new ArrayList<>();
            brandQueries.add("brands:\"" + brandWords + "\" " + words);
            String longest = longestWord(brandWords);
            if (longest.length() >= 4) {
                brandQueries.add("brands:" + longest.toLowerCase(Locale.ROOT) + "~1 " + words);
            }
            for (String country : List.of(COUNTRY_FILTER, "")) {
                for (String query : brandQueries) {
                    complete &= collect(query + country, found,
                            hit -> matchesName(hit, required) && similarBrand(hit.brand(), brandWords));
                    if (found.size() >= MAX_SUGGESTIONS) {
                        break;
                    }
                }
                if (!found.isEmpty()) {
                    break;
                }
            }
            if (found.isEmpty()) {
                URI legacy = URI.create(legacySearchUrl + "?search_terms="
                        + URLEncoder.encode(words + " " + brandWords, StandardCharsets.UTF_8)
                        + "&search_simple=1&action=process&json=1&page_size=" + PAGE_SIZE
                        + "&fields=product_name,brands,image_front_url");
                complete &= collect(legacy, "products", found,
                        hit -> matchesName(hit, required) && similarBrand(hit.brand(), brandWords));
            }
        }
        if (found.isEmpty()) {
            complete &= collect(words + COUNTRY_FILTER, found, hit -> matchesName(hit, required));
        }
        List<Suggestion> suggestions = found.values().stream().limit(MAX_SUGGESTIONS).toList();
        if (complete) {
            synchronized (cache) {
                cache.put(key, new Cached(suggestions, clock.instant().plus(CACHE_TIME)));
            }
        }
        return suggestions;
    }

    /** Adds the query's acceptable results to found; false if the search failed. */
    private boolean collect(String query, Map<String, Suggestion> found, java.util.function.Predicate<Suggestion> acceptable) {
        return collect(URI.create(searchUrl + "?q=" + URLEncoder.encode(query + HAS_PHOTO_FILTER, StandardCharsets.UTF_8)
                + "&page_size=" + PAGE_SIZE + "&fields=product_name,brands,image_front_url"), "hits", found, acceptable);
    }

    private boolean collect(URI url, String listField, Map<String, Suggestion> found,
            java.util.function.Predicate<Suggestion> acceptable) {
        String query = url.getRawQuery();
        try {
            for (Suggestion suggestion : searchRetryingWhenBusy(url, listField)) {
                if (acceptable.test(suggestion)) {
                    found.putIfAbsent(suggestion.thumbnailUrl(), suggestion);
                }
            }
            return true;
        } catch (IOException | RuntimeException failed) { // including malformed JSON
            log.info("Product photo search for \"{}\" failed: {}", query, failed.toString());
            return false;
        }
    }

    /** Whether the product's name contains each of the item's words (or a word starting the same way: yogurts). */
    static boolean matchesName(Suggestion hit, List<String> required) {
        List<String> nameWords = List.of(hit.name().toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"));
        return required.stream().allMatch(word -> nameWords.stream().anyMatch(nameWord -> sameWord(nameWord, word)));
    }

    private static boolean sameWord(String candidate, String word) {
        int stem = Math.min(word.length(), 5);
        return candidate.length() >= stem && candidate.regionMatches(0, word, 0, stem)
                && Math.abs(candidate.length() - word.length()) <= 3;
    }

    /** Whether the product's brand is the given one, allowing small spelling differences ("Mapple Hill"). */
    static boolean similarBrand(String hitBrand, String brand) {
        if (hitBrand == null) {
            return false;
        }
        String a = hitBrand.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
        String b = brand.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        if (a.contains(b) || b.contains(a)) {
            return true;
        }
        // Compare with the start of the product's brand that's closest ("Maple Hill Creamery" starts like "Mapple Hill").
        int allowed = Math.max(1, b.length() / 5);
        for (int length = Math.max(1, b.length() - allowed); length <= Math.min(a.length(), b.length() + allowed); length++) {
            if (editDistance(a.substring(0, length), b) <= allowed) {
                return true;
            }
        }
        return false;
    }

    private static int editDistance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j], current[j - 1]) + 1);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    private static List<String> significantWords(String words) {
        return List.of(words.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")).stream()
                .filter(word -> word.length() >= 3 && !FILLER_WORDS.contains(word))
                .distinct()
                .toList();
    }

    private static String longestWord(String words) {
        String longest = "";
        for (String word : words.split(" ")) {
            if (word.length() > longest.length()) {
                longest = word;
            }
        }
        return longest;
    }

    /** The older search service answers 503 now and then when busy; it's asked again after a pause. */
    private List<Suggestion> searchRetryingWhenBusy(URI url, String listField) throws IOException {
        boolean retry = url.toString().startsWith(legacySearchUrl.toString());
        for (int attempt = 0; ; attempt++) {
            try {
                return search(url, listField);
            } catch (BusyException busy) {
                if (!retry || attempt >= legacyRetryDelays.size()) {
                    throw busy;
                }
                try {
                    Thread.sleep(legacyRetryDelays.get(attempt));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw busy;
                }
            }
        }
    }

    private static final class BusyException extends IOException {
        BusyException(int status) {
            super("HTTP " + status + " (busy)");
        }
    }

    private List<Suggestion> search(URI url, String listField) throws IOException {
        SafeDownloader.Download download = downloader.download(url, MAX_RESPONSE_BYTES, "application/json");
        if (download.status() == 503 || download.status() == 429) {
            throw new BusyException(download.status());
        }
        if (download.status() != 200 || download.truncated()) {
            throw new IOException("HTTP " + download.status() + (download.truncated() ? " (too long)" : ""));
        }
        List<Suggestion> suggestions = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode hit : JSON.readTree(download.body()).path(listField)) {
            String thumbnail = hit.path("image_front_url").asString("");
            String productName = hit.path("product_name").asString("").strip();
            if (!thumbnail.startsWith("https://") || productName.isEmpty() || !seen.add(thumbnail)) {
                continue;
            }
            suggestions.add(new Suggestion(productName, firstBrand(hit.path("brands")), thumbnail, fullSize(thumbnail)));
        }
        return suggestions;
    }

    private static String firstBrand(JsonNode brands) {
        String brand = brands.isArray() ? (brands.isEmpty() ? "" : brands.get(0).asString(""))
                : brands.asString("").split(",")[0];
        brand = brand.strip();
        return brand.isEmpty() ? null : brand;
    }

    /** Open Food Facts names sizes in the address: front_en.58.400.jpg is 400 px, front_en.58.full.jpg the original. */
    static String fullSize(String thumbnail) {
        return thumbnail.replaceFirst("\\.(?:100|200|400)\\.jpg$", ".full.jpg");
    }

    private static String words(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll(QUERY_SYNTAX, " ").replaceAll("\\s+", " ").strip();
    }
}
