package com.abcconsulting.slme.server.config;

import com.abcconsulting.slme.server.assistant.AccessTokenFilter;
import com.abcconsulting.slme.server.assistant.AccessTokenService;
import com.abcconsulting.slme.server.model.UserAccount;
import com.abcconsulting.slme.server.repository.UserAccountRepository;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.authentication.rememberme.JdbcTokenRepositoryImpl;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;
import org.springframework.security.web.webauthn.api.AuthenticatorSelectionCriteria;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRpEntity;
import org.springframework.security.web.webauthn.api.ResidentKeyRequirement;
import org.springframework.security.web.webauthn.api.UserVerificationRequirement;
import org.springframework.security.web.webauthn.authentication.WebAuthnAuthenticationFilter;
import org.springframework.security.web.webauthn.management.JdbcPublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.JdbcUserCredentialRepository;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;
import org.springframework.security.web.webauthn.management.Webauthn4JRelyingPartyOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;

import jakarta.servlet.DispatcherType;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

    private static final int REMEMBER_ME_SECONDS = (int) Duration.ofDays(30).toSeconds();

    @Bean
    UserDetailsService userDetailsService(
            UserAccountRepository userAccountRepository,
            JdbcTemplate jdbcTemplate) {
        return username -> {
            // Usernames are case-insensitive: "alex" signs in to "Alex", and the session, remember-me token and
            // all data use the name as stored.
            UserAccount userAccount = userAccountRepository.findByUsernameIgnoringCase(username)
                    .orElseThrow(() -> new UsernameNotFoundException("User not found"));
            List<SimpleGrantedAuthority> authorities = jdbcTemplate.query(
                    "SELECT authority FROM authorities WHERE username = ?",
                    (resultSet, rowNumber) -> new SimpleGrantedAuthority(resultSet.getString("authority")),
                    userAccount.username());

            return User.withUsername(userAccount.username())
                    .password(userAccount.password())
                    .disabled(!Boolean.TRUE.equals(userAccount.enabled()))
                    .authorities(authorities)
                    .build();
        };
    }

    @Bean
    PersistentTokenRepository persistentTokenRepository(DataSource dataSource) {
        JdbcTokenRepositoryImpl tokenRepository = new JdbcTokenRepositoryImpl();
        tokenRepository.setDataSource(dataSource);
        return tokenRepository;
    }

    @Bean
    RenewableRememberMeServices rememberMeServices(
            UserDetailsService userDetailsService,
            PersistentTokenRepository persistentTokenRepository) {
        RenewableRememberMeServices rememberMeServices = new RenewableRememberMeServices(
                UUID.randomUUID().toString(), userDetailsService, persistentTokenRepository);
        rememberMeServices.setTokenValiditySeconds(REMEMBER_ME_SECONDS);
        rememberMeServices.setCookieCustomizer(cookie -> cookie.setAttribute("SameSite", "Lax"));
        return rememberMeServices;
    }

    @Bean
    PublicKeyCredentialUserEntityRepository passkeyUserRepository(JdbcOperations jdbcOperations) {
        return new JdbcPublicKeyCredentialUserEntityRepository(jdbcOperations);
    }

    @Bean
    UserCredentialRepository passkeyRepository(JdbcOperations jdbcOperations) {
        return new JdbcUserCredentialRepository(jdbcOperations);
    }

    /**
     * Passkeys always verify the person (Face ID, Touch ID or the device passcode), not just that the device is
     * present, because a passkey replaces the password rather than adding a second factor.
     */
    @Bean
    WebAuthnRelyingPartyOperations relyingPartyOperations(
            PublicKeyCredentialUserEntityRepository passkeyUserRepository,
            UserCredentialRepository passkeyRepository,
            PasskeySettings passkeySettings) {
        Webauthn4JRelyingPartyOperations operations = new Webauthn4JRelyingPartyOperations(
                passkeyUserRepository,
                passkeyRepository,
                PublicKeyCredentialRpEntity.builder().id(passkeySettings.rpId()).name("HList").build(),
                passkeySettings.allowedOrigins());
        operations.setCustomizeCreationOptions(options -> options.authenticatorSelection(
                AuthenticatorSelectionCriteria.builder()
                        .residentKey(ResidentKeyRequirement.REQUIRED)
                        .userVerification(UserVerificationRequirement.REQUIRED)
                        .build()));
        operations.setCustomizeRequestOptions(options -> options.userVerification(UserVerificationRequirement.REQUIRED));
        return operations;
    }

    /**
     * OAuth endpoints that AI assistants' servers call directly: no cookies, so no CSRF, sessions or Basic sign-in
     * (a client's Basic credentials at /oauth/token aren't a user's).
     */
    @Bean
    @Order(1)
    SecurityFilterChain oauthEndpoints(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/oauth/token", "/oauth/register", "/.well-known/oauth-protected-resource",
                        "/.well-known/oauth-protected-resource/**", "/.well-known/oauth-authorization-server",
                        "/.well-known/openid-configuration")
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .build();
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            RenewableRememberMeServices rememberMeServices,
            AccessTokenService accessTokens,
            UserDetailsService userDetailsService) throws Exception {
        // No WWW-Authenticate header, so browsers never show a Basic prompt or cache Basic credentials.
        AuthenticationEntryPoint unauthorized = new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED);
        AuthenticationSuccessHandler signedIn = (request, response, authentication) ->
                response.setStatus(HttpStatus.NO_CONTENT.value());

        return http
                .authorizeHttpRequests(authorize -> authorize
                        // A live-update stream was authorized when it opened; its later async dispatches (e.g. after
                        // the session expired) would otherwise fail on an already committed response.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/users").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf", "/api/auth/registration", "/api/auth/passkeys", "/api/version", "/api/stores", "/api/store-icons/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/app", "/app/**").permitAll()
                        // OAuth for AI assistants: authorize.html signs the user in, then asks to approve.
                        .requestMatchers(HttpMethod.GET, "/oauth/authorize", "/authorize.html", "/api/oauth/requests/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/", "/index.html", "/login.html", "/shopping-lists.html", "/shopping-list-items.html", "/console.html", "/api.js", "/ui-choice.js", "/favicon.ico").permitAll()
                        // Tesla Fleet API checks that this domain hosts the app's public key.
                        .requestMatchers(HttpMethod.GET, "/.well-known/appspecific/com.tesla.3p.public-key.pem").permitAll()
                        // Assistants only: a browser's session cookie doesn't open /mcp (so it needs no CSRF token).
                        .requestMatchers(AccessTokenFilter.MCP_PATH).access((authentication, context) ->
                                new AuthorizationDecision(authentication.get() instanceof AccessTokenFilter.TokenAuthentication))
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login.html")
                        .loginProcessingUrl("/api/auth/login")
                        .successHandler(signedIn)
                        .failureHandler(unauthorized("Invalid username or password"))
                        .permitAll())
                // Passkeys: POST /webauthn/register/options and /webauthn/register add one to the signed-in user;
                // POST /webauthn/authenticate/options and /login/webauthn sign in with one.
                .webAuthn(webAuthn -> webAuthn
                        .disableDefaultRegistrationPage(true)
                        .withObjectPostProcessor(new ObjectPostProcessor<WebAuthnAuthenticationFilter>() {
                            @Override
                            public <O extends WebAuthnAuthenticationFilter> O postProcess(O filter) {
                                // Sign in like the password form does: same responses, the remember-me cookie
                                // (with ?remember-me=true), and a new session ID and CSRF token, which the
                                // passkey configurer doesn't set up on its own.
                                filter.setAuthenticationSuccessHandler(signedIn);
                                filter.setAuthenticationFailureHandler(unauthorized(
                                        "This passkey didn't work. It may have been removed from your account; "
                                                + "sign in with your password instead."));
                                filter.setRememberMeServices(rememberMeServices);
                                filter.setSessionAuthenticationStrategy(
                                        http.getSharedObject(SessionAuthenticationStrategy.class));
                                return filter;
                            }
                        }))
                .rememberMe(rememberMe -> rememberMe
                        .rememberMeServices(rememberMeServices)
                        .key(rememberMeServices.getKey()))
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
                // Basic stays available for curl and tests; browsers use the session cookie.
                .httpBasic(basic -> basic.authenticationEntryPoint(unauthorized))
                // AI assistants use /mcp with an access token (Authorization: Bearer hlist_...).
                .addFilterBefore(new AccessTokenFilter(accessTokens, userDetailsService), BasicAuthenticationFilter.class)
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(withMcpMetadata(unauthorized)))
                .csrf(csrf -> csrf
                        .spa()
                        .ignoringRequestMatchers("/h2-console/**")
                        .ignoringRequestMatchers(AccessTokenFilter.MCP_PATH)
                        // Explicit Authorization headers are never sent automatically cross-site.
                        .ignoringRequestMatchers(request -> request.getHeader(HttpHeaders.AUTHORIZATION) != null))
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .build();
    }

    /** MCP clients find the OAuth server from the 401's WWW-Authenticate header (RFC 9728). */
    private static AuthenticationEntryPoint withMcpMetadata(AuthenticationEntryPoint otherwise) {
        RequestMatcher mcp = PathPatternRequestMatcher.withDefaults().matcher(AccessTokenFilter.MCP_PATH);
        return (request, response, exception) -> {
            if (mcp.matches(request)) {
                response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer resource_metadata=\""
                        + ServletUriComponentsBuilder.fromContextPath(request).replacePath("").build().toUriString()
                        + "/.well-known/oauth-protected-resource/mcp\"");
            }
            otherwise.commence(request, response, exception);
        };
    }

    private static AuthenticationFailureHandler unauthorized(String message) {
        return (request, response, exception) -> {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"message\":\"" + message + "\"}");
        };
    }
}
