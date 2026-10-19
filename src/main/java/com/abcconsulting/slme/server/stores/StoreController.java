package com.abcconsulting.slme.server.stores;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/** The stores HList recognises, and their icons. Both are public: they hold nothing about any user. */
@RestController
@RequestMapping("/api")
public class StoreController {

    private final StoreCatalog catalog;
    private final StoreIconService iconService;

    public StoreController(StoreCatalog catalog, StoreIconService iconService) {
        this.catalog = catalog;
        this.iconService = iconService;
    }

    @GetMapping("/stores")
    public List<StoreInfo> stores() {
        return catalog.all().stream()
                .map(store -> new StoreInfo(store.key(), store.name(), store.aliases(), store.site().toString(),
                        store.domains(), "/api/store-icons/" + store.key()))
                .toList();
    }

    @GetMapping("/store-icons/{key}")
    public ResponseEntity<byte[]> icon(@PathVariable String key) {
        return iconService.icon(key)
                .map(icon -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(icon.contentType()))
                        .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                        .body(icon.image()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record StoreInfo(String key, String name, List<String> aliases, String site, List<String> domains,
                            String iconUrl) {
    }
}
