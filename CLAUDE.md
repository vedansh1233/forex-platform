# CLAUDE.md

This file guides Claude Code (claude.ai/code) when working in this repository.

## Project overview

`forex-platform` is the backend for a forex trading platform, at an early stage. It is a Spring Boot 3.5 REST API (Java 21, Maven) backed by PostgreSQL. Users register (and are signed in immediately with a JWT) and can create one USD wallet. The wallet is seeded with a 10,000 demo/paper balance. No trading, exchange-rate, or order features exist yet. The WebSocket starter is on the classpath but unused. A separate browser frontend is planned, and the API is set up for it (CORS, consistent JSON errors, an OpenAPI spec).

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

The schema is managed by `spring.jpa.hibernate.ddl-auto=update`. There are no migrations, and `update` never alters existing columns.

- Swagger UI: `http://localhost:8080/swagger-ui.html` (use the "Authorize" button to paste a JWT)
- OpenAPI spec: `http://localhost:8080/v3/api-docs` (can generate a typed frontend client)
- Health check: `GET /actuator/health`

### Toolchain gotcha

The machine's default JDK may be newer than 21 (JDK 26 is installed). The pom therefore pins Lombok `1.18.48` and lists it explicitly in `maven-compiler-plugin` `annotationProcessorPaths`. Newer JDKs no longer run annotation processors implicitly, so without this entry Lombok silently does nothing and compilation fails with "cannot find symbol getX/setX". Keep that entry when editing the pom.

## API contract (for the frontend)

Auth is stateless: send `Authorization: Bearer <token>` on every protected call. Routes under `/api/auth/**` are public.

| Method | Path | Auth | Success | Body / notes |
|---|---|---|---|---|
| POST | `/api/auth/register` | no | 201 `AuthResponse` | `{fullName, email, password}`; password 8–72 chars |
| POST | `/api/auth/login` | no | 200 `AuthResponse` | `{email, password}` |
| GET | `/api/user/me` | yes | 200 `UserResponse` | Use this to restore a session from a stored token |
| POST | `/api/wallet/create` | yes | 201 `WalletResponse` | Returns 409 if the wallet already exists |
| GET | `/api/wallet/my` | yes | 200 `WalletResponse` | Returns 404 until a wallet is created |

- `AuthResponse`: `{token, tokenType: "Bearer", expiresIn (seconds), user: UserResponse}`
- `UserResponse`: `{id, fullName, email, role, createdAt}`
- `WalletResponse`: `{id, fullName, balance, currency, createdAt}`

Every error (including 401/403 from Spring Security) uses this shape: `ErrorResponse {timestamp, status, error, message, path, fieldErrors?}`. `fieldErrors` (`field -> message`) appears only on 400 validation failures. Status codes: 400 validation or malformed body, 401 missing, invalid, or expired token or bad credentials, 404 not found, 409 conflict, 500 unexpected. Emails are trimmed and lower-cased, so login is case-insensitive.

## Architecture

Base package: `com.forex.forex_platform` (underscore, because the hyphenated package name is invalid). The code uses a standard layered layout:

- `controller/`: `AuthController`, `UserController`, `WalletController`. Controllers are thin; they get the current user's email from `Authentication#getName()`.
- `service/`: interfaces with implementations in `service/impl/`. `AuthService` is a concrete class. Email normalization lives in `UserService.normalizeEmail`.
- `repository/`: Spring Data JPA.
- `entity/`: `User` (table `users`; `role` defaults to `USER`) and `Wallet` (table `wallets`; `@OneToOne` unique to `User`; `BigDecimal` balance with precision 19 and scale 4). Defaults are set in `@PrePersist`.
- `dto/`: Java records. Requests carry Bean Validation annotations and trim input in their compact constructors. Responses have static `from(entity)` factories. Entities are never returned directly.
- `exception/`: `ApiException` subclasses (`ResourceNotFoundException`, `ConflictException`, `InvalidCredentialsException`) map to HTTP statuses in `GlobalExceptionHandler`.
- `security/`:
  - `SecurityConfig`: stateless, CSRF off, CORS from `app.cors.allowed-origins`, public routes listed explicitly
  - `JwtFilter`: puts the user's email String in as the principal; invalid tokens are ignored, which leads to a 401
  - `JwtService`: jjwt 0.11.5, HS256, subject = email
  - `RestSecurityErrorHandler`: writes JSON 401/403 responses
- `config/OpenApiConfig`: Swagger metadata and the bearer security scheme.

## Conventions

- Use `BigDecimal` for money, never `double`.
- Throw an `ApiException` subclass for expected business errors. Don't throw bare `RuntimeException` (it becomes a 500).
- New endpoints should accept and return DTO records, use `@Valid` on request bodies, and get an integration test in `ApiIntegrationTests` (MockMvc + H2, configured in `src/test/resources/application.properties`).
- New public routes must be added to the `permitAll()` list in `SecurityConfig`.
- Never commit secrets. Use environment variables or the gitignored `application-local.properties`.
