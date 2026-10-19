package com.abcconsulting.slme.server.stores;

import com.abcconsulting.slme.server.stores.StoreIconRepository.StoredIcon;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Store icons, fetched from the store's website the first time they're asked for and then served from the
 * database. Stores without a usable icon are retried after a day; icons are refreshed monthly, keeping the old
 * one when a refresh fails.
 */
@Service
public class StoreIconService {

    static final Duration RETRY_MISSING_AFTER = Duration.ofDays(1);
    static final Duration REFRESH_AFTER = Duration.ofDays(30);

    private final StoreCatalog catalog;
    private final StoreIconFetcher fetcher;
    private final StoreIconRepository repository;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    private Clock clock = Clock.systemDefaultZone();

    public StoreIconService(StoreCatalog catalog, StoreIconFetcher fetcher, StoreIconRepository repository) {
        this.catalog = catalog;
        this.fetcher = fetcher;
        this.repository = repository;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    /** The icon for a known store, fetching it on first use; empty for unknown stores or when none was found. */
    public Optional<StoredIcon> icon(String storeKey) {
        Optional<Store> store = catalog.find(storeKey);
        if (store.isEmpty()) {
            return Optional.empty();
        }
        // One fetch per store at a time, so simultaneous first requests don't all hit the store's site.
        synchronized (locks.computeIfAbsent(storeKey, key -> new Object())) {
            Optional<StoredIcon> stored = repository.find(storeKey);
            if (stored.isPresent() && stored.get().found()) {
                return stored;
            }
            if (stored.isPresent() && stored.get().fetchedAt().isAfter(now().minus(RETRY_MISSING_AFTER))) {
                return Optional.empty();
            }
            return fetchAndStore(store.get(), stored);
        }
    }

    /** Re-fetches icons older than {@link #REFRESH_AFTER}, nightly; a failed refresh keeps the current icon. */
    @Scheduled(cron = "0 30 4 * * *")
    public void refreshStaleIcons() {
        for (Store store : catalog.all()) {
            synchronized (locks.computeIfAbsent(store.key(), key -> new Object())) {
                Optional<StoredIcon> stored = repository.find(store.key());
                if (stored.isPresent() && stored.get().found()
                        && stored.get().fetchedAt().isBefore(now().minus(REFRESH_AFTER))) {
                    fetchAndStore(store, stored);
                }
            }
        }
    }

    private Optional<StoredIcon> fetchAndStore(Store store, Optional<StoredIcon> previous) {
        Optional<StoreIconFetcher.FetchedIcon> fetched = fetcher.fetch(store);
        if (fetched.isPresent()) {
            StoredIcon icon = new StoredIcon(store.key(), fetched.get().image(), fetched.get().contentType(),
                    fetched.get().source().toString(), now());
            repository.save(icon);
            return Optional.of(icon);
        }
        if (previous.isPresent() && previous.get().found()) {
            // Keep serving the icon we have; it is tried again on the next nightly run.
            return previous;
        }
        repository.save(new StoredIcon(store.key(), null, null, null, now()));
        return Optional.empty();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
