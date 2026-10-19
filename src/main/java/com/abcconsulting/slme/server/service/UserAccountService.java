package com.abcconsulting.slme.server.service;

import com.abcconsulting.slme.server.model.UserAccount;
import com.abcconsulting.slme.server.repository.UserAccountRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.NoSuchElementException;

@Service
@Validated
public class UserAccountService {

    private final UserAccountRepository userAccountRepository;
    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;

    public UserAccountService(
            UserAccountRepository userAccountRepository,
            JdbcTemplate jdbcTemplate,
            PasswordEncoder passwordEncoder) {
        this.userAccountRepository = userAccountRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public void createUser(
            @NotBlank @Size(max = 50) String username,
            @NotBlank String rawPassword) {
        // Names differing only in capitalization would sign in to the same account, so they count as taken.
        if (userAccountRepository.existsById(username)
                || !userAccountRepository.findAllByUsernameIgnoringCase(username).isEmpty()) {
            throw new IllegalArgumentException("Username already exists");
        }

        jdbcTemplate.update(
            "INSERT INTO users (username, password, enabled) VALUES (?, ?, ?)",
            username, passwordEncoder.encode(rawPassword), true);
    }

        @Transactional
    public void changeUserPassword(
            @NotBlank @Size(max = 50) String username,
            @NotBlank String currentPassword,
            @NotBlank String newPassword) {
        UserAccount userAccount = userAccountRepository.findById(username)
                .orElseThrow(() -> new NoSuchElementException("User not found"));

        if (!passwordEncoder.matches(currentPassword, userAccount.password())) {
            throw new IllegalArgumentException("Current password is incorrect");
        }

        int updatedRows = jdbcTemplate.update(
            "UPDATE users SET password = ? WHERE username = ?",
            passwordEncoder.encode(newPassword), userAccount.username());
        if (updatedRows == 0) {
            throw new NoSuchElementException("User not found");
        }
    }
}