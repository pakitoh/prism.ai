package ai.prism.adapters.out.memory;

import static org.assertj.core.api.Assertions.assertThat;

import ai.prism.domain.investigation.Signal;
import ai.prism.domain.investigation.SignalType;
import java.io.PrintWriter;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

class PgVectorMemoryTest {

    @Test
    void formatsAFloatArrayAsAPgvectorLiteral() {
        assertThat(PgVectorMemory.toVectorLiteral(new float[] {0.1f, -0.2f, 3.0f}))
                .isEqualTo("[0.1,-0.2,3.0]");
    }

    @Test
    void formatsAnEmptyVector() {
        assertThat(PgVectorMemory.toVectorLiteral(new float[] {})).isEqualTo("[]");
    }

    @Test
    void findSimilarDegradesGracefullyWhenTheEmbeddingCallFails() {
        EmbeddingModel failing = new EmbeddingModel() {
            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                throw new RuntimeException("429 rate limit");
            }

            @Override
            public float[] embed(Document document) {
                throw new RuntimeException("429 rate limit");
            }
        };
        PgVectorMemory adapter = new PgVectorMemory(
                failing, UNUSED_DATA_SOURCE, Clock.fixed(Instant.parse("2026-06-12T10:00:00Z"), ZoneOffset.UTC));

        Signal signal = adapter.findSimilar("checkout latency");

        assertThat(signal.type()).isEqualTo(SignalType.MEMORY);
        assertThat(signal.content()).isEqualTo("Memory is currently unavailable.");
    }

    /** A DataSource that is never touched (the embedding call fails before any DB access). */
    private static final DataSource UNUSED_DATA_SOURCE = new DataSource() {
        @Override
        public Connection getConnection() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Connection getConnection(String username, String password) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return null;
        }

        @Override
        public <T> T unwrap(Class<T> iface) {
            return null;
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    };
}
