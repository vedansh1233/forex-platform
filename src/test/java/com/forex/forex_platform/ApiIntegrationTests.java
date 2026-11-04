package com.forex.forex_platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forex.forex_platform.repository.UserRepository;
import com.forex.forex_platform.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end tests of the HTTP API (auth, user, wallet, errors, CORS) against an in-memory H2 database.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiIntegrationTests {

    private static final String PASSWORD = "password123";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WalletRepository walletRepository;

    @BeforeEach
    void cleanDatabase() {
        walletRepository.deleteAll();
        userRepository.deleteAll();
    }

    // --- helpers ---

    private String registerJson(String fullName, String email, String password) {
        return """
                {"fullName":"%s","email":"%s","password":"%s"}""".formatted(fullName, email, password);
    }

    private String register(String email) throws Exception {
        String body = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("Jane Trader", email, PASSWORD)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    // --- auth ---

    @Test
    void registerReturnsTokenAndUser() throws Exception {
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("Jane Trader", "Jane@Example.com ", PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(3600))
                .andExpect(jsonPath("$.user.email").value("jane@example.com"))
                .andExpect(jsonPath("$.user.fullName").value("Jane Trader"))
                .andExpect(jsonPath("$.user.role").value("USER"))
                .andExpect(jsonPath("$.user.password").doesNotExist());
    }

    @Test
    void registerDuplicateEmailReturns409() throws Exception {
        register("jane@example.com");

        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("Other", "JANE@example.com", PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("Email already registered"));
    }

    @Test
    void registerInvalidInputReturns400WithFieldErrors() throws Exception {
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson("", "not-an-email", "short")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.fullName").exists())
                .andExpect(jsonPath("$.fieldErrors.email").exists())
                .andExpect(jsonPath("$.fieldErrors.password").exists());
    }

    @Test
    void malformedBodyReturns400() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed or missing request body"));
    }

    @Test
    void loginSucceedsWithCaseInsensitiveEmail() throws Exception {
        register("jane@example.com");

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"JANE@example.com","password":"%s"}""".formatted(PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value("jane@example.com"));
    }

    @Test
    void loginWithWrongPasswordOrUnknownEmailReturns401() throws Exception {
        register("jane@example.com");

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"jane@example.com","password":"wrong-password"}"""))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"nobody@example.com","password":"%s"}""".formatted(PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    // --- protected endpoints ---

    @Test
    void protectedEndpointWithoutTokenReturns401Json() throws Exception {
        mvc.perform(get("/api/user/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.path").value("/api/user/me"));
    }

    @Test
    void invalidTokenReturns401() throws Exception {
        mvc.perform(get("/api/user/me").header(HttpHeaders.AUTHORIZATION, bearer("garbage.token.value")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meReturnsCurrentUser() throws Exception {
        String token = register("jane@example.com");

        mvc.perform(get("/api/user/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("jane@example.com"))
                .andExpect(jsonPath("$.fullName").value("Jane Trader"))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    // --- wallet ---

    @Test
    void walletLifecycle() throws Exception {
        String token = register("jane@example.com");

        // No wallet yet
        mvc.perform(get("/api/wallet/my").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Wallet not found"));

        // Create it
        mvc.perform(post("/api/wallet/create").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fullName").value("Jane Trader"))
                .andExpect(jsonPath("$.balance").value(10000))
                .andExpect(jsonPath("$.currency").value("USD"));

        // Creating twice is a conflict
        mvc.perform(post("/api/wallet/create").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Wallet already exists"));

        // Read it back
        mvc.perform(get("/api/wallet/my").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(10000))
                .andExpect(jsonPath("$.currency").value("USD"));
    }

    @Test
    void walletsAreIsolatedPerUser() throws Exception {
        String jane = register("jane@example.com");
        String bob = register("bob@example.com");

        mvc.perform(post("/api/wallet/create").header(HttpHeaders.AUTHORIZATION, bearer(jane)))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/wallet/my").header(HttpHeaders.AUTHORIZATION, bearer(bob)))
                .andExpect(status().isNotFound());
    }

    // --- CORS / infra ---

    @Test
    void corsPreflightFromAllowedOriginSucceeds() throws Exception {
        mvc.perform(options("/api/wallet/my")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"));
    }

    @Test
    void corsPreflightFromUnknownOriginIsRejected() throws Exception {
        mvc.perform(options("/api/wallet/my")
                        .header(HttpHeaders.ORIGIN, "http://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden());
    }

    @Test
    void healthAndApiDocsArePublic() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());

        String docs = mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode spec = objectMapper.readTree(docs);
        org.assertj.core.api.Assertions.assertThat(spec.at("/components/securitySchemes/bearerAuth/scheme").asText())
                .isEqualTo("bearer");
        org.assertj.core.api.Assertions.assertThat(spec.at("/paths").has("/api/wallet/my")).isTrue();
    }
}
