package com.abcconsulting.slme.server.live;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LiveUpdateServiceTests {

	@Test
	void aBrokenStreamIsDroppedWithoutStoppingTheOthers() {
		LiveUpdateService service = new LiveUpdateService();
		// A stream whose client went away: writing fails. It must not be completed from this thread, because
		// Tomcat rejects that once it has failed the request itself (the error seen in the server log).
		AtomicBoolean completedByService = new AtomicBoolean();
		SseEmitter broken = new SseEmitter() {
			@Override
			public void send(SseEventBuilder builder) throws IOException {
				throw new IOException("Broken pipe");
			}

			@Override
			public void completeWithError(Throwable exception) {
				completedByService.set(true);
				throw new IllegalStateException("A non-container (application) thread attempted to use the AsyncContext");
			}
		};
		AtomicInteger healthySends = new AtomicInteger();
		SseEmitter healthy = new SseEmitter() {
			@Override
			public void send(SseEventBuilder builder) {
				healthySends.incrementAndGet();
			}
		};
		service.register("alex", broken);
		service.register("alex", healthy);

		assertDoesNotThrow(service::heartbeat);
		assertDoesNotThrow(service::heartbeat);

		assertFalse(completedByService.get(), "a failed stream is left for the container to finish");
		assertEquals(2, healthySends.get(), "other streams keep getting keep-alives");
		assertEquals(1, service.openStreams("alex"), "the broken stream is forgotten");
	}
}
