package com.abcconsulting.slme.server.stores;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** The stores HList recognises, and matching of list names ("Costco run", "Lowe's") to them. */
@Component
public class StoreCatalog {

    private static final List<Store> STORES = List.of(
            store("publix", "Publix", "https://www.publix.com", "publix"),
            store("costco", "Costco", "https://www.costco.com", "costco")
                    .withOtherDomains("costcobusinessdelivery.com"),
            store("aldi", "Aldi", "https://www.aldi.us", "aldi"),
            store("walmart", "Walmart", "https://www.walmart.com", "walmart", "wal mart"),
            store("target", "Target", "https://www.target.com", "target"),
            store("homedepot", "Home Depot", "https://www.homedepot.com", "home depot", "homedepot"),
            store("lowes", "Lowe's", "https://www.lowes.com", "lowes"));

    public List<Store> all() {
        return STORES;
    }

    public Optional<Store> find(String key) {
        return STORES.stream().filter(store -> store.key().equals(key)).findFirst();
    }

    /** The store named in a list name, if any; with several, the one mentioned first. */
    public Optional<Store> match(String listName) {
        String words = " " + normalize(listName) + " ";
        Store best = null;
        int bestPosition = Integer.MAX_VALUE;
        for (Store store : STORES) {
            for (String alias : store.aliases()) {
                int position = words.indexOf(" " + normalize(alias) + " ");
                if (position >= 0 && position < bestPosition) {
                    best = store;
                    bestPosition = position;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** Lowercase words without accents or punctuation; apostrophes are dropped so "Lowe's" reads "lowes". */
    static String normalize(String text) {
        String plain = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return plain.toLowerCase(Locale.ROOT)
                .replaceAll("['’`]", "")
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }

    private static Store store(String key, String name, String site, String... aliases) {
        return new Store(key, name, List.of(aliases), URI.create(site));
    }
}
