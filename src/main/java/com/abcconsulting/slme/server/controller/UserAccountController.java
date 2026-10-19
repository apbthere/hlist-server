package com.abcconsulting.slme.server.controller;

import com.abcconsulting.slme.server.config.RenewableRememberMeServices;
import com.abcconsulting.slme.server.service.RegistrationPolicy;
import com.abcconsulting.slme.server.service.UserAccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserAccountController {

    private final UserAccountService userAccountService;
    private final RenewableRememberMeServices rememberMeServices;
    private final RegistrationPolicy registrationPolicy;

    public UserAccountController(
            UserAccountService userAccountService,
            RenewableRememberMeServices rememberMeServices,
            RegistrationPolicy registrationPolicy) {
        this.userAccountService = userAccountService;
        this.rememberMeServices = rememberMeServices;
        this.registrationPolicy = registrationPolicy;
    }

    @PostMapping
    public ResponseEntity<UserCreatedResponse> createUser(@Valid @RequestBody CreateUserRequest request) {
        registrationPolicy.checkInviteCode(request.inviteCode());
        userAccountService.createUser(request.username(), request.password());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new UserCreatedResponse(request.username()));
    }

    @GetMapping("/me")
    public CurrentUserResponse currentUser(Authentication authentication) {
        return new CurrentUserResponse(authentication.getName());
    }

    @PutMapping("/me/password")
    public ResponseEntity<Void> changePassword(
            Authentication authentication,
            @Valid @RequestBody ChangePasswordRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        userAccountService.changeUserPassword(
                authentication.getName(), request.currentPassword(), request.newPassword());
        rememberMeServices.revokeOtherDevices(httpRequest, httpResponse, authentication);
        return ResponseEntity.noContent().build();
    }

    public record CreateUserRequest(
            @NotBlank @Size(max = 50) String username,
            @NotBlank String password,
            @Size(max = 200) String inviteCode) {
    }

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank String newPassword) {
    }

    public record UserCreatedResponse(String username) {
    }

    public record CurrentUserResponse(String username) {
    }
}