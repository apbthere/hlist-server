package com.abcconsulting.slme.server.stores;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds the icons a web page declares in its {@code <link rel="...icon">} tags, best first. */
final class IconPageParser {

    private static final Pattern LINK_TAG = Pattern.compile("<link\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))");
    private static final Pattern SIZE = Pattern.compile("(\\d+)x(\\d+)", Pattern.CASE_INSENSITIVE);

    /** iOS home-screen icons are 180 px unless declared otherwise; plain icons without sizes are usually small. */
    private static final int DEFAULT_APPLE_TOUCH_SIZE = 180;
    private static final int DEFAULT_ICON_SIZE = 32;

    record Candidate(URI url, int size, boolean appleTouch) {
    }

    /** Largest first; on equal size, home-screen (apple-touch) icons first. */
    static final Comparator<Candidate> BEST_FIRST = Comparator.comparingInt((Candidate candidate) -> candidate.size())
            .reversed()
            .thenComparing((Candidate candidate) -> candidate.appleTouch(), Comparator.reverseOrder());

    private IconPageParser() {
    }

    static List<Candidate> parse(String html, URI pageUrl) {
        List<Candidate> candidates = new ArrayList<>();
        Matcher tags = LINK_TAG.matcher(html);
        while (tags.find()) {
            Map<String, String> attributes = attributes(tags.group());
            List<String> rel = List.of(attributes.getOrDefault("rel", "").toLowerCase(Locale.ROOT).split("\\s+"));
            boolean appleTouch = rel.contains("apple-touch-icon") || rel.contains("apple-touch-icon-precomposed");
            if (!appleTouch && !rel.contains("icon")) {
                continue;
            }
            String href = unescape(attributes.getOrDefault("href", "").trim());
            String type = attributes.getOrDefault("type", "").toLowerCase(Locale.ROOT);
            // SVG is skipped: served from this server's origin it could carry script.
            if (href.isEmpty() || type.contains("svg") || href.toLowerCase(Locale.ROOT).split("[?#]")[0].endsWith(".svg")) {
                continue;
            }
            URI url;
            try {
                url = pageUrl.resolve(href);
            } catch (IllegalArgumentException invalid) {
                continue;
            }
            int size = declaredSize(attributes.get("sizes"));
            candidates.add(new Candidate(url, size > 0 ? size : appleTouch ? DEFAULT_APPLE_TOUCH_SIZE : DEFAULT_ICON_SIZE, appleTouch));
        }
        candidates.sort(BEST_FIRST);
        return candidates;
    }

    private static int declaredSize(String sizes) {
        if (sizes == null) {
            return 0;
        }
        int largest = 0;
        Matcher size = SIZE.matcher(sizes);
        while (size.find()) {
            largest = Math.max(largest, Math.min(Integer.parseInt(size.group(1)), Integer.parseInt(size.group(2))));
        }
        return largest;
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

    private static String unescape(String value) {
        return value.replace("&amp;", "&").replace("&#38;", "&").replace("&quot;", "\"").replace("&#39;", "'");
    }
}
