package com.abcconsulting.slme.server.stores;

import com.abcconsulting.slme.server.web.AddressPolicy;
import com.abcconsulting.slme.server.web.SafeDownloader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Downloads a store's icon: the largest icon its home page declares, then /apple-touch-icon.png, then /favicon.ico.
 * Downloads go through {@link SafeDownloader}: HTTPS, public addresses only, size- and time-limited.
 */
public class StoreIconFetcher {

    static final int MAX_PAGE_BYTES = 2 * 1024 * 1024;
    static final int MAX_ICON_BYTES = 512 * 1024;

    private static final Logger log = LoggerFactory.getLogger(StoreIconFetcher.class);

    public record FetchedIcon(byte[] image, String contentType, URI source) {
    }

    private final SafeDownloader downloader;

    public StoreIconFetcher(AddressPolicy addressPolicy) {
        this(addressPolicy, true);
    }

    /** {@code httpsOnly} is false only in tests that serve pages from a local plain-HTTP server. */
    StoreIconFetcher(AddressPolicy addressPolicy, boolean httpsOnly) {
        this.downloader = new SafeDownloader(addressPolicy, httpsOnly);
    }

    public Optional<FetchedIcon> fetch(Store store) {
        List<IconPageParser.Candidate> ranked = new ArrayList<>();
        try {
            SafeDownloader.Download page = downloader.download(store.site(), MAX_PAGE_BYTES, "text/html,application/xhtml+xml");
            if (page.status() == 200) {
                String html = new String(page.body(), StandardCharsets.UTF_8);
                ranked.addAll(IconPageParser.parse(html, page.url()));
            } else {
                log.info("Store page {} returned HTTP {}", store.site(), page.status());
            }
        } catch (IOException | RuntimeException exception) {
            log.info("Couldn't read store page {}: {}", store.site(), exception.getMessage());
        }
        // Many sites serve a 180 px home-screen icon at one of the standard paths without declaring it (Costco, Lowe's);
        // it beats small declared favicons.
        ranked.add(new IconPageParser.Candidate(store.site().resolve("/apple-touch-icon.png"), 180, true));
        ranked.add(new IconPageParser.Candidate(store.site().resolve("/apple-touch-icon-precomposed.png"), 180, true));
        ranked.sort(IconPageParser.BEST_FIRST);
        Set<URI> candidates = new LinkedHashSet<>();
        ranked.forEach(candidate -> candidates.add(candidate.url()));
        candidates.add(store.site().resolve("/favicon.ico"));

        for (URI candidate : candidates) {
            try {
                SafeDownloader.Download icon = downloader.download(candidate, MAX_ICON_BYTES, "image/*");
                if (icon.status() != 200 || icon.truncated()) {
                    continue;
                }
                Optional<String> type = ImageType.detect(icon.body());
                if (type.isPresent()) {
                    return Optional.of(new FetchedIcon(icon.body(), type.get(), icon.url()));
                }
            } catch (IOException | RuntimeException exception) {
                log.debug("Icon candidate {} failed: {}", candidate, exception.getMessage());
            }
        }
        log.info("No usable icon found for {}", store.key());
        return Optional.empty();
    }

}
