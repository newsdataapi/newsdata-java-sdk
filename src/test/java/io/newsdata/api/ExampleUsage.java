package io.newsdata.api;

import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import io.newsdata.api.exception.NewsdataAuthException;
import io.newsdata.api.exception.NewsdataException;
import io.newsdata.api.exception.NewsdataRateLimitException;
import io.newsdata.api.exception.NewsdataValidationException;

/**
 * Compile-only example showing the public API surface. Not a runnable test —
 * lives under src/test so it doesn't ship in the artifact jar.
 */
@SuppressWarnings({"unused", "PMD.SystemPrintln"})
final class ExampleUsage {

    private ExampleUsage() {}

    static void simpleLatest() {
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
    }

    static void typedErrorHandling() {
        NewsDataApiClient client = NewsDataApiClient.builder()
                .apiKey(System.getenv("NEWSDATA_API_KEY"))
                .timeout(Duration.ofSeconds(15))
                .maxRetries(3)
                .retryBackoff(Duration.ofSeconds(2))
                .build();
        try {
            client.latest(Params.of().with("q", "news"));
        } catch (NewsdataValidationException e) {
            System.out.println("Invalid param " + e.param() + ": " + e.getMessage());
        } catch (NewsdataAuthException e) {
            System.out.println("Auth failed: HTTP " + e.statusCode());
        } catch (NewsdataRateLimitException e) {
            System.out.println("Rate limited; retry after " + e.retryAfter() + "s");
        } catch (NewsdataException e) {
            System.out.println("Request failed: " + e.getMessage());
        }
    }

    static void scrollAndPaginate() {
        NewsDataApiClient client = NewsDataApiClient.builder()
                .apiKey(System.getenv("NEWSDATA_API_KEY"))
                .build();

        // Merged scroll, capped at 200 articles.
        NewsdataResponse merged = client.scrollAll(
                Endpoint.LATEST,
                Params.of().with("q", "news"),
                200);

        // Lazy Stream, one page at a time. Composes with .limit / .takeWhile.
        try (Stream<NewsdataResponse> stream = client.paginate(
                Endpoint.LATEST, Params.of().with("q", "news"))) {
            stream.limit(5)
                  .forEach(page -> System.out.println(
                          page.articles(client.objectMapper()).size() + " articles"));
        }
    }

    static void countEndpoint() {
        NewsDataApiClient client = NewsDataApiClient.builder()
                .apiKey(System.getenv("NEWSDATA_API_KEY"))
                .build();

        NewsdataResponse resp = client.count(Params.of()
                .with("from_date", "2024-01-01")
                .with("to_date", "2024-01-31")
                .with("interval", "day")
                .with("q", "election"));

        System.out.println(resp.aggregate(client.objectMapper()));
    }
}
