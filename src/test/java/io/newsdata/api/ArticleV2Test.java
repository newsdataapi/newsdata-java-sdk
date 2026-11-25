package io.newsdata.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The v2 article layout: field names, groups, and the plan-notice wrinkle. */
class ArticleV2Test {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** One article exactly as API v2 sends it, every group populated. The
     *  real-time stream sends this same layout. */
    private static final String V2_ARTICLE = """
            {
              "id": "9f8e7d6c5b4a39281706f5e4d3c2b1a0",
              "title": "Central bank holds rates steady as inflation cools",
              "meta_description": "The central bank kept its key rate unchanged on Wednesday.",
              "content": "Full article text...",
              "url": "https://www.example.com/business/central-bank-holds-rates",
              "published_at": "2026-10-22T06:15:00Z",
              "fetched_at": "2026-10-22T06:18:42Z",
              "datatype": "news",
              "language": "english",
              "country": ["india"],
              "category": ["business"],
              "keyword": ["interest rates", "inflation"],
              "creator": ["Jane Doe"],
              "duplicate": false,
              "media": {"image_url": "https://www.example.com/images/rates.jpg", "video_url": null},
              "source": {
                "name": "Example News",
                "url": "https://www.example.com",
                "icon_url": "https://www.example.com/icon.png",
                "priority": 1520
              },
              "ai": {
                "summary": "The central bank left its policy rate unchanged.",
                "sentiment": {"label": "neutral", "score": {"negative": 4.12, "neutral": 90.33, "positive": 5.55}},
                "tag": ["banking and finance", "economy"],
                "region": ["mumbai,maharashtra,india", "delhi,india"],
                "organization": ["reserve bank of india"]
              }
            }""";

    private static ArticleV2 parse(String json) throws Exception {
        return MAPPER.readValue(json, ArticleV2.class);
    }

    @Test
    @DisplayName("v2 spells the top-level fields id/url/published_at")
    void decodesTopLevelFields() throws Exception {
        ArticleV2 a = parse(V2_ARTICLE);

        assertEquals("9f8e7d6c5b4a39281706f5e4d3c2b1a0", a.id());
        assertEquals("Central bank holds rates steady as inflation cools", a.title());
        assertEquals("https://www.example.com/business/central-bank-holds-rates", a.url());
        assertEquals("2026-10-22T06:15:00Z", a.publishedAt());
        assertEquals("2026-10-22T06:18:42Z", a.fetchedAt());
        assertEquals("The central bank kept its key rate unchanged on Wednesday.", a.metaDescription());
        assertEquals("Full article text...", a.content());
        assertEquals("english", a.language());
        assertEquals("news", a.dataType());
        assertEquals(Boolean.FALSE, a.duplicate());
        assertEquals(List.of("india"), a.country());
        assertEquals(List.of("business"), a.category());
        assertEquals(List.of("interest rates", "inflation"), a.keyword());
        assertEquals(List.of("Jane Doe"), a.creator());
    }

    @Test
    @DisplayName("source, media and ai arrive as groups")
    void decodesGroupedFields() throws Exception {
        ArticleV2 a = parse(V2_ARTICLE);

        assertEquals("Example News", a.source().name());
        assertEquals("https://www.example.com", a.source().url());
        assertEquals("https://www.example.com/icon.png", a.source().iconUrl());
        assertEquals(Integer.valueOf(1520), a.source().priority());

        assertEquals("https://www.example.com/images/rates.jpg", a.media().imageUrl());
        assertNull(a.media().videoUrl(), "a null video_url stays null");

        assertEquals("The central bank left its policy rate unchanged.", a.ai().summary());
        assertEquals("neutral", a.ai().sentiment().label());
        assertEquals(90.33, a.ai().sentiment().score().get("neutral"), 1e-9);
        assertEquals(4.12, a.ai().sentiment().score().get("negative"), 1e-9);
        assertEquals(List.of("banking and finance", "economy"), a.ai().tag());
        assertEquals(List.of("reserve bank of india"), a.ai().organization());
    }

    @Test
    @DisplayName("ai.region entries contain commas and are not split")
    void regionEntriesKeepTheirCommas() throws Exception {
        ArticleV2 a = parse(V2_ARTICLE);
        assertEquals(List.of("mumbai,maharashtra,india", "delhi,india"), a.ai().region());
    }

    @Test
    @DisplayName("an absent group is null, an absent list is empty")
    void absentFields() throws Exception {
        ArticleV2 a = parse("{\"id\":\"x\",\"title\":\"hi\"}");

        assertEquals("x", a.id());
        assertNull(a.source());
        assertNull(a.media());
        assertNull(a.ai());
        assertNull(a.duplicate());
        assertEquals(List.of(), a.country());
        assertEquals(List.of(), a.keyword());
    }

    @Test
    @DisplayName("an unknown field is ignored, not an error")
    void unknownFieldsAreIgnored() throws Exception {
        ArticleV2 a = parse("{\"id\":\"x\",\"something_new\":42}");
        assertEquals("x", a.id());
    }

    // ---- plan notices -----------------------------------------------------

    @Test
    @DisplayName("a plan notice where a list is documented does not fail decoding")
    void planNoticeInAListField() throws Exception {
        ArticleV2 a = parse("{\"id\":\"x\",\"ai\":{\"tag\":\"ONLY AVAILABLE IN PROFESSIONAL AND CORPORATE PLANS\"}}");

        assertEquals(1, a.ai().tag().size());
        assertTrue(ArticleV2.isPlanNotice(a.ai().tag().get(0)));
    }

    @Test
    @DisplayName("a plan notice replacing the sentiment object lands in its label")
    void planNoticeReplacingSentiment() throws Exception {
        ArticleV2 a = parse("{\"id\":\"x\",\"ai\":{\"sentiment\":\"ONLY AVAILABLE IN PAID PLANS\"}}");

        assertTrue(ArticleV2.isPlanNotice(a.ai().sentiment().label()));
        assertEquals(List.of(), List.copyOf(a.ai().sentiment().score().keySet()));
    }

    @Test
    @DisplayName("a plan notice inside the sentiment label is recognised too")
    void planNoticeInsideSentimentLabel() throws Exception {
        ArticleV2 a = parse("{\"id\":\"x\",\"ai\":{\"sentiment\":{\"label\":\"ONLY AVAILABLE IN PAID PLANS\"}}}");
        assertTrue(ArticleV2.isPlanNotice(a.ai().sentiment().label()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ONLY AVAILABLE IN PAID PLANS",
            "ONLY AVAILABLE IN PROFESSIONAL AND CORPORATE PLANS",
            "  only available in corporate plans  ",
    })
    void planNoticesAreRecognised(String value) {
        assertTrue(ArticleV2.isPlanNotice(value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "india", "neutral", "available in paid plans"})
    void realValuesAreNotPlanNotices(String value) {
        assertFalse(ArticleV2.isPlanNotice(value));
    }

    @Test
    void nullIsNotAPlanNotice() {
        assertFalse(ArticleV2.isPlanNotice(null));
    }

    // ---- lenient numbers --------------------------------------------------

    @Test
    @DisplayName("source.priority accepts a number, a quoted number, or neither")
    void lenientPriority() throws Exception {
        assertEquals(Integer.valueOf(1520), parse("{\"source\":{\"priority\":1520}}").source().priority());
        assertEquals(Integer.valueOf(1520), parse("{\"source\":{\"priority\":\"1520\"}}").source().priority());
        assertNull(parse("{\"source\":{\"priority\":null}}").source().priority());
        assertNull(parse("{\"source\":{\"priority\":\"ONLY AVAILABLE IN PAID PLANS\"}}").source().priority(),
                "a non-numeric priority must not fail the article");
    }

    // ---- the sentiment score map -----------------------------------------

    @Test
    @DisplayName("the sentiment score is never null and cannot be modified")
    void sentimentScoreIsAnUnmodifiableMap() throws Exception {
        ArticleV2 a = parse(V2_ARTICLE);
        assertNotNull(a.ai().sentiment().score());
        assertThrows(UnsupportedOperationException.class,
                () -> a.ai().sentiment().score().put("mixed", 1.0));
    }

    @Test
    @DisplayName("a class the platform adds later is still decoded")
    void scoreKeepsUnknownClasses() throws Exception {
        ArticleV2 a = parse("{\"ai\":{\"sentiment\":{\"label\":\"mixed\",\"score\":{\"mixed\":51.0}}}}");
        assertEquals(51.0, a.ai().sentiment().score().get("mixed"), 1e-9);
    }

    // ---- through the envelope --------------------------------------------

    @Test
    @DisplayName("articlesV2 decodes the v2 envelope's results")
    void envelopeArticlesV2() throws Exception {
        String body = "{\"status\":\"success\",\"total_results\":2841,"
                + "\"next_page\":\"1792649700000000000\",\"results\":[" + V2_ARTICLE + "]}";
        NewsdataResponse response = new NewsdataResponse(
                "success",
                NewsDataApiClient.totalResultsOf(MAPPER.readTree(body)),
                MAPPER.readTree(body).get("results"),
                NewsDataApiClient.nextPageOf(MAPPER.readTree(body)),
                null);

        assertEquals(2841, response.totalResults());
        assertEquals("1792649700000000000", response.nextPage());

        List<ArticleV2> articles = response.articlesV2(MAPPER);
        assertEquals(1, articles.size());
        assertEquals("Example News", articles.get(0).source().name());
        assertEquals("https://www.example.com/business/central-bank-holds-rates", articles.get(0).url());
    }

    @Test
    @DisplayName("articlesV2 on a count endpoint's aggregate page is empty")
    void envelopeArticlesV2OnAnAggregateIsEmpty() throws Exception {
        NewsdataResponse response = new NewsdataResponse(
                "success", 0, MAPPER.readTree("{\"2026-10-22\":5}"), null, null);
        assertEquals(List.of(), response.articlesV2(MAPPER));
    }

    @Test
    @DisplayName("articlesV2 with no results at all is empty, not null")
    void envelopeArticlesV2WithNoResults() {
        NewsdataResponse response = new NewsdataResponse("success", 0, null, null, null);
        assertEquals(List.of(), response.articlesV2(MAPPER));
    }
}
