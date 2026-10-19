package com.abcconsulting.slme.server.stores;

import java.net.URI;
import java.util.List;
import java.util.stream.Stream;

/**
 * A store HList recognises in list names.
 *
 * @param key          stable identifier used in URLs, e.g. "homedepot"
 * @param name         display name
 * @param aliases      words or phrases that identify the store in a list name, matched as whole words ignoring case
 *                     and punctuation
 * @param site         the store's website, the only place its icon is fetched from
 * @param otherDomains further websites of the same store, e.g. "costcobusinessdelivery.com" for Costco
 */
public record Store(String key, String name, List<String> aliases, URI site, List<String> otherDomains) {

    public Store(String key, String name, List<String> aliases, URI site) {
        this(key, name, aliases, site, List.of());
    }

    public Store withOtherDomains(String... domains) {
        return new Store(key, name, aliases, site, List.of(domains));
    }

    /** The domains of the store's websites, without "www.": products from these pages belong to this store. */
    public List<String> domains() {
        return Stream.concat(Stream.of(site.getHost().replaceFirst("^www\\.", "")), otherDomains.stream()).toList();
    }
}
