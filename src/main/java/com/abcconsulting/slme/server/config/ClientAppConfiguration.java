package com.abcconsulting.slme.server.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.time.Duration;

/**
 * Serves the mobile client (the hlist-client project's build output) under /app/, on the same origin as
 * the API so its session, remember-me and CSRF cookies work. Client-side routes fall back to index.html.
 */
@Configuration
public class ClientAppConfiguration implements WebMvcConfigurer {

    private final String clientLocation;

    public ClientAppConfiguration(
            @Value("${hlist.client.location:file:../hlist-client/dist/}") String clientLocation) {
        this.clientLocation = clientLocation.endsWith("/") ? clientLocation : clientLocation + "/";
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/app", "/app/");
        registry.addViewController("/app/").setViewName("forward:/app/index.html");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Build assets have content hashes in their names, so they never change.
        registry.addResourceHandler("/app/assets/**")
                .addResourceLocations(clientLocation + "assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
        registry.addResourceHandler("/app/**")
                .addResourceLocations(clientLocation)
                .resourceChain(true)
                .addResolver(new SinglePageAppResourceResolver());
    }

    private static final class SinglePageAppResourceResolver extends PathResourceResolver {

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource resource = super.getResource(resourcePath, location);
            if (resource != null) {
                return resource;
            }
            // Paths without a file extension are client-side routes such as /app/lists/12.
            String fileName = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
            return fileName.contains(".") ? null : super.getResource("index.html", location);
        }
    }
}
