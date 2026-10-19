package com.abcconsulting.slme.server.controller;

import com.abcconsulting.slme.server.service.RegistrationPolicy;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Login and logout are handled by Spring Security at /api/auth/login and /api/auth/logout.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final RegistrationPolicy registrationPolicy;

    public AuthController(RegistrationPolicy registrationPolicy) {
        this.registrationPolicy = registrationPolicy;
    }

    /**
     * Loading the token makes Spring Security write the XSRF-TOKEN cookie that pages echo back in X-XSRF-TOKEN.
     */
    @GetMapping("/csrf")
    public ResponseEntity<Void> csrf(CsrfToken csrfToken) {
        csrfToken.getToken();
        return ResponseEntity.noContent().build();
    }

    /** Tells sign-up forms whether to ask for an invite code. */
    @GetMapping("/registration")
    public RegistrationInfo registration() {
        return new RegistrationInfo(registrationPolicy.inviteRequired());
    }

    public record RegistrationInfo(boolean inviteRequired) {
    }
}
