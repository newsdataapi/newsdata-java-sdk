<div align="center">

![Newsdata.io logo](https://raw.githubusercontent.com/newsdataapi/newsdata-java-sdk/main/newsdata-logo.png)

# Newsdata.io Java SDK

[![Maven Central](https://img.shields.io/maven-central/v/io.newsdata/newsdataapi.svg?label=maven&color=blue)](https://central.sonatype.com/artifact/io.newsdata/newsdataapi)
[![CI](https://img.shields.io/github/actions/workflow/status/newsdataapi/newsdata-java-sdk/ci.yml?branch=main&logo=github&label=CI)](https://github.com/newsdataapi/newsdata-java-sdk/actions/workflows/ci.yml)
[![Java](https://img.shields.io/badge/java-%3E%3D17-007396?logo=openjdk)](https://openjdk.org)
[![License](https://img.shields.io/badge/license-MIT-blue)](./LICENSE)

</div>

Official Java client (SDK) for the [Newsdata.io](https://newsdata.io) News
API. Wraps every endpoint (`latest`, `archive`, `sources`, `crypto`, `market`,
`count`, `crypto/count`, `market/count`) with client-side parameter
validation, automatic retries with exponential backoff, lazy `Stream`-based
pagination, and a typed exception hierarchy.

Built on Java 17's built-in `java.net.http.HttpClient` — the only runtime
dependency is Jackson for JSON. Thread-safe.

## Installation

### Maven
```xml
<dependency>
    <groupId>io.newsdata</groupId>
    <artifactId>newsdataapi</artifactId>
    <version>0.0.1</version>
</dependency>
```

### Gradle (Kotlin DSL)
```kotlin
implementation("io.newsdata:newsdataapi:0.0.1")
```

### Gradle (Groovy DSL)
```groovy
implementation 'io.newsdata:newsdataapi:0.0.1'
```

## Quickstart

```java
import io.newsdata.api.*;
import java.util.List;

NewsDataApiClient client = NewsDataApiClient.builder()
        .apiKey(System.getenv("NEWSDATA_API_KEY"))
        .build();

NewsdataResponse resp = client.latest(Params.of()
        .with("q", "bitcoin")
        .with("country", List.of("us", "gb"))
        .with("language", List.of("en")));

for (Article article : resp.articles(client.objectMapper())) {
    System.out.println(article.title() + " - " + article.link());
}
```

## Endpoints

| Method | Endpoint | Notes |
|--------|----------|-------|
| `client.latest(params)` | `/1/latest` | Real-time news |
| `client.archive(params)` | `/1/archive` | Historical news |
| `client.sources(params)` | `/1/sources` | Available sources (single page) |
| `client.crypto(params)` | `/1/crypto` | Cryptocurrency news |
| `client.market(params)` | `/1/market` | Market / financial news |
| `client.count(params)` | `/1/count` | Aggregate counts (requires `from_date`, `to_date`) |
| `client.cryptoCount(params)` | `/1/crypto/count` | Aggregate crypto counts |
| `client.marketCount(params)` | `/1/market/count` | Aggregate market counts |

`params` is a `Map<String, Object>`. Use the bundled [`Params`](src/main/java/io/newsdata/api/Params.java) helper for fluent construction — `null` values are dropped automatically so you can chain optional fields without `if`-guards. Values may be `String`, `Number`, `Boolean`, or `Collection<String>` (sent comma-joined). Parameter names are case-insensitive — `qInTitle` and `qintitle` are equivalent.

## Pagination

Two helpers — both work for every endpoint except `sources`:

```java
// 1) scrollAll: follow nextPage and return one merged response.
NewsdataResponse all = client.scrollAll(
        Endpoint.LATEST,
        Params.of().with("q", "news"),
        200); // maxResults; 0 = no cap

// 2) paginate: lazy Stream<NewsdataResponse>, one page at a time.
//    Composes with .limit, .takeWhile, .filter, etc.
client.paginate(Endpoint.LATEST, Params.of().with("q", "news"))
        .limit(5)
        .forEach(page -> process(page.articles(client.objectMapper())));
```

## Raw query

```java
client.latest(Params.of().with("rawQuery", "q=bitcoin&country=us&language=en"));
```

`rawQuery` is mutually exclusive with every other parameter and is parsed and validated against the endpoint's allowed keys before the request leaves.

## Client-side validation

A `NewsdataValidationException` is thrown — before any HTTP request — when:

- a parameter is not accepted by that endpoint;
- mutually-exclusive parameters are set together — `q`/`qInTitle`/`qInMeta`, `country`/`excludecountry`, `category`/`excludecategory`, `language`/`excludelanguage`, `domain`/`domainurl`/`excludedomain`;
- `size` is outside 1–50;
- `sentiment_score` is set without `sentiment`;
- a count endpoint is missing `from_date` or `to_date`.

Booleans for `full_content`, `image`, `video`, and `removeduplicate` are coerced to `"1"`/`"0"`.

## Error handling

All SDK exceptions extend `RuntimeException`, so they don't pollute method signatures with `throws` clauses. Catch by type to react to specific failures:

```java
import io.newsdata.api.exception.*;

try {
    client.latest(Params.of().with("q", "news"));
} catch (NewsdataValidationException e) {
    // bad parameter — e.param()
} catch (NewsdataAuthException e) {
    // 401 / 403
} catch (NewsdataRateLimitException e) {
    // 429 — e.retryAfter() (seconds)
} catch (NewsdataServerException e) {
    // 5xx
} catch (NewsdataApiException e) {
    // other API errors — e.statusCode(), e.responseBody()
} catch (NewsdataNetworkException e) {
    // I/O, timeout, interrupted — e.getCause()
}
```

Hierarchy:

```
NewsdataException (extends RuntimeException)
├── NewsdataValidationException             (.param())
├── NewsdataApiException                    (.statusCode(), .responseBody())
│   ├── NewsdataAuthException               (401 / 403)
│   ├── NewsdataRateLimitException          (429; .retryAfter())
│   └── NewsdataServerException             (5xx)
└── NewsdataNetworkException                (.getCause())
```

## Configuration

```java
NewsDataApiClient client = NewsDataApiClient.builder()
        .apiKey(apiKey)
        .timeout(Duration.ofSeconds(30))               // per-request
        .maxRetries(5)                                 // 1 = no retry
        .retryBackoff(Duration.ofSeconds(2))           // base, exponential
        .retryBackoffMax(Duration.ofSeconds(60))       // cap on a single sleep
        .paginationDelay(Duration.ofSeconds(1))
        .baseUrl("https://staging.example/api/1/")     // staging / proxy
        .includeHeaders(true)                          // attach response headers
        .httpClient(myCustomHttpClient)                // proxies, mTLS, etc.
        .logger((level, msg) -> System.out.println(level + ": " + msg))
        .build();
```

Retries cover network errors, HTTP 429, and 5xx. 429 honours the `Retry-After` header (integer seconds or HTTP-date); otherwise backoff is exponential. Auth (401/403) and other 4xx errors are never retried. The API key is redacted from URLs passed to the logger.

## Concurrency

`NewsDataApiClient` is thread-safe — share one instance across your application. The underlying `HttpClient` reuses connections.

## Development

```bash
mvn -B verify       # compile + run the unit suite (33 tests, no API key needed)
mvn -B test         # tests only
```

The test suite uses Java's built-in `com.sun.net.httpserver.HttpServer` as a local mock — no WireMock, no network, no external test dep.

## Releasing to Maven Central

Releases are automated via `.github/workflows/publish.yml` — push a `v`-prefixed tag whose number matches `pom.xml`'s `<version>` and the workflow signs, uploads, and publishes.

```bash
# bump <version> in pom.xml first
git commit -am "Bump to 0.0.2"
git tag -a v0.0.2 -m "v0.0.2"
git push origin main v0.0.2          # tag push fires publish.yml
```

### One-time setup before the first release

Maven Central is the heaviest of the registries to wire up — it predates OIDC trusted publishing and requires GPG-signed artifacts. The good news: you do this once.

#### 1. Register the `io.newsdata` namespace

- Sign up at <https://central.sonatype.com/>.
- Account → **Add Namespace** → enter `io.newsdata`.
- Verify by adding the displayed TXT record to `newsdata.io`'s DNS. Propagation typically takes minutes; longer for stubborn DNS providers.

#### 2. Create a user token (for the publish workflow)

On <https://central.sonatype.com/account> → **Generate User Token** → copy the **token name** and **token password** (you can't see them again).

#### 3. Generate a GPG key

```bash
gpg --gen-key                                            # interactive; default RSA 3072 is fine
gpg --list-secret-keys --keyid-format LONG               # note the key ID (after sec   rsa3072/)
gpg --keyserver keys.openpgp.org --send-keys <KEY_ID>    # upload public key
gpg --export-secret-keys --armor <KEY_ID> > private.key  # this file goes into GitHub
```

#### 4. Store secrets on GitHub

Repo Settings → **Secrets and variables** → **Actions** → **New repository secret**:

| Secret name | Value |
|---|---|
| `MAVEN_USERNAME` | the user-token *name* from step 2 |
| `MAVEN_PASSWORD` | the user-token *password* from step 2 |
| `GPG_PRIVATE_KEY` | the full contents of `private.key` from step 3 (including `-----BEGIN PGP PRIVATE KEY BLOCK-----` lines) |
| `GPG_PASSPHRASE` | the passphrase you set in step 3 |

Then delete `private.key` from your machine.

#### 5. First publish

Push a tag (`git push origin v0.0.2`). The workflow:

1. Verifies the tag matches `<version>` in `pom.xml`.
2. Runs `mvn verify` (all tests).
3. Runs `mvn -Prelease deploy` — signs every artifact (jar, sources, javadoc, pom) with your GPG key and uploads via the `central-publishing-maven-plugin`.
4. With `autoPublish=true` set in the release profile, the deployment is released directly. (For a manual approval gate, set `autoPublish=false` and approve on <https://central.sonatype.com/publishing>.)

Artifacts appear on Maven Central indexes within ~10–30 minutes after a successful workflow run, then in the public search within 1–4 hours.

### Why no OIDC trusted publishing (yet)?

Unlike npm, PyPI, and pub.dev, the Sonatype Central Portal doesn't yet support tokenless GitHub Actions publishing via OIDC — it's on their roadmap. Until then, the user-token + GPG-signing flow above is the supported path.

## Related libraries

Official Newsdata.io clients across languages and runtimes:

- **Python** — [newsdataapi/python-client](https://github.com/newsdataapi/python-client) ([PyPI](https://pypi.org/project/newsdataapi/))
- **Node.js** — [newsdataapi/newsdata-nodejs-client](https://github.com/newsdataapi/newsdata-nodejs-client) (npm)
- **React (hooks)** — [newsdataapi/newsdata-reactjs-client](https://github.com/newsdataapi/newsdata-reactjs-client) (npm)
- **PHP** — [newsdataapi/php-client](https://github.com/newsdataapi/php-client) ([Packagist](https://packagist.org/packages/newsdataio/newsdataapi))
- **.NET** — [newsdataapi/newsdata-dotnet-sdk](https://github.com/newsdataapi/newsdata-dotnet-sdk) ([NuGet](https://www.nuget.org/packages/Newsdata.Api/))
- **Go** — [newsdataapi/newsdata-go-client](https://github.com/newsdataapi/newsdata-go-client) (Go modules)
- **Dart / Flutter** — [newsdataapi/newsdata-flutter-client](https://github.com/newsdataapi/newsdata-flutter-client) (pub.dev)
- **MCP Server (AI assistants)** — [newsdataapi/newsdata.io-mcp](https://github.com/newsdataapi/newsdata.io-mcp) ([PyPI](https://pypi.org/project/newsdata-mcp/))

Also see [free news datasets](https://github.com/newsdataapi/newsdata.io-free-datasets) for ML / NLP work.

## License

[MIT](./LICENSE)
