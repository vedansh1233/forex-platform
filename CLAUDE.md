# CLAUDE.md

This file guides Claude Code (claude.ai/code) when working in this repository.

## Project overview

`forex-platform` is the backend for a demo (paper-trading) forex platform. It is a Spring Boot 3.5 REST API (Java 21, Maven) backed by PostgreSQL. Users register (and are signed in immediately with a JWT) and open one wallet seeded with 10,000 USD. They can view exchange rates and history for 8 major currencies, convert between currencies at the latest rate, and see a transaction ledger. The WebSocket starter is on the classpath but unused. The React frontend lives in a separate repo, `forex-platform-web` (sibling folder `../forex-platform-web`).

## Build and run

Use the Maven wrapper (`mvnw.cmd` on Windows, `./mvnw` in Git Bash):

```bash
./mvnw spring-boot:run          # run the app on port 8080
./mvnw test                     # run all tests (no database needed; uses in-memory H2)
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
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173,http://localhost:3000` | Comma-separated frontend origins |
| `PORT` / `SHOW_SQL` | `8080` / `false` | |
| `RATES_PROVIDER` | `frankfurter` | `frankfurter` = live ECB rates (free, no key); `fixed` = offline simulated rates |
| `RATES_FALLBACK_TO_SIMULATED` | `true` | If live rates fail and nothing is cached, serve simulated rates instead of 503 |

The schema is managed by `spring.jpa.hibernate.ddl-auto=update`. There are no migrations, and `update` never alters existing columns.

- Swagger UI: `http://localhost:8080/swagger-ui.html` (use the "Authorize" button to paste a JWT)
- OpenAPI spec: `http://localhost:8080/v3/api-docs` (can generate a typed frontend client)
- Health check: `GET /actuator/health`

### Toolchain gotcha

The machine's default JDK may be newer than 21 (JDK 26 is installed). The pom therefore pins Lombok `1.18.48` and lists it explicitly in `maven-compiler-plugin` `annotationProcessorPaths`. Newer JDKs no longer run annotation processors implicitly, so without this entry Lombok silently does nothing and compilation fails with "cannot find symbol getX/setX". Keep that entry when editing the pom.

## API contract (for the frontend)

Auth is stateless: send `Authorization: Bearer <token>` on every protected call. Routes under `/api/auth/**` and `/api/rates/**` are public.

| Method | Path | Auth | Success | Body / notes |
|---|---|---|---|---|
| POST | `/api/auth/register` | no | 201 `AuthResponse` | `{fullName, email, password}`; password 8–72 chars |
| POST | `/api/auth/login` | no | 200 `AuthResponse` | `{email, password}` |
| GET | `/api/user/me` | yes | 200 `UserResponse` | Use this to restore a session from a stored token |
| POST | `/api/wallet/create` | yes | 201 `WalletResponse` | Returns 409 if the wallet already exists |
| GET | `/api/wallet/my` | yes | 200 `WalletResponse` | Returns 404 until a wallet is created |
| POST | `/api/wallet/convert` | yes | 200 `ConversionResponse` | `{from, to, amount}`; amount ≥ 0.01 with ≤ 2 decimals; 400 if insufficient funds |
| GET | `/api/wallet/transactions?page=0&size=20` | yes | 200 `PageResponse<TransactionResponse>` | Newest first; size 1–100 |
| GET | `/api/rates/currencies` | no | 200 `string[]` | `USD, EUR, GBP, JPY, CHF, CAD, AUD, NZD` |
| GET | `/api/rates?base=USD` | no | 200 `RatesResponse` | Latest rates for 1 base against the 7 others |
| GET | `/api/rates/history?base=EUR&quote=USD&days=30` | no | 200 `RateHistoryResponse` | days 7–365; business days only |

- `AuthResponse`: `{token, tokenType: "Bearer", expiresIn (seconds), user: UserResponse}`
- `UserResponse`: `{id, fullName, email, role, createdAt}`
- `WalletResponse`: `{id, fullName, balance (USD cash), currency ("USD"), createdAt, holdings: [{currency, amount, valueInBase}], totalValue, valuationDate}`. `holdings` lists the base currency first and then every non-zero currency. `totalValue`/`valueInBase` are null if rates are unavailable.
- `ConversionResponse`: `{transaction: TransactionResponse, wallet: WalletResponse}`
- `TransactionResponse`: `{id, type: DEPOSIT|CONVERSION, fromCurrency, fromAmount, toCurrency, toAmount, rate, createdAt}`. The `from*` fields and `rate` are null for DEPOSIT.
- `PageResponse<T>`: `{items, page, size, totalItems, totalPages}`
- `RatesResponse`: `{base, date, source: "ECB"|"SIMULATED", rates: {CCY: rate}}`. A rate is units of CCY per 1 base.
- `RateHistoryResponse`: `{base, quote, source, points: [{date, rate}]}`, oldest first

Every error (including 401/403 from Spring Security) uses this shape: `ErrorResponse {timestamp, status, error, message, path, fieldErrors?}`. `fieldErrors` (`field -> message`) appears only on 400 validation failures. Status codes: 400 validation, malformed body, or bad/missing query parameter; 401 missing, invalid, or expired token or bad credentials; 404 not found; 409 conflict; 503 rates unavailable (only when the simulated fallback is disabled); 500 unexpected. Emails are trimmed and lower-cased, so login is case-insensitive.

### Exchange rates and money rules

- **Source.** `FrankfurterRatesProvider` calls `api.frankfurter.dev/v1`, which serves ECB reference rates published once per business day. It uses **only the time-series endpoint**; "latest" is the last day of a 10-day window, because the `/latest` endpoint proved unreliable. Timeouts are 3 s to connect and 4 s to read.
- **Fallback.** `RatesService` caches latest rates for 15 min and history for 1 h. When the provider fails, it serves the stale cached value. If nothing is cached, it serves `FixedRatesProvider` data labelled `source: "SIMULATED"`, cached for 5 min so the live source is retried. With the fallback disabled, it returns 503 instead.
- **Simulated rates** (`FixedRatesProvider`) are deterministic. Every pair is derived from per-currency USD levels, so cross rates stay consistent. Tests always use this provider (`app.rates.provider=fixed` in the test properties), so tests never touch the network.
- **Wallet model.** USD cash stays in `Wallet.balance`. Each other currency is a `Holding` row (unique per wallet + currency). Every deposit and conversion appends a `WalletTransaction` row; that ledger is never updated.
- **Conversion.** The rate is fetched *before* `findByUserForUpdate` takes a pessimistic row lock on the wallet, so concurrent conversions can't overspend. The received amount is `amount × rate` **rounded down** to 2 decimals.

## Architecture

Base package: `com.forex.forex_platform` (underscore, because the hyphenated package name is invalid). The code uses a standard layered layout:

- `controller/`: `AuthController`, `UserController`, `WalletController`, `RatesController`. Controllers are thin; they get the current user's email from `Authentication#getName()`.
- `service/`: interfaces with implementations in `service/impl/`. `AuthService` and `RatesService` are concrete classes. Email normalization lives in `UserService.normalizeEmail`; currency validation lives in `RatesService.requireSupported`.
- `client/`: the `RatesProvider` interface with `FrankfurterRatesProvider` (default bean) and `FixedRatesProvider` (a bean only when `app.rates.provider=fixed`, via `config/RatesConfig`; otherwise it is created inside `RatesService` as the fallback).
- `repository/`: Spring Data JPA.
- `entity/`: `User` (table `users`; `role` defaults to `USER`), `Wallet` (table `wallets`; `@OneToOne` unique to `User`; `BigDecimal` balance with precision 19 and scale 4), `Holding` (`holdings`), and `WalletTransaction` (`wallet_transactions`, with a `TransactionType` enum). Defaults are set in `@PrePersist`.
- `dto/`: Java records. Requests carry Bean Validation annotations and trim input in their compact constructors. Responses have static `from(entity)` factories. Entities are never returned directly.
- `exception/`: `ApiException` subclasses (`BadRequestException`, `ResourceNotFoundException`, `ConflictException`, `InvalidCredentialsException`, `ServiceUnavailableException`) map to HTTP statuses in `GlobalExceptionHandler`.
- `security/`:
  - `SecurityConfig`: stateless, CSRF off, CORS from `app.cors.allowed-origins`, public routes listed explicitly
  - `JwtFilter`: puts the user's email String in as the principal; invalid tokens are ignored, which leads to a 401
  - `JwtService`: jjwt 0.11.5, HS256, subject = email
  - `RestSecurityErrorHandler`: writes JSON 401/403 responses
- `config/OpenApiConfig`: Swagger metadata and the bearer security scheme.

## Conventions

- Use `BigDecimal` for money, never `double`.
- Throw an `ApiException` subclass for expected business errors. Don't throw bare `RuntimeException` (it becomes a 500).
- New endpoints should accept and return DTO records, use `@Valid` on request bodies, and get an integration test: auth, user, and infra in `ApiIntegrationTests`; rates, conversion, and history in `TradingIntegrationTests` (MockMvc + H2, configured in `src/test/resources/application.properties`). Cache and fallback logic is unit-tested in `service/RatesServiceTest` with a fake provider and clock.
- Test classes that touch the database delete rows in foreign-key order: transactions → holdings → wallets → users.
- Don't return Spring's `Page` directly; wrap it in `PageResponse`.
- New public routes must be added to the `permitAll()` list in `SecurityConfig`.
- Never commit secrets. Use environment variables or the gitignored `application-local.properties`.
