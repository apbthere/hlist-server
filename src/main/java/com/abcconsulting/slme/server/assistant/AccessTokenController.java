package com.abcconsulting.slme.server.assistant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The signed-in user's access tokens for AI assistants (see {@link AccessTokenService}). */
@RestController
@RequestMapping("/api/users/me/access-tokens")
public class AccessTokenController {

    private final AccessTokenService tokens;

    public AccessTokenController(AccessTokenService tokens) {
        this.tokens = tokens;
    }

    @GetMapping
    public List<AccessTokenService.AccessToken> list(Authentication authentication) {
        return tokens.list(authentication.getName());
    }

    /** Creates a token; the response is the only time its secret is shown. */
    @PostMapping
    public ResponseEntity<AccessTokenService.CreatedToken> create(
            Authentication authentication, @Valid @RequestBody(required = false) CreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(tokens.create(authentication.getName(), request == null ? null : request.label()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(Authentication authentication, @PathVariable long id) {
        return tokens.delete(id, authentication.getName())
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    public record CreateRequest(@Size(max = 100) String label) {
    }
}
