# CLAUDE.md

This file guides Claude Code (claude.ai/code) when working in this repository.

## Project overview

`forex-platform` is the backend for a demo (paper-trading) forex and crypto platform. It is a Spring Boot 3.5 REST API (Java 21, Maven) backed by PostgreSQL. Users register (and are signed in immediately with a JWT) and open one wallet seeded with 10,000 USD. They can:

- view exchange rates and history for 8 major currencies (ECB), and live prices and history for 5 cryptocurrencies (Coinbase), streamed over a WebSocket;
- trade any pair at the latest price, or place limit orders that fill automatically when a target rate is reached;
- set price alerts on any pair;
- see a transaction ledger.

The React frontend lives in a separate repo, `forex-platform-web` (sibling folder `../forex-platform-web`).

## Build and run

Use the Maven wrapper (`mvnw.cmd` on Windows, `./mvnw` in Git Bash):

```bash
./mvnw spring-boot:run          # run the app on port 8080
./mvnw test                     # run all tests (no database or network needed; uses in-memory H2)
./mvnw test -Dtest=ApiIntegrationTests#walletLifecycle   # run a single test
./mvnw clean package            # build the jar
```

Running the app needs a local PostgreSQL database named `forex`. Configure it with environment variables, or copy `application-local.properties.example` to `application-local.properties` (gitignored, loaded automatically from the working directory):

| Variable | Default | Notes |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/forex` | |
| `DB_USERNAME` | `postgres` | |
| `DB_PASSWORD` | (required) | |
| `JWT_SECRET` | (required) | At least 32 bytes, or startup fails (HS256) |
| `JWT_EXPIRATION_MS` | `36000000` (10 h) | |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173,http://localhost:3000` | Comma-separated frontend origins; also used for the WebSocket |
| `PORT` / `SHOW_SQL` | `8080` / `false` | |
| `RATES_PROVIDER` | `frankfurter` | `frankfurter` = live ECB rates (free, no key); `fixed` = offline simulated rates |
| `RATES_FALLBACK_TO_SIMULATED` | `true` | If a live source fails and nothing usable is cached, serve simulated prices instead of 503 (currencies and crypto) |
| `CRYPTO_PROVIDER` | `coinbase` | `coinbase` = live prices from Coinbase Exchange public market data (free, no key); `simulated` = offline |
| `CRYPTO_REFRESH_MS` | `5000` | How often crypto prices are refreshed and pushed over the WebSocket |
| `SCHEDULING_ENABLED` / `ORDERS_MATCH_MS` | `true` / `10000` | Background jobs (crypto refresh, limit-order matching, alerts) and the matching interval |

**Schema: Flyway.** Migrations live in `src/main/resources/db/migration` (`V1__baseline.sql`, `V2__multi_asset_precision.sql`, `V3__orders_and_alerts.sql`); Hibernate runs with `ddl-auto=validate`. `V1` uses `CREATE TABLE IF NOT EXISTS` and `baseline-version=0`, so databases created by the older `ddl-auto=update` setup migrate cleanly. Every schema change needs a new `V<n>__*.sql` file. Keep the SQL portable: it must run on PostgreSQL and on H2 in PostgreSQL mode (the tests run the same migrations). Use `ALTER COLUMN x SET DATA TYPE ...`, which both accept.

- Swagger UI: `http://localhost:8080/swagger-ui.html` (use the "Authorize" button to paste a JWT)
- OpenAPI spec: `http://localhost:8080/v3/api-docs` (can generate a typed frontend client)
- Health check: `GET /actuator/health`

**Quick local run without PostgreSQL:** `./mvnw spring-boot:run -Dspring-boot.run.useTestClasspath=true` with `--spring.datasource.url=jdbc:h2:mem:demo;MODE=PostgreSQL;DB_CLOSE_DELAY=-1 --spring.datasource.driver-class-name=org.h2.Driver --spring.datasource.username=sa --spring.datasource.password= --jwt.secret=<32+ bytes> --app.rates.provider=frankfurter --app.crypto.provider=coinbase --app.scheduling.enabled=true` as program arguments (the test properties otherwise switch to offline providers and turn jobs off). Data resets on restart.

### Toolchain gotcha

The machine's default JDK may be newer than 21 (JDK 26 is installed). The pom therefore pins Lombok `1.18.48` and lists it explicitly in `maven-compiler-plugin` `annotationProcessorPaths`. Newer JDKs no longer run annotation processors implicitly, so without this entry Lombok silently does nothing and compilation fails with "cannot find symbol getX/setX". Keep that entry when editing the pom.

## API contract (for the frontend)

Auth is stateless: send `Authorization: Bearer <token>` on every protected call. Routes under `/api/auth/**`, `/api/rates/**`, `/api/market/**` and `/ws/**` are public.

| Method | Path | Auth | Success | Body / notes |
|---|---|---|---|---|
| POST | `/api/auth/register` | no | 201 `AuthResponse` | `{fullName, email, password}`; password 8–72 chars |
| POST | `/api/auth/login` | no | 200 `AuthResponse` | `{email, password}` |
| GET | `/api/user/me` | yes | 200 `UserResponse` | Use this to restore a session from a stored token |
| POST | `/api/wallet/create` | yes | 201 `WalletResponse` | Returns 409 if the wallet already exists |
| GET | `/api/wallet/my` | yes | 200 `WalletResponse` | Returns 404 until a wallet is created |
| POST | `/api/wallet/convert` | yes | 200 `ConversionResponse` | `{from, to, amount}`; any two assets; amount > 0 with at most the asset's decimals (2 currencies, 8 crypto); 400 if insufficient funds |
| GET | `/api/wallet/transactions?page=0&size=20` | yes | 200 `PageResponse<TransactionResponse>` | Newest first; size 1–100 |
| GET | `/api/rates/currencies` | no | 200 `string[]` | `USD, EUR, GBP, JPY, CHF, CAD, AUD, NZD` |
| GET | `/api/rates?base=USD` | no | 200 `RatesResponse` | Latest rates for 1 base against the 7 others (currencies only) |
| GET | `/api/rates/history?base=EUR&quote=USD&days=30` | no | 200 `RateHistoryResponse` | days 7–365; business days only |
| GET | `/api/market/assets` | no | 200 `AssetResponse[]` | 8 currencies, then `BTC, ETH, SOL, XRP, ADA` |
| GET | `/api/market/quote?from=BTC&to=EUR` | no | 200 `QuoteResponse` | Current rate for any pair |
| GET | `/api/market/crypto` | no | 200 `CryptoPricesResponse` | Latest USD prices with 24h change |
| GET | `/api/market/crypto/history?symbol=BTC&days=30` | no | 200 `RateHistoryResponse` | Daily USD closes (every day, UTC); days 7–365 |
| WS | `/ws/prices` | no | text frames | Plain WebSocket. Sends a `CryptoPricesResponse` on connect and after every refresh (default 5 s). Origins limited to `app.cors.allowed-origins` |
| POST | `/api/orders` | yes | 201 `OrderResponse` | `{from, to, amount, targetRate}`; fills when 1 `from` buys ≥ `targetRate` `to`; max 20 open (409) |
| GET | `/api/orders` | yes | 200 `OrderResponse[]` | 50 most recent, newest first |
| POST | `/api/orders/{id}/cancel` | yes | 200 `OrderResponse` | 409 unless OPEN; 404 if not yours |
| POST | `/api/alerts` | yes | 201 `AlertResponse` | `{base, quote, direction: ABOVE\|BELOW, targetRate}`; max 20 active (409) |
| GET | `/api/alerts` | yes | 200 `AlertResponse[]` | 50 most recent, newest first |
| POST | `/api/alerts/{id}/cancel` | yes | 200 `AlertResponse` | 409 unless ACTIVE; 404 if not yours |
| POST | `/api/alerts/mark-seen` | yes | 204 | Marks every triggered alert as seen (clears the notification badge) |

- `AuthResponse`: `{token, tokenType: "Bearer", expiresIn (seconds), user: UserResponse}`
- `UserResponse`: `{id, fullName, email, role, createdAt}`
- `WalletResponse`: `{id, fullName, balance (USD cash), currency ("USD"), createdAt, holdings: [{currency, amount, valueInBase}], totalValue, valuationDate}`. `holdings` lists USD first, then every non-zero asset (currencies before crypto). Crypto is valued at live prices. `totalValue`/`valueInBase` are null if prices are unavailable. `valuationDate` is the ECB rates date when currencies other than USD are held.
- `ConversionResponse`: `{transaction: TransactionResponse, wallet: WalletResponse}`
- `TransactionResponse`: `{id, type: DEPOSIT|CONVERSION, fromCurrency, fromAmount, toCurrency, toAmount, rate, createdAt}`. The `from*` fields and `rate` are null for DEPOSIT. Limit-order fills are CONVERSIONs.
- `PageResponse<T>`: `{items, page, size, totalItems, totalPages}`
- `RatesResponse`: `{base, date, source: "ECB"|"SIMULATED", rates: {CCY: rate}}`. A rate is units of CCY per 1 base.
- `RateHistoryResponse`: `{base, quote, source, points: [{date, rate}]}`, oldest first. For crypto, `quote` is `USD` and `source` is `COINBASE` or `SIMULATED`.
- `AssetResponse`: `{code, name, type: FIAT|CRYPTO, decimals, minAmount}`
- `QuoteResponse`: `{from, to, rate}` (units of `to` per 1 `from`)
- `CryptoPricesResponse`: `{source: "COINBASE"|"SIMULATED", updatedAt, prices: [{symbol, name, priceUsd, change24h (%)}]}`
- `OrderResponse`: `{id, from, to, amount, targetRate, status: OPEN|FILLED|CANCELLED|FAILED, createdAt, closedAt, currentRate, fillRate, receivedAmount, transactionId, failureReason}`. `currentRate` is set only while OPEN.
- `AlertResponse`: `{id, base, quote, direction, targetRate, status: ACTIVE|TRIGGERED|CANCELLED, seen, createdAt, triggeredAt, triggeredRate, currentRate}`

Every error (including 401/403 from Spring Security) uses this shape: `ErrorResponse {timestamp, status, error, message, path, fieldErrors?}`. `fieldErrors` (`field -> message`) appears only on 400 validation failures. Status codes: 400 validation, malformed body, or bad/missing query parameter; 401 missing, invalid, or expired token or bad credentials; 404 not found; 409 conflict; 503 prices unavailable (only when the simulated fallback is disabled); 500 unexpected. Emails are trimmed and lower-cased, so login is case-insensitive. `BigDecimal`s are written as plain numbers (`spring.jackson.generator.write-bigdecimal-as-plain`), never `1E-8`.

### Prices and money rules

- **Assets.** `market/Assets` is the single registry: 8 currencies (`FIAT`, 2 decimals) and 5 cryptocurrencies (`CRYPTO`, 8 decimals). `Assets.require` validates codes; `Assets.requireValidAmount` enforces per-asset decimals. The fiat list must equal `RatesService.SUPPORTED_CURRENCIES` (checked at startup).
- **Currency rates.** `FrankfurterRatesProvider` calls `api.frankfurter.dev/v1`, which serves ECB reference rates published once per business day. It uses **only the time-series endpoint**; "latest" is the last day of a 10-day window, because the `/latest` endpoint proved unreliable. Timeouts are 3 s to connect and 4 s to read.
- **Currency fallback.** `RatesService` caches latest rates for 15 min and history for 1 h. When the provider fails, it serves the stale cached value. If nothing is cached, it serves `FixedRatesProvider` data labelled `source: "SIMULATED"`, cached for 5 min so the live source is retried. With the fallback disabled, it returns 503 instead.
- **Crypto prices.** `CoinbaseCryptoProvider` calls `api.exchange.coinbase.com` `/products/{SYM}-USD/stats` (current price and 24h open; one request per coin, in parallel on virtual threads) and `/candles` (daily closes, at most 300 per request). It sends a `User-Agent`, which Coinbase requires. `CryptoPriceService` keeps a snapshot refreshed by the scheduler (and on read if older than 30 s). If the feed fails, it keeps serving live prices for up to 2 min, then falls back to `SimulatedCryptoProvider` labelled `SIMULATED`. Each refresh publishes a `CryptoPricesUpdatedEvent`, which `PriceStreamHandler` broadcasts to WebSocket clients.
- **Any pair.** `PricingService.rate(from, to)` uses ECB rates directly for currency pairs; anything involving crypto goes through USD (`usdPrice(from) / usdPrice(to)`, 18 decimal places).
- **Simulated data** is deterministic. Currency cross rates stay consistent; crypto prices add small intraday waves in 5-second steps so the ticker moves. Tests always use the offline providers (`app.rates.provider=fixed`, `app.crypto.provider=simulated`), so they never touch the network.
- **Wallet model.** USD cash stays in `Wallet.balance` (scale 4). Each other asset is a `Holding` row (unique per wallet + currency, `numeric(28,8)`). Every deposit and conversion appends a `WalletTransaction` row; that ledger is never updated.
- **Conversion.** All balance changes go through `service/impl/WalletLedger`, used by market conversions and limit-order fills alike. The rate is fetched *before* the wallet's pessimistic row lock (`findByUserForUpdate` / `findByIdForUpdate`), so a slow price feed never holds the lock. Funds are checked under the lock. The received amount is `amount × rate` **rounded down** to the target asset's decimals.
- **Limit orders.** `OrderServiceImpl.matchOpenOrders` (every 10 s) gets one rate per pair and calls `OrderFiller.tryFill`. That method runs in its own transaction: it locks the wallet *first* and then re-reads the order, so a cancel (which takes the same lock) can't race a fill. Funds are checked at placement and again at fill, but not reserved; an order whose funds were spent meanwhile ends FAILED with a reason.
- **Alerts.** `AlertServiceImpl.checkActiveAlerts` triggers via a conditional `UPDATE ... WHERE status = ACTIVE`, so a trigger and a cancel can't both win. Each alert fires once.

## Architecture

Base package: `com.forex.forex_platform` (underscore, because the hyphenated package name is invalid). The code uses a standard layered layout:

- `controller/`: `AuthController`, `UserController`, `WalletController`, `RatesController`, `MarketController` (assets, quote, crypto), `OrderController`, `AlertController`. Controllers are thin; they get the current user's email from `Authentication#getName()`.
- `service/`: interfaces with implementations in `service/impl/`. `AuthService`, `RatesService`, `CryptoPriceService` and `PricingService` are concrete classes. `service/impl/WalletLedger` (balance changes) and `OrderFiller` (one order per transaction) are package helpers. Email normalization lives in `UserService.normalizeEmail`.
- `market/`: `Asset`, `AssetType`, and the `Assets` registry.
- `client/`:
  - `RatesProvider`, with `FrankfurterRatesProvider` (default) and `FixedRatesProvider`.
  - `CryptoPriceProvider`, with `CoinbaseCryptoProvider` (default) and `SimulatedCryptoProvider`.
  - The offline providers are beans only when selected (via `config/RatesConfig`); otherwise the services create them internally as fallbacks.
- `scheduling/MarketJobs`: `@Scheduled` crypto refresh and order/alert matching. It is enabled by `config/SchedulingConfig` (`app.scheduling.enabled`), which tests turn off; they call the services directly.
- `websocket/PriceStreamHandler` + `config/WebSocketConfig`: plain WebSocket at `/ws/prices`. Sessions are wrapped in `ConcurrentWebSocketSessionDecorator` for thread-safe sends.
- `event/`: `CryptoPricesUpdatedEvent`.
- `repository/`: Spring Data JPA.
- `entity/`:
  - `User` (table `users`; `role` defaults to `USER`) and `Wallet` (`wallets`; `@OneToOne` unique to `User`).
  - `Holding` (`holdings`), and `WalletTransaction` (`wallet_transactions`, with a `TransactionType` enum).
  - `LimitOrder` (`limit_orders`, with `OrderStatus`) and `PriceAlert` (`price_alerts`, with `AlertDirection` and `AlertStatus`).
  - Defaults are set in `@PrePersist`.
- `dto/`: Java records. Requests carry Bean Validation annotations and trim input in their compact constructors. Responses have static `from(entity)` factories. Entities are never returned directly.
- `exception/`: `ApiException` subclasses (`BadRequestException`, `ResourceNotFoundException`, `ConflictException`, `InvalidCredentialsException`, `ServiceUnavailableException`) map to HTTP statuses in `GlobalExceptionHandler`.
- `security/`:
  - `SecurityConfig`: stateless, CSRF off, CORS from `app.cors.allowed-origins`, public routes listed explicitly
  - `JwtFilter`: puts the user's email String in as the principal; invalid tokens are ignored, which leads to a 401
  - `JwtService`: jjwt 0.11.5, HS256, subject = email
  - `RestSecurityErrorHandler`: writes JSON 401/403 responses
- `config/OpenApiConfig`: Swagger metadata and the bearer security scheme.

## Conventions

- Use `BigDecimal` for money, never `double`. Validate amounts against the asset's decimals with `Assets.requireValidAmount`.
- Change balances only through `WalletLedger`, while holding the wallet's row lock. Never call the network while holding it.
- Throw an `ApiException` subclass for expected business errors. Don't throw bare `RuntimeException` (it becomes a 500).
- Schema changes go in a new Flyway migration (portable SQL), never `ddl-auto`.
- New endpoints should accept and return DTO records, use `@Valid` on request bodies, and get an integration test (MockMvc + H2, configured in `src/test/resources/application.properties`):
  - `ApiIntegrationTests`: auth, user and infra
  - `TradingIntegrationTests`: currency rates, conversion and history
  - `CryptoOrdersAlertsIntegrationTests`: market endpoints, crypto trading, orders and alerts
  - `PriceStreamWebSocketTests`: the WebSocket, on a real port
  - Cache and fallback logic is unit-tested in `service/RatesServiceTest` and `service/CryptoPriceServiceTest`, with fake providers and a clock.
- Test classes that touch the database delete rows in foreign-key order: alerts → orders → transactions → holdings → wallets → users.
- Don't return Spring's `Page` directly; wrap it in `PageResponse`.
- New public routes must be added to the `permitAll()` list in `SecurityConfig`.
- Never commit secrets. Use environment variables or the gitignored `application-local.properties`.
