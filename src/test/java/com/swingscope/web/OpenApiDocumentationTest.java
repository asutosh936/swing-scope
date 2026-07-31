package com.swingscope.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The generated OpenAPI document is a deliverable, so it gets tested like one.
 *
 * <p>The load-bearing assertion is the last one: only {@code /api/**} may appear. The Thymeleaf form
 * handlers return HTML and are not an API surface; listing them in a spec would tell a caller they
 * can POST JSON to routes that would hand back a redirect and a rendered page.
 */
@SpringBootTest
@ActiveProfiles("test")
class OpenApiDocumentationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MockMvc mockMvc;

    @Autowired
    OpenApiDocumentationTest(WebApplicationContext context) {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    private JsonNode spec() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(body);
    }

    @Test
    void servesAnOpenApiDocument() throws Exception {
        JsonNode spec = spec();

        assertThat(spec.get("openapi").asText()).startsWith("3.");
        assertThat(spec.at("/info/title").asText()).isEqualTo("swing-scope API");
        assertThat(spec.at("/info/description").asText())
                .contains("Not financial advice")
                .contains("read and compute only");
    }

    @Test
    @DisplayName("every JSON endpoint is documented, and each one carries a description")
    void documentsEveryJsonEndpoint() throws Exception {
        JsonNode paths = spec().get("paths");

        assertThat(paths.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "/api/analyze",
                "/api/marketdata/{symbol}",
                "/api/marketdata/search",
                "/api/marketdata/status",
                "/api/scan",
                "/api/scan/watchlist",
                "/api/watchlist",
                "/api/watchlist/{id}/note",
                "/api/journal",
                "/api/journal/stats",
                "/api/journal/{id}");

        List<String> undocumented = new ArrayList<>();
        paths.fields().forEachRemaining(path -> path.getValue().fields().forEachRemaining(op -> {
            JsonNode summary = op.getValue().get("summary");
            JsonNode description = op.getValue().get("description");
            if (summary == null || summary.asText().isBlank()
                    || description == null || description.asText().isBlank()) {
                undocumented.add(op.getKey().toUpperCase() + " " + path.getKey());
            }
        }));

        assertThat(undocumented)
                .as("every operation needs both a summary and a description")
                .isEmpty();
    }

    @Test
    @DisplayName("UI form handlers stay out of the API spec")
    void doesNotDocumentTheHtmlRoutes() throws Exception {
        assertThat(spec().get("paths").fieldNames()).toIterable()
                .allSatisfy(path -> assertThat(path).startsWith("/api/"))
                .doesNotContain("/", "/analyze", "/scan", "/journal", "/plan", "/watchlist",
                        "/journal/{id}/fill", "/journal/{id}/close", "/journal/from-calculator");
    }

    @Test
    void groupsOperationsUnderTheFourFeatureTags() throws Exception {
        JsonNode spec = spec();

        List<String> tagNames = new ArrayList<>();
        spec.get("tags").forEach(tag -> tagNames.add(tag.get("name").asText()));
        assertThat(tagNames).containsExactlyInAnyOrder(
                "Calculator", "Market data", "Scan & watchlist", "Journal");

        assertThat(spec.at("/paths/~1api~1analyze/post/tags/0").asText()).isEqualTo("Calculator");
        assertThat(spec.at("/paths/~1api~1journal~1stats/get/tags/0").asText()).isEqualTo("Journal");
        assertThat(spec.at("/paths/~1api~1scan/post/tags/0").asText()).isEqualTo("Scan & watchlist");
        assertThat(spec.at("/paths/~1api~1marketdata~1status/get/tags/0").asText())
                .isEqualTo("Market data");
    }

    @Test
    @DisplayName("the traps a caller would otherwise hit are spelled out in the descriptions")
    void documentsTheNonObviousBehaviour() throws Exception {
        JsonNode paths = spec().get("paths");

        // A rule failure is a 200, not an error.
        assertThat(paths.at("/~1api~1analyze/post/description").asText())
                .contains("pass: false");

        // null uptrend means "not enough history", not "false".
        assertThat(paths.at("/~1api~1marketdata~1{symbol}/get/description").asText())
                .contains("null` means fewer than 200 bars")
                .contains("millions");

        // A cold scan blocks for minutes on the free tier.
        assertThat(paths.at("/~1api~1scan/post/description").asText())
                .contains("8 calls a minute")
                .contains("average");

        // Only wins and losses count toward the scorecard.
        assertThat(paths.at("/~1api~1journal~1stats/get/description").asText())
                .contains("completed trades only")
                .contains("all three");
    }

    @Test
    void documentsTheErrorResponses() throws Exception {
        JsonNode paths = spec().get("paths");

        assertThat(paths.at("/~1api~1analyze/post/responses/400").isMissingNode()).isFalse();
        assertThat(paths.at("/~1api~1journal~1{id}/get/responses/404").isMissingNode()).isFalse();
        assertThat(paths.at("/~1api~1marketdata~1{symbol}/get/responses/429").isMissingNode()).isFalse();
        assertThat(paths.at("/~1api~1marketdata~1{symbol}/get/responses/503").isMissingNode()).isFalse();
    }

    @Test
    void swaggerUiIsServed() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }
}
