package com.forex.forex_platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forex.forex_platform.repository.HoldingRepository;
import com.forex.forex_platform.repository.LimitOrderRepository;
import com.forex.forex_platform.repository.PriceAlertRepository;
import com.forex.forex_platform.repository.UserRepository;
import com.forex.forex_platform.repository.WalletRepository;
import com.forex.forex_platform.repository.WalletTransactionRepository;
import com.forex.forex_platform.service.AlertService;
import com.forex.forex_platform.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Crypto market data and trading, limit orders and price alerts. Uses the offline "fixed" rates and
 * "simulated" crypto providers; background jobs are off, so tests run the matcher directly.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CryptoOrdersAlertsIntegrationTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrderService orderService;

    @Autowired
    private AlertService alertService;

    @Autowired
    private PriceAlertRepository alertRepository;

    @Autowired
    private LimitOrderRepository orderRepository;

    @Autowired
    private WalletTransactionRepository transactionRepository;

    @Autowired
    private HoldingRepository holdingRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void cleanDatabase() {
        alertRepository.deleteAll();
        orderRepository.deleteAll();
        transactionRepository.deleteAll();
        holdingRepository.deleteAll();
        walletRepository.deleteAll();
        userRepository.deleteAll();
    }

    // --- helpers ---

    private String register(String email, boolean withWallet) throws Exception {
        String body = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Crypto Trader","email":"%s","password":"password123"}""".formatted(email)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = "Bearer " + json(body).get("token").asText();
        if (withWallet) {
            mvc.perform(post("/api/wallet/create").header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(status().isCreated());
        }
        return token;
    }

    private JsonNode json(String content) throws Exception {
        return objectMapper.readTree(content);
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json(result.andReturn().getResponse().getContentAsString());
    }

    private BigDecimal quote(String from, String to) throws Exception {
        return body(mvc.perform(get("/api/market/quote").param("from", from).param("to", to))
                .andExpect(status().isOk()))
                .get("rate").decimalValue();
    }

    private ResultActions postJson(String url, String token, String content) throws Exception {
        return mvc.perform(post(url).header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(content));
    }

    private String orderJson(String from, String to, String amount, BigDecimal target) {
        return """
                {"from":"%s","to":"%s","amount":%s,"targetRate":%s}""".formatted(from, to, amount, target.toPlainString());
    }

    private String alertJson(String base, String quote, String direction, BigDecimal target) {
        return """
                {"base":"%s","quote":"%s","direction":"%s","targetRate":%s}""".formatted(base, quote, direction, target.toPlainString());
    }

    private static BigDecimal scaled(BigDecimal rate, String factor) {
        return rate.multiply(new BigDecimal(factor)).round(new MathContext(10));
    }

    // --- market data ---

    @Test
    void assetsListCurrenciesThenCrypto() throws Exception {
        mvc.perform(get("/api/market/assets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(13))
                .andExpect(jsonPath("$[0].code").value("USD"))
                .andExpect(jsonPath("$[0].type").value("FIAT"))
                .andExpect(jsonPath("$[0].decimals").value(2))
                .andExpect(jsonPath("$[8].code").value("BTC"))
                .andExpect(jsonPath("$[8].type").value("CRYPTO"))
                .andExpect(jsonPath("$[8].decimals").value(8))
                .andExpect(jsonPath("$[8].minAmount").value(0.00000001));
    }

    @Test
    void cryptoPricesArePublic() throws Exception {
        JsonNode prices = body(mvc.perform(get("/api/market/crypto"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("SIMULATED"))
                .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.prices.length()").value(5))
                .andExpect(jsonPath("$.prices[0].symbol").value("BTC"))
                .andExpect(jsonPath("$.prices[0].name").value("Bitcoin")));
        for (JsonNode price : prices.get("prices")) {
            assertThat(price.get("priceUsd").decimalValue()).isPositive();
            assertThat(price.get("change24h").isNumber()).isTrue();
        }
    }

    @Test
    void cryptoHistoryReturnsDailyPointsAndValidates() throws Exception {
        JsonNode history = body(mvc.perform(get("/api/market/crypto/history").param("symbol", "eth").param("days", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.base").value("ETH"))
                .andExpect(jsonPath("$.quote").value("USD")));
        JsonNode points = history.get("points");
        assertThat(points.size()).isEqualTo(31); // crypto trades every day, including weekends
        for (int i = 1; i < points.size(); i++) {
            assertThat(points.get(i).get("date").asText()).isGreaterThan(points.get(i - 1).get("date").asText());
        }

        mvc.perform(get("/api/market/crypto/history").param("symbol", "EUR"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("EUR is not a crypto asset"));
        mvc.perform(get("/api/market/crypto/history").param("symbol", "BTC").param("days", "3"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void quoteWorksForAnyPairAndCrossRatesAreConsistent() throws Exception {
        BigDecimal btcUsd = quote("BTC", "USD");
        BigDecimal usdEur = quote("USD", "EUR");
        BigDecimal btcEur = quote("btc", "eur");
        // BTC -> EUR goes through USD
        assertThat(btcEur.doubleValue()).isCloseTo(btcUsd.doubleValue() * usdEur.doubleValue(), within(0.01));
        // and the inverse is consistent to many digits
        assertThat(quote("EUR", "BTC").doubleValue() * btcEur.doubleValue()).isCloseTo(1.0, within(1e-9));

        mvc.perform(get("/api/market/quote").param("from", "BTC").param("to", "DOGE"))
                .andExpect(status().isBadRequest());
    }

    // --- crypto trading ---

    @Test
    void buyAndSellCryptoWithEightDecimals() throws Exception {
        String token = register("crypto@example.com", true);
        BigDecimal rate = quote("USD", "BTC");
        BigDecimal expectedBtc = new BigDecimal("1000").multiply(rate).setScale(8, RoundingMode.DOWN);

        JsonNode buy = body(postJson("/api/wallet/convert", token, """
                {"from":"USD","to":"BTC","amount":1000}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transaction.toCurrency").value("BTC"))
                .andExpect(jsonPath("$.wallet.balance").value(9000))
                .andExpect(jsonPath("$.wallet.holdings[1].currency").value("BTC")));
        BigDecimal btc = buy.at("/transaction/toAmount").decimalValue();
        assertThat(btc).isEqualByComparingTo(expectedBtc);
        assertThat(btc.stripTrailingZeros().scale()).isLessThanOrEqualTo(8);
        assertThat(buy.at("/wallet/holdings/1/amount").decimalValue()).isEqualByComparingTo(expectedBtc);
        // Valued at the same price, the wallet is still worth (almost exactly) $10,000
        assertThat(buy.at("/wallet/totalValue").decimalValue()).isBetween(new BigDecimal("9999.98"), new BigDecimal("10000.00"));

        // Sell part of it for euros, using all 8 decimal places
        BigDecimal half = btc.divide(BigDecimal.TWO, 8, RoundingMode.DOWN);
        postJson("/api/wallet/convert", token, """
                {"from":"BTC","to":"EUR","amount":%s}""".formatted(half.toPlainString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transaction.fromCurrency").value("BTC"))
                .andExpect(jsonPath("$.wallet.holdings.length()").value(3))
                // Currencies are listed before crypto
                .andExpect(jsonPath("$.wallet.holdings[1].currency").value("EUR"))
                .andExpect(jsonPath("$.wallet.holdings[2].currency").value("BTC"));

        // 9 decimals is too many for any asset
        postJson("/api/wallet/convert", token, """
                {"from":"BTC","to":"EUR","amount":0.000000001}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.amount").exists());
        // and currencies stay at 2
        postJson("/api/wallet/convert", token, """
                {"from":"USD","to":"BTC","amount":10.001}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("USD amounts can have at most 2 decimal places"));
        // Selling more BTC than held
        postJson("/api/wallet/convert", token, """
                {"from":"BTC","to":"USD","amount":5}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("Insufficient BTC balance")));
    }

    // --- limit orders ---

    @Test
    void limitOrderFillsOnceTheTargetIsReached() throws Exception {
        String token = register("orders@example.com", true);
        BigDecimal current = quote("USD", "EUR");
        // Target below the current rate: already met, so the next matcher run fills it
        BigDecimal target = scaled(current, "0.5");

        JsonNode placed = body(postJson("/api/orders", token, orderJson("USD", "EUR", "100", target))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.from").value("USD"))
                .andExpect(jsonPath("$.to").value("EUR"))
                .andExpect(jsonPath("$.currentRate").isNumber()));
        long id = placed.get("id").asLong();

        assertThat(orderService.matchOpenOrders()).isEqualTo(1);
        // A second run has nothing left to do
        assertThat(orderService.matchOpenOrders()).isZero();

        JsonNode order = body(mvc.perform(get("/api/orders").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))).get(0);
        assertThat(order.get("id").asLong()).isEqualTo(id);
        assertThat(order.get("status").asText()).isEqualTo("FILLED");
        assertThat(order.get("fillRate").decimalValue()).isGreaterThanOrEqualTo(target);
        BigDecimal received = order.get("receivedAmount").decimalValue();
        assertThat(received).isEqualByComparingTo(new BigDecimal("100").multiply(current).setScale(2, RoundingMode.DOWN));
        assertThat(order.get("transactionId").isNumber()).isTrue();
        assertThat(order.get("closedAt").asText()).isNotBlank();

        // The fill went through the wallet and the ledger like a normal conversion
        mvc.perform(get("/api/wallet/my").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.balance").value(9900))
                .andExpect(jsonPath("$.holdings[1].currency").value("EUR"));
        mvc.perform(get("/api/wallet/transactions").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.items[0].type").value("CONVERSION"))
                .andExpect(jsonPath("$.items[0].id").value(order.get("transactionId").asLong()));
    }

    @Test
    void limitOrderWaitsBelowTargetAndCanBeCancelled() throws Exception {
        String token = register("orders@example.com", true);
        BigDecimal target = scaled(quote("USD", "BTC"), "2");

        long id = body(postJson("/api/orders", token, orderJson("USD", "BTC", "250", target))
                .andExpect(status().isCreated())).get("id").asLong();

        assertThat(orderService.matchOpenOrders()).isZero();
        mvc.perform(get("/api/orders").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$[0].status").value("OPEN"))
                .andExpect(jsonPath("$[0].currentRate").isNumber());

        mvc.perform(post("/api/orders/{id}/cancel", id).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.closedAt").isNotEmpty());
        mvc.perform(post("/api/orders/{id}/cancel", id).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isConflict());

        // Cancelled orders never fill, and no money moved
        assertThat(orderService.matchOpenOrders()).isZero();
        mvc.perform(get("/api/wallet/my").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.balance").value(10000));
    }

    @Test
    void limitOrderFailsIfFundsWereSpentBeforeItFilled() throws Exception {
        String token = register("orders@example.com", true);
        BigDecimal target = scaled(quote("USD", "GBP"), "0.5");

        postJson("/api/orders", token, orderJson("USD", "GBP", "6000", target)).andExpect(status().isCreated());
        // Funds aren't reserved: spending them first leaves the order unable to execute
        postJson("/api/wallet/convert", token, """
                {"from":"USD","to":"EUR","amount":5000}""").andExpect(status().isOk());

        assertThat(orderService.matchOpenOrders()).isZero();
        mvc.perform(get("/api/orders").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$[0].status").value("FAILED"))
                .andExpect(jsonPath("$[0].failureReason").value("Insufficient USD balance: 5000.00 available"));
        mvc.perform(get("/api/wallet/my").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.balance").value(5000));
    }

    @Test
    void limitOrderValidationAndOwnership() throws Exception {
        String token = register("orders@example.com", true);
        BigDecimal target = quote("USD", "EUR");

        postJson("/api/orders", token, orderJson("USD", "EUR", "20000", target))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Insufficient USD balance: 10000.00 available"));
        postJson("/api/orders", token, orderJson("EUR", "EUR", "10", target))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Choose two different assets"));
        postJson("/api/orders", token, """
                {"from":"USD","to":"EUR","amount":10,"targetRate":0}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.targetRate").exists());
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson("USD", "EUR", "10", target)))
                .andExpect(status().isUnauthorized());

        // Another user can't see or cancel someone else's order
        long id = body(postJson("/api/orders", token, orderJson("USD", "EUR", "10", scaled(target, "2")))
                .andExpect(status().isCreated())).get("id").asLong();
        String other = register("other@example.com", true);
        mvc.perform(post("/api/orders/{id}/cancel", id).header(HttpHeaders.AUTHORIZATION, other))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/orders").header(HttpHeaders.AUTHORIZATION, other))
                .andExpect(jsonPath("$.length()").value(0));

        // Orders need a wallet
        String noWallet = register("nowallet@example.com", false);
        postJson("/api/orders", noWallet, orderJson("USD", "EUR", "10", target))
                .andExpect(status().isNotFound());
    }

    // --- alerts ---

    @Test
    void alertsTriggerOnceAndCanBeMarkedSeen() throws Exception {
        String token = register("alerts@example.com", false);
        BigDecimal current = quote("BTC", "USD");

        // ABOVE half the current price: met now. BELOW half the price: not met.
        long above = body(postJson("/api/alerts", token, alertJson("BTC", "USD", "ABOVE", scaled(current, "0.5")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.currentRate").isNumber())).get("id").asLong();
        long below = body(postJson("/api/alerts", token, alertJson("btc", "usd", "BELOW", scaled(current, "0.5")))
                .andExpect(status().isCreated())).get("id").asLong();

        assertThat(alertService.checkActiveAlerts()).isEqualTo(1);
        assertThat(alertService.checkActiveAlerts()).isZero();

        JsonNode alerts = body(mvc.perform(get("/api/alerts").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2)));
        JsonNode triggered = alerts.get(0).get("id").asLong() == above ? alerts.get(0) : alerts.get(1);
        assertThat(triggered.get("status").asText()).isEqualTo("TRIGGERED");
        assertThat(triggered.get("seen").asBoolean()).isFalse();
        assertThat(triggered.get("triggeredRate").decimalValue()).isPositive();
        assertThat(triggered.get("triggeredAt").asText()).isNotBlank();

        mvc.perform(post("/api/alerts/mark-seen").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/alerts").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$[?(@.status == 'TRIGGERED')].seen").value(true));

        // Only active alerts can be cancelled
        mvc.perform(post("/api/alerts/{id}/cancel", above).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/alerts/{id}/cancel", below).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void alertValidation() throws Exception {
        String token = register("alerts@example.com", false);

        postJson("/api/alerts", token, alertJson("EUR", "EUR", "ABOVE", BigDecimal.ONE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Choose two different assets"));
        postJson("/api/alerts", token, alertJson("EUR", "USD", "SIDEWAYS", BigDecimal.ONE))
                .andExpect(status().isBadRequest());
        postJson("/api/alerts", token, """
                {"base":"EUR","quote":"USD","targetRate":1.1}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.direction").exists());
        mvc.perform(get("/api/alerts"))
                .andExpect(status().isUnauthorized());

        long id = body(postJson("/api/alerts", token, alertJson("EUR", "USD", "ABOVE", BigDecimal.TEN))
                .andExpect(status().isCreated())).get("id").asLong();
        String other = register("other@example.com", false);
        mvc.perform(post("/api/alerts/{id}/cancel", id).header(HttpHeaders.AUTHORIZATION, other))
                .andExpect(status().isNotFound());
    }
}
