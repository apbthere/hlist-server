package com.abcconsulting.slme.server.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Where passkeys work. A passkey belongs to one domain (the relying party ID), and browsers only offer it to pages
 * on that domain, so this must be the public address people use: {@code hlist.domain} (env HLIST_DOMAIN, the same
 * setting Caddy uses). Without it, passkeys work on localhost only, which browsers treat as secure even over HTTP.
 */
@Component
public class PasskeySettings {

    /** The Vite dev server (npm run dev), which proxies the API to this server. */
    private static final String CLIENT_DEV_ORIGIN = "http://localhost:5173";

    private final String rpId;
    private final Set<String> allowedOrigins;

    public PasskeySettings(
            @Value("${hlist.domain:localhost}") String domain,
            @Value("${hlist.passkeys.origins:}") List<String> origins,
            @Value("${server.port:8080}") int port) {
        this.rpId = domain.trim().toLowerCase();
        Set<String> allowed = new LinkedHashSet<>();
        origins.stream().map(String::trim).filter(origin -> !origin.isEmpty()).forEach(allowed::add);
        if (allowed.isEmpty()) {
            if (rpId.equals("localhost")) {
                allowed.add("http://localhost:" + port);
                allowed.add(CLIENT_DEV_ORIGIN);
            } else {
                allowed.add("https://" + rpId);
            }
        }
        this.allowedOrigins = Set.copyOf(allowed);
    }

    public String rpId() {
        return rpId;
    }

    public Set<String> allowedOrigins() {
        return allowedOrigins;
    }
}
