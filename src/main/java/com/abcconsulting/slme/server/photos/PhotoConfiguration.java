package com.abcconsulting.slme.server.photos;

import com.abcconsulting.slme.server.web.AddressPolicy;
import com.abcconsulting.slme.server.web.SafeDownloader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.time.Clock;

@Configuration
class PhotoConfiguration {

    @Bean
    PhotoImporter photoImporter() {
        return new PhotoImporter(new SafeDownloader(AddressPolicy.PUBLIC_ONLY));
    }

    @Bean
    ProductPhotoFinder productPhotoFinder() {
        // Open Food Facts asks apps to identify themselves.
        SafeDownloader downloader = new SafeDownloader(AddressPolicy.PUBLIC_ONLY, true, "HList/1.0 (self-hosted shopping list)");
        return new ProductPhotoFinder(downloader, URI.create("https://search.openfoodfacts.org/search"),
                URI.create("https://world.openfoodfacts.org/cgi/search.pl"), Clock.systemUTC());
    }
}
