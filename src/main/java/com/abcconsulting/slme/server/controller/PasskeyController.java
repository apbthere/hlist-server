package com.abcconsulting.slme.server.controller;

import com.abcconsulting.slme.server.config.PasskeySettings;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Lists and removes the signed-in user's passkeys. Adding one and signing in with one are handled by Spring
 * Security (see SecurityConfiguration).
 */
@RestController
public class PasskeyController {

    private final PublicKeyCredentialUserEntityRepository passkeyUserRepository;
    private final UserCredentialRepository passkeyRepository;
    private final PasskeySettings passkeySettings;

    public PasskeyController(
            PublicKeyCredentialUserEntityRepository passkeyUserRepository,
            UserCredentialRepository passkeyRepository,
            PasskeySettings passkeySettings) {
        this.passkeyUserRepository = passkeyUserRepository;
        this.passkeyRepository = passkeyRepository;
        this.passkeySettings = passkeySettings;
    }

    /** The pages that can use passkeys, so sign-in forms only offer them where the browser will accept them. */
    @GetMapping("/api/auth/passkeys")
    public PasskeySupport passkeySupport() {
        return new PasskeySupport(passkeySettings.allowedOrigins().stream().sorted().toList());
    }

    @GetMapping("/api/users/me/passkeys")
    public List<PasskeyResponse> passkeys(Authentication authentication) {
        return ownPasskeys(authentication).stream()
                .sorted(Comparator.comparing(CredentialRecord::getCreated, Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(PasskeyResponse::from)
                .toList();
    }

    @DeleteMapping("/api/users/me/passkeys/{id}")
    public ResponseEntity<Void> deletePasskey(Authentication authentication, @PathVariable String id) {
        CredentialRecord passkey = ownPasskeys(authentication).stream()
                .filter(record -> record.getCredentialId().toBase64UrlString().equals(id))
                .findFirst()
                .orElse(null);
        if (passkey == null) {
            return ResponseEntity.notFound().build();
        }
        passkeyRepository.delete(passkey.getCredentialId());
        return ResponseEntity.noContent().build();
    }

    private List<CredentialRecord> ownPasskeys(Authentication authentication) {
        PublicKeyCredentialUserEntity user = passkeyUserRepository.findByUsername(authentication.getName());
        return user == null ? List.of() : passkeyRepository.findByUserId(user.getId());
    }

    public record PasskeySupport(List<String> origins) {
    }

    public record PasskeyResponse(String id, String label, Instant created, Instant lastUsed) {

        static PasskeyResponse from(CredentialRecord record) {
            Bytes id = record.getCredentialId();
            return new PasskeyResponse(id.toBase64UrlString(), record.getLabel(), record.getCreated(), record.getLastUsed());
        }
    }
}
