package cero.ninja.agent.codexusage.jobs;

import cero.ninja.agent.codexusage.db.JdbcClient;
import cero.ninja.agent.codexusage.store.Cursors;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(AnnotateJobFeatureFlagsTest.Profile.class)
class AnnotateJobFeatureFlagsTest {

    @Inject
    JdbcClient db;

    @Inject
    Cursors cursors;

    @Inject
    AnnotateJob annotateJob;

    @BeforeEach
    void resetTables() {
        db.sql("DELETE FROM cursor").update();
        db.sql("DELETE FROM annotated_events").update();
        db.sql("DELETE FROM usage_samples").update();
        db.sql("DELETE FROM otel_log_records").update();
    }

    @Test
    void codexDisabledSkipsCodexRowsButStillAnnotatesClaudeRows() {
        insertRaw(1, """
                {
                  "observed_time_unix_nano": 1781208000000000000,
                  "attributes": {
                    "event.name": "codex.sse_event",
                    "conversation.id": "codex-thread-1",
                    "input_token_count": 100,
                    "cached_token_count": 10,
                    "output_token_count": 20,
                    "model": "gpt-5.3-codex"
                  },
                  "resource_attributes": {
                    "host.name": "test-host"
                  }
                }
                """);
        insertRaw(2, """
                {
                  "observed_time_unix_nano": 1781208060000000000,
                  "body": "api_request",
                  "attributes": {
                    "event.name": "api_request",
                    "request_id": "claude-request-1",
                    "session.id": "claude-session-1",
                    "model": "claude-fable-5",
                    "input_tokens": 200,
                    "cache_read_tokens": 50,
                    "output_tokens": 25,
                    "query_source": "user"
                  },
                  "resource_attributes": {
                    "service.name": "claude-code",
                    "host.name": "test-host"
                  }
                }
                """);

        annotateJob.run();

        assertEquals(0, countBySource("codex"));
        assertEquals(1, countBySource("claude"));
        assertEquals(2, cursors.getLong("annotate_log_id", 0));
    }

    @Test
    void annotatesClaudeErrorWithoutCostOrTokens() {
        insertRaw(10, """
                {
                  "observed_time_unix_nano": 1781208060000000000,
                  "body": "api_error",
                  "attributes": {
                    "event.name": "api_error",
                    "request_id": "claude-error-1",
                    "session.id": "claude-session-2",
                    "model": "claude-fable-5",
                    "error_name": "rate_limit_error",
                    "query_source": "user"
                  },
                  "resource_attributes": {
                    "service.name": "claude-code",
                    "host.name": "test-host"
                  }
                }
                """);

        annotateJob.run();

        assertEquals(1, countBySource("claude"));
        assertEquals(10, cursors.getLong("annotate_log_id", 0));
    }

    @Test
    void configEndpointReflectsFeatureFlags() {
        given()
                .when().get("/api/config")
                .then()
                .statusCode(200)
                .body("codexEnabled", is(false))
                .body("claudeEnabled", is(true));
    }

    private void insertRaw(long id, String recordJson) {
        db.sql("""
                INSERT INTO otel_log_records (id, record_json)
                VALUES (:id, :record_json)
                """)
                .param("id", id)
                .param("record_json", recordJson)
                .update();
    }

    private int countBySource(String sourceTool) {
        return db.sql("""
                SELECT count(*) FROM annotated_events
                WHERE source_tool = :source_tool
                """)
                .param("source_tool", sourceTool)
                .query((rs, row) -> rs.getInt(1))
                .single();
    }

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.datasource.jdbc.url",
                    "jdbc:sqlite:target/annotate-job-feature-flags-test.sqlite?journal_mode=WAL&busy_timeout=10000",
                    "quarkus.http.test-port", "0",
                    "quarkus.scheduler.enabled", "false",
                    "codex-usage-dashboard.codex.enabled", "false",
                    "codex-usage-dashboard.claude.enabled", "true");
        }
    }
}
