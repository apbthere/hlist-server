package com.abcconsulting.slme.server.live;

import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live-update stream for the signed-in user (Server-Sent Events). Every event is named "change" and carries
 * JSON like {"type":"list-items","listId":12,"origin":"..."}; see {@link DataChangedEvent} for the types.
 */
@RestController
public class LiveUpdateController {

    private final LiveUpdateService liveUpdateService;

    public LiveUpdateController(LiveUpdateService liveUpdateService) {
        this.liveUpdateService = liveUpdateService;
    }

    @GetMapping(path = "/api/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(Authentication authentication) {
        return liveUpdateService.subscribe(authentication.getName());
    }
}
