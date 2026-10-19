package com.abcconsulting.slme.server.stores;

import com.abcconsulting.slme.server.web.AddressPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StoreConfiguration {

    @Bean
    StoreIconFetcher storeIconFetcher() {
        return new StoreIconFetcher(AddressPolicy.PUBLIC_ONLY);
    }
}
