package com.abcconsulting.slme.server.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Controls who may create an account. When {@code hlist.invite-code} (env HLIST_INVITE_CODE) is set,
 * sign-up requires that code; otherwise anyone can register.
 */
@Component
public class RegistrationPolicy {

    private final String inviteCode;

    public RegistrationPolicy(@Value("${hlist.invite-code:}") String inviteCode) {
        this.inviteCode = inviteCode.trim();
    }

    public boolean inviteRequired() {
        return !inviteCode.isEmpty();
    }

    public void checkInviteCode(String providedCode) {
        if (!inviteRequired()) {
            return;
        }
        String provided = providedCode == null ? "" : providedCode.trim();
        if (!MessageDigest.isEqual(
                inviteCode.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException(
                    provided.isEmpty() ? "An invite code is required to create an account" : "Invite code is incorrect");
        }
    }
}
