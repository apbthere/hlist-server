package com.abcconsulting.slme.server.web;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Downloads from addresses on the internet for features that fetch on a user's behalf (store icons, item photos
 * from web links). Only HTTPS addresses that the {@link AddressPolicy} allows are contacted, redirects are followed
 * manually so every hop is checked, and downloads are size- and time-limited.
 */
public class SafeDownloader {

    private static final int MAX_REDIRECTS = 5;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    // Some sites turn away clients that don't look like a browser.
    private static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 "
            + "(KHTML, like Gecko) Version/18.0 Safari/605.1.15";

    /** A finished download; {@code truncated} when the body was longer than allowed (body then holds the start). */
    public record Download(int status, byte[] body, URI url, String contentType, boolean truncated) {
    }

    private final HttpClient httpClient;
    private final AddressPolicy addressPolicy;
    private final boolean httpsOnly;
    private final String userAgent;

    public SafeDownloader(AddressPolicy addressPolicy) {
        this(addressPolicy, true);
    }

    /** {@code httpsOnly} is false only in tests that serve pages from a local plain-HTTP server. */
    public SafeDownloader(AddressPolicy addressPolicy, boolean httpsOnly) {
        this(addressPolicy, httpsOnly, USER_AGENT);
    }

    /** With a User-Agent of its own, for services that ask apps to identify themselves. */
    public SafeDownloader(AddressPolicy addressPolicy, boolean httpsOnly, String userAgent) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.addressPolicy = addressPolicy;
        this.httpsOnly = httpsOnly;
        this.userAgent = userAgent;
    }

    public Download download(URI start, int maxBytes, String accept) throws IOException {
        URI url = start;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            checkAllowed(url);
            HttpRequest request = HttpRequest.newBuilder(url)
                    .timeout(TIMEOUT)
                    .header("User-Agent", userAgent)
                    .header("Accept", accept)
                    .GET()
                    .build();
            HttpResponse<InputStream> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted", interrupted);
            }
            int status = response.statusCode();
            if (status >= 300 && status < 400 && status != 304) {
                response.body().close();
                String location = response.headers().firstValue("Location")
                        .orElseThrow(() -> new IOException("Redirect without Location from " + request.uri()));
                url = request.uri().resolve(location.trim());
                continue;
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
            try (InputStream body = response.body()) {
                byte[] bytes = body.readNBytes(maxBytes + 1);
                boolean truncated = bytes.length > maxBytes;
                return new Download(status, truncated ? Arrays.copyOf(bytes, maxBytes) : bytes, url, contentType, truncated);
            }
        }
        throw new IOException("Too many redirects from " + start);
    }

    /** Whether the address uses an allowed scheme; the host is checked when downloading. */
    public boolean allowedScheme(URI url) {
        String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
        return url.getHost() != null && (scheme.equals("https") || (!httpsOnly && scheme.equals("http")));
    }

    private void checkAllowed(URI url) throws IOException {
        if (!allowedScheme(url)) {
            throw new IOException("Not an allowed address: " + url);
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(url.getHost());
        } catch (UnknownHostException unknown) {
            throw new IOException("Unknown host " + url.getHost(), unknown);
        }
        List<InetAddress> blocked = new ArrayList<>();
        for (InetAddress address : addresses) {
            if (!addressPolicy.allows(address)) {
                blocked.add(address);
            }
        }
        if (!blocked.isEmpty()) {
            throw new IOException("Refusing non-public address " + blocked + " for " + url);
        }
    }
}
