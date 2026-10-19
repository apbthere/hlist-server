package com.abcconsulting.slme.server.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Which build is running, shown as a watermark in the UIs. The values come from the Docker image
 * (HLIST_BUILD_SERVER_COMMIT, HLIST_BUILD_CLIENT_COMMIT, HLIST_BUILD_TIME, set by deploy/qnap/build-image.sh);
 * a server started from source reports "dev".
 */
@RestController
public class BuildInfoController {

    private final BuildInfo buildInfo;

    public BuildInfoController(
            @Value("${hlist.build.server-commit:dev}") String serverCommit,
            @Value("${hlist.build.client-commit:dev}") String clientCommit,
            @Value("${hlist.build.time:}") String builtAt) {
        this.buildInfo = new BuildInfo(serverCommit, clientCommit, builtAt.isBlank() ? null : builtAt);
    }

    @GetMapping("/api/version")
    public BuildInfo version() {
        return buildInfo;
    }

    public record BuildInfo(String serverCommit, String clientCommit, String builtAt) {
    }
}
