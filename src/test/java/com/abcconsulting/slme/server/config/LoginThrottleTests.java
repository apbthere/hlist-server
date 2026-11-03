package com.abcconsulting.slme.server.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class LoginThrottleTests {

	private final LoginThrottle throttle = new LoginThrottle();
	private Instant now = Instant.parse("2026-11-02T12:00:00Z");

	@BeforeEach
	void useTestClock() {
		setNow(now);
	}

	@Test
	void anAccountGetsFiveFailuresPerWindow() {
		for (int attempt = 0; attempt < LoginThrottle.ACCOUNT_LIMIT; attempt++) {
			assertThat(throttle.tryAttempt("198.51.100.1", "alex")).isEmpty();
			setNow(now.plusSeconds(60));
		}

		// From any address, in any capitalization; the first failure frees up 15 minutes after it.
		assertThat(throttle.tryAttempt("198.51.100.2", "ALEX")).contains(Duration.ofMinutes(10));
		setNow(now.plus(Duration.ofMinutes(10)));
		assertThat(throttle.tryAttempt("198.51.100.2", "Alex")).isEmpty();
		assertThat(throttle.tryAttempt("198.51.100.2", "Alex")).isPresent();
	}

	@Test
	void signingInClearsTheAccountsFailures() {
		for (int attempt = 0; attempt < LoginThrottle.ACCOUNT_LIMIT; attempt++) {
			throttle.tryAttempt("198.51.100.1", "alex");
		}
		throttle.succeeded("198.51.100.1", "Alex");

		for (int attempt = 0; attempt < LoginThrottle.ACCOUNT_LIMIT; attempt++) {
			assertThat(throttle.tryAttempt("198.51.100.1", "alex")).isEmpty();
		}
		assertThat(throttle.tryAttempt("198.51.100.1", "alex")).isPresent();
	}

	@Test
	void anAddressGetsTwentyFailuresAcrossAccounts() {
		for (int attempt = 0; attempt < LoginThrottle.ADDRESS_LIMIT; attempt++) {
			assertThat(throttle.tryAttempt("198.51.100.1", "user" + attempt)).isEmpty();
		}

		assertThat(throttle.tryAttempt("198.51.100.1", "someone-else")).contains(LoginThrottle.WINDOW);
		assertThat(throttle.tryAttempt("198.51.100.1", "")).isPresent();
		assertThat(throttle.tryAttempt("198.51.100.9", "someone-else")).isEmpty();
	}

	@Test
	void blockedAttemptsDontExtendTheWait() {
		for (int attempt = 0; attempt < LoginThrottle.ACCOUNT_LIMIT; attempt++) {
			throttle.tryAttempt("198.51.100.1", "alex");
		}
		for (int attempt = 0; attempt < 10; attempt++) {
			setNow(now.plusSeconds(60));
			throttle.tryAttempt("198.51.100.1", "alex");
		}

		setNow(Instant.parse("2026-11-02T12:15:00Z"));
		assertThat(throttle.tryAttempt("198.51.100.1", "alex")).isEmpty();
	}

	@Test
	void oldAttemptsAreForgotten() {
		throttle.tryAttempt("198.51.100.1", "alex");
		setNow(now.plus(LoginThrottle.WINDOW));
		throttle.forgetOldAttempts();

		for (int attempt = 0; attempt < LoginThrottle.ACCOUNT_LIMIT; attempt++) {
			assertThat(throttle.tryAttempt("198.51.100.1", "alex")).isEmpty();
		}
	}

	private void setNow(Instant instant) {
		now = instant;
		throttle.setClock(Clock.fixed(instant, ZoneOffset.UTC));
	}
}
