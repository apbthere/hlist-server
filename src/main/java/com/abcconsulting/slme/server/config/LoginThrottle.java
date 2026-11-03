package com.abcconsulting.slme.server.config;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Limits password guessing: at most {@link #ACCOUNT_LIMIT} failed password sign-ins per account and
 * {@link #ADDRESS_LIMIT} per client address in any {@link #WINDOW}. Every attempt counts until it succeeds, so
 * guesses sent in parallel can't get past the limit before the first one fails. Passkeys and remember-me cookies
 * are never limited, so someone guessing at an account doesn't lock its owner out of devices already signed in.
 */
@Component
public class LoginThrottle {

    static final int ACCOUNT_LIMIT = 5;
    static final int ADDRESS_LIMIT = 20;
    static final Duration WINDOW = Duration.ofMinutes(15);

    private final Map<String, Deque<Instant>> attempts = new HashMap<>();
    private Clock clock = Clock.systemUTC();

    void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Records a password attempt from the address for the username (blank when none was given), or, when either has
     * used up its attempts, records nothing and returns how long until the next one is allowed.
     */
    public synchronized Optional<Duration> tryAttempt(String address, String username) {
        Instant now = clock.instant();
        String accountKey = accountKey(username);
        Duration wait = max(waitTime(addressKey(address), ADDRESS_LIMIT, now),
                accountKey == null ? Duration.ZERO : waitTime(accountKey, ACCOUNT_LIMIT, now));
        if (wait.isPositive()) {
            return Optional.of(wait);
        }
        attempts.computeIfAbsent(addressKey(address), key -> new ArrayDeque<>()).addLast(now);
        if (accountKey != null) {
            attempts.computeIfAbsent(accountKey, key -> new ArrayDeque<>()).addLast(now);
        }
        return Optional.empty();
    }

    /** The attempt was the right password: it doesn't count, and nor do the account's earlier failures. */
    public synchronized void succeeded(String address, String username) {
        Deque<Instant> fromAddress = attempts.get(addressKey(address));
        if (fromAddress != null) {
            fromAddress.pollLast();
        }
        String accountKey = accountKey(username);
        if (accountKey != null) {
            attempts.remove(accountKey);
        }
    }

    @Scheduled(fixedDelay = 10, timeUnit = TimeUnit.MINUTES)
    synchronized void forgetOldAttempts() {
        Instant now = clock.instant();
        attempts.values().removeIf(times -> {
            dropExpired(times, now);
            return times.isEmpty();
        });
    }

    private Duration waitTime(String key, int limit, Instant now) {
        Deque<Instant> times = attempts.get(key);
        if (times == null) {
            return Duration.ZERO;
        }
        dropExpired(times, now);
        if (times.size() < limit) {
            return Duration.ZERO;
        }
        // The oldest attempts in the window have to expire until one is free.
        Instant freed = times.stream().skip(times.size() - limit).findFirst().orElseThrow().plus(WINDOW);
        return Duration.between(now, freed);
    }

    private static void dropExpired(Deque<Instant> times, Instant now) {
        while (!times.isEmpty() && !times.peekFirst().plus(WINDOW).isAfter(now)) {
            times.pollFirst();
        }
    }

    private static Duration max(Duration first, Duration second) {
        return first.compareTo(second) >= 0 ? first : second;
    }

    private static String addressKey(String address) {
        return "address:" + address;
    }

    private static String accountKey(String username) {
        return username == null || username.isBlank() ? null : "account:" + username.strip().toLowerCase(Locale.ROOT);
    }
}
