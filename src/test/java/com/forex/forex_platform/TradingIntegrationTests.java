package com.forex.forex_platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forex.forex_platform.repository.HoldingRepository;
import com.forex.forex_platform.repository.UserRepository;
import com.forex.forex_platform.repository.WalletRepository;
import com.forex.forex_platform.repository.WalletTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Rates, conversions and transaction history. Uses the offline "fixed" rates
 * provider (see src/test/resources/application.properties), so no network is needed.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TradingIntegrationTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private HoldingRepository holdingRepository;

    @Autowired
    private WalletTransactionRepository transactionRepository;

    @BeforeEach
    void cleanDatabase() {
        transactionRepository.deleteAll();
        holdingRepository.deleteAll();
        walletRepository.deleteAll();
        userRepository.deleteAll();
    }

    // --- helpers ---

    private String registerWithWallet(String email) throws Exception {
        String body = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Test Trader","email":"%s","password":"password123"}""".formatted(email)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = "Bearer " + objectMapper.readTree(body).get("token").asText();

        mvc.perform(post("/api/wallet/create").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isCreated());
        return token;
    }

    private JsonNode json(String content) throws Exception {
        return objectMapper.readTree(content);
    }

    private BigDecimal latestRate(String base, String quote) throws Exception {
        String body = mvc.perform(get("/api/rates").param("base", base))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json(body).at("/rates/" + quote).decimalValue();
    }

    private String convertJson(String from, String to, String amount) {
        return """
                {"from":"%s","to":"%s","amount":%s}""".formatted(from, to, amount);
    }

    // --- rates ---

    @Test
    void latestRatesArePublicAndCoverAllOtherCurrencies() throws Exception {
        mvc.perform(get("/api/rates").param("base", "usd"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.base").value("USD"))
                .andExpect(jsonPath("$.source").value("SIMULATED"))
                .andExpect(jsonPath("$.date").isNotEmpty())
                .andExpect(jsonPath("$.rates.USD").doesNotExist())
                .andExpect(jsonPath("$.rates.EUR").isNumber())
                .andExpect(jsonPath("$.rates.JPY").isNumber())
                .andExpect(jsonPath("$.rates.length()").value(7));
    }

    @Test
    void currenciesEndpointListsSupportedCurrencies() throws Exception {
        mvc.perform(get("/api/rates/currencies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("USD"))
                .andExpect(jsonPath("$.length()").value(8));
    }

    @Test
    void unsupportedCurrencyReturns400() throws Exception {
        mvc.perform(get("/api/rates").param("base", "XYZ"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("Unsupported currency")));
    }

    @Test
    void historyReturnsAscendingBusinessDayPoints() throws Exception {
        String body = mvc.perform(get("/api/rates/history")
                        .param("base", "EUR").param("quote", "USD").param("days", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.base").value("EUR"))
                .andExpect(jsonPath("$.quote").value("USD"))
                .andReturn().getResponse().getContentAsString();

        JsonNode points = json(body).get("points");
        assertThat(points.size()).isBetween(18, 24); // ~30 calendar days minus weekends
        for (int i = 1; i < points.size(); i++) {
            assertThat(points.get(i).get("date").asText()).isGreaterThan(points.get(i - 1).get("date").asText());
        }
    }

    @Test
    void historyValidatesParameters() throws Exception {
        mvc.perform(get("/api/rates/history").param("base", "EUR").param("quote", "EUR"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/rates/history").param("base", "EUR").param("quote", "USD").param("days", "1000"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/rates/history").param("base", "EUR"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Missing required parameter 'quote'"));
        mvc.perform(get("/api/rates/history").param("base", "EUR").param("quote", "USD").param("days", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for parameter 'days'"));
    }

    // --- conversion ---

    @Test
    void convertMovesFundsAtLatestRateAndRoundsDown() throws Exception {
        String token = registerWithWallet("trader@example.com");
        BigDecimal rate = latestRate("USD", "EUR");
        BigDecimal expectedEur = new BigDecimal("1000").multiply(rate).setScale(2, RoundingMode.DOWN);

        String body = mvc.perform(post("/api/wallet/convert")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(convertJson("USD", "EUR", "1000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transaction.type").value("CONVERSION"))
                .andExpect(jsonPath("$.transaction.fromCurrency").value("USD"))
                .andExpect(jsonPath("$.transaction.toCurrency").value("EUR"))
                .andExpect(jsonPath("$.wallet.balance").value(9000))
                .andExpect(jsonPath("$.wallet.holdings[0].currency").value("USD"))
                .andExpect(jsonPath("$.wallet.holdings[1].currency").value("EUR"))
                .andReturn().getResponse().getContentAsString();

        JsonNode response = json(body);
        assertThat(response.at("/transaction/toAmount").decimalValue()).isEqualByComparingTo(expectedEur);
        assertThat(response.at("/wallet/holdings/1/amount").decimalValue()).isEqualByComparingTo(expectedEur);

        // Converting back and forth at mid rates loses at most rounding cents
        BigDecimal total = response.at("/wallet/totalValue").decimalValue();
        assertThat(total).isBetween(new BigDecimal("9999.98"), new BigDecimal("10000.00"));
        assertThat(response.at("/wallet/valuationDate").asText()).isNotBlank();

        // The GET endpoint reflects the same state
        mvc.perform(get("/api/wallet/my").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(9000))
                .andExpect(jsonPath("$.holdings.length()").value(2));
    }

    @Test
    void crossConversionBetweenNonBaseCurrencies() throws Exception {
        String token = registerWithWallet("trader@example.com");

        mvc.perform(post("/api/wallet/convert").header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(convertJson("USD", "EUR", "500")))
                .andExpect(status().isOk());

        mvc.perform(post("/api/wallet/convert").header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(convertJson("eur", "gbp", "100")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transaction.fromCurrency").value("EUR"))
                .andExpect(jsonPath("$.transaction.toCurrency").value("GBP"))
                .andExpect(jsonPath("$.wallet.holdings.length()").value(3));
    }

    @Test
    void convertRejectsInsufficientFunds() throws Exception {
        String token = registerWithWallet("trader@example.com");

        mvc.perform(post("/api/wallet/convert").header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(convertJson("USD", "EUR", "10000.01")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Insufficient USD balance: 10000.00 available"));

        // A currency never held has zero balance
        mvc.perform(post("/api/wallet/convert").header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(convertJson("JPY", "USD", "1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Insufficient JPY balance: 0.00 available"));
    }

    @Test
    void convertValidatesInput() throws Exception {
        String token = registerWithWallet("trader@example.com");

        mvc.perform(post("/api/wallet/convert").header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(convertJson("USD", "USD", "10")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Choose two different currencies"));

        mvc.perform(post("/api/wallet/convert").header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(convertJson("USD", "BTC", "10")))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/wallet/convert").header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(convertJson("USD", "EUR", "0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.amount").exists());

        mvc.perform(post("/api/wallet/convert").header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(convertJson("USD", "EUR", "1.001")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.amount").value("Amount can have at most 2 decimal places"));
    }

    @Test
    void convertRequiresAuthAndAWallet() throws Exception {
        mvc.perform(post("/api/wallet/convert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(convertJson("USD", "EUR", "10")))
                .andExpect(status().isUnauthorized());

        String body = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"No Wallet","email":"nowallet@example.com","password":"password123"}"""))
                .andReturn().getResponse().getContentAsString();
        String token = "Bearer " + json(body).get("token").asText();

        mvc.perform(post("/api/wallet/convert").header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(convertJson("USD", "EUR", "10")))
                .andExpect(status().isNotFound());
    }

    // --- transactions ---

    @Test
    void transactionsListNewestFirstWithPagination() throws Exception {
        String token = registerWithWallet("trader@example.com");

        for (String amount : new String[]{"100", "200"}) {
            mvc.perform(post("/api/wallet/convert").header(HttpHeaders.AUTHORIZATION, token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(convertJson("USD", "EUR", amount)))
                    .andExpect(status().isOk());
        }

        mvc.perform(get("/api/wallet/transactions").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.items[0].type").value("CONVERSION"))
                .andExpect(jsonPath("$.items[0].fromAmount").value(200))
                .andExpect(jsonPath("$.items[1].fromAmount").value(100))
                .andExpect(jsonPath("$.items[2].type").value("DEPOSIT"))
                .andExpect(jsonPath("$.items[2].toAmount").value(10000))
                .andExpect(jsonPath("$.items[2].fromCurrency").doesNotExist());

        mvc.perform(get("/api/wallet/transactions").header(HttpHeaders.AUTHORIZATION, token)
                        .param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.totalPages").value(2));

        mvc.perform(get("/api/wallet/transactions").header(HttpHeaders.AUTHORIZATION, token)
                        .param("size", "0"))
                .andExpect(status().isBadRequest());
    }
}
