package com.abcconsulting.slme.server.live;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pushes "this changed" notifications to every open client over Server-Sent Events. Clients reload the
 * affected data through the normal API, so the stream never carries the data itself.
 */
@Service
public class LiveUpdateService {

    /** Request header a client can set on its own changes, echoed as the event's origin so it can skip them. */
    public static final String CLIENT_HEADER = "X-HList-Client";

    /** Streams end after this long; the browser's EventSource reconnects automatically. */
    static final Duration STREAM_TIMEOUT = Duration.ofMinutes(30);

    private static final Logger log = LoggerFactory.getLogger(LiveUpdateService.class);

    private final Map<String, Set<SseEmitter>> emittersByUser = new ConcurrentHashMap<>();

    /** Opens a stream for the user; it receives their own changes and changes to shared data. */
    public SseEmitter subscribe(String username) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());
        register(username, emitter);
        // An immediate event makes the response start, so the client knows it is connected.
        send(username, emitter, SseEmitter.event().name("ready").data(Map.of(), MediaType.APPLICATION_JSON));
        return emitter;
    }

    /** Adds a stream for the user and forgets it once it completes, times out or fails. */
    void register(String username, SseEmitter emitter) {
        emittersByUser.computeIfAbsent(username, key -> ConcurrentHashMap.newKeySet()).add(emitter);
        Runnable remove = () -> remove(username, emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(error -> remove.run());
    }

    /** Forwards a change to the affected clients once it is committed, so a reload sees the new data. */
    @TransactionalEventListener(fallbackExecution = true)
    public void onDataChanged(DataChangedEvent event) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("type", event.type());
        if (event.listId() != null) {
            data.put("listId", event.listId());
        }
        String origin = currentClientId();
        if (origin != null) {
            data.put("origin", origin);
        }
        if (event.username() == null) {
            emittersByUser.forEach((username, emitters) -> sendToAll(username, emitters, data));
        } else {
            sendToAll(event.username(), emittersByUser.getOrDefault(event.username(), Set.of()), data);
        }
    }

    /** Keeps idle streams from being closed by proxies. */
    @Scheduled(fixedDelay = 25_000, initialDelay = 25_000)
    public void heartbeat() {
        emittersByUser.forEach((username, emitters) ->
                List.copyOf(emitters).forEach(emitter -> send(username, emitter, SseEmitter.event().comment("keep-alive"))));
    }

    int openStreams(String username) {
        return emittersByUser.getOrDefault(username, Set.of()).size();
    }

    private void sendToAll(String username, Set<SseEmitter> emitters, Map<String, Object> data) {
        List.copyOf(emitters).forEach(emitter ->
                send(username, emitter, SseEmitter.event().name("change").data(data, MediaType.APPLICATION_JSON)));
    }

    private void send(String username, SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | RuntimeException exception) {
            // The client went away (or the stream already ended); just forget the stream. It must not be completed
            // here: by now the servlet container has failed the request itself and finishes it, and completing it
            // from this (non-container) thread is rejected by Tomcat. The client reconnects if it is still open.
            log.debug("Dropping live-update stream for {}: {}", username, exception.getMessage());
            remove(username, emitter);
        }
    }

    private void remove(String username, SseEmitter emitter) {
        emittersByUser.computeIfPresent(username, (key, emitters) -> {
            emitters.remove(emitter);
            return emitters.isEmpty() ? null : emitters;
        });
    }

    private static String currentClientId() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return null;
        }
        HttpServletRequest request = attributes.getRequest();
        String clientId = request.getHeader(CLIENT_HEADER);
        return clientId == null || clientId.isBlank() || clientId.length() > 100 ? null : clientId;
    }
}
