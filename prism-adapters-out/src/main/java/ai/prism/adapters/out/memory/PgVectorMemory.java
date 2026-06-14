package ai.prism.adapters.out.memory;

import ai.prism.application.port.out.MemoryPort;
import ai.prism.domain.investigation.Investigation;
import ai.prism.domain.investigation.Signal;
import ai.prism.domain.investigation.SignalType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * {@link MemoryPort} backed by pgvector. Concluded investigations
 * are embedded (via the configured {@link EmbeddingModel}) and stored; recall is a
 * cosine-distance nearest-neighbour search. Plain JDBC, plain SQL — the embedding
 * is passed as a pgvector literal cast with {@code ?::vector}.
 *
 * <p>Provider-agnostic: it depends only on Spring AI's {@code EmbeddingModel}, so
 * the embedding provider (Gemini by default, or a local model) is a wiring choice.
 */
public class PgVectorMemory implements MemoryPort {

    private static final Logger log = LoggerFactory.getLogger(PgVectorMemory.class);

    private static final int MAX_RESULTS = 3;

    private static final String UPSERT = """
            INSERT INTO investigation_embeddings
                (investigation_id, query, root_cause, recommended_action, embedding)
            VALUES (?, ?, ?, ?, ?::vector)
            ON CONFLICT (investigation_id) DO UPDATE SET
                query = EXCLUDED.query,
                root_cause = EXCLUDED.root_cause,
                recommended_action = EXCLUDED.recommended_action,
                embedding = EXCLUDED.embedding
            """;

    private static final String SEARCH = """
            SELECT query, root_cause, recommended_action,
                   1 - (embedding <=> ?::vector) AS similarity
            FROM investigation_embeddings
            ORDER BY embedding <=> ?::vector
            LIMIT ?
            """;

    private final EmbeddingModel embeddingModel;
    private final DataSource dataSource;
    private final Clock clock;

    public PgVectorMemory(EmbeddingModel embeddingModel, DataSource dataSource, Clock clock) {
        this.embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel must not be null");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public void remember(Investigation investigation) {
        investigation.finding().ifPresent(finding -> {
            String query = investigation.request().query();
            String embedding = toVectorLiteral(embeddingModel.embed(
                    query + "\n" + finding.rootCause() + "\n" + finding.recommendedAction()));
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(UPSERT)) {
                statement.setObject(1, investigation.id().value());
                statement.setString(2, query);
                statement.setString(3, finding.rootCause());
                statement.setString(4, finding.recommendedAction());
                statement.setString(5, embedding);
                statement.executeUpdate();
            } catch (SQLException failure) {
                throw new MemoryException("Failed to remember investigation " + investigation.id(), failure);
            }
        });
    }

    @Override
    public Signal findSimilar(String query) {
        // Best-effort: memory recall (embedding + DB) must never fail an otherwise-fine
        // investigation — a 429 or DB error degrades to "unavailable" rather than throwing.
        try {
            String content = recall(query);
            log.debug("Memory recall for \"{}\": {}", query, content);
            return new Signal(SignalType.MEMORY, query, content, clock.instant());
        } catch (RuntimeException failure) {
            log.warn("Memory recall failed for \"{}\": {}", query, failure.toString());
            return new Signal(SignalType.MEMORY, query, "Memory is currently unavailable.", clock.instant());
        }
    }

    private String recall(String query) {
        String embedding = toVectorLiteral(embeddingModel.embed(query));
        List<String> lines = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SEARCH)) {
            statement.setString(1, embedding);
            statement.setString(2, embedding);
            statement.setInt(3, MAX_RESULTS);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    lines.add("- \"" + rows.getString("query") + "\" => " + rows.getString("root_cause")
                            + " (recommended: " + rows.getString("recommended_action")
                            + "; similarity " + String.format("%.2f", rows.getDouble("similarity")) + ")");
                }
            }
        } catch (SQLException failure) {
            throw new MemoryException("Failed to search past investigations", failure);
        }
        return lines.isEmpty() ? "No similar past investigations found." : String.join("\n", lines);
    }

    /** Formats an embedding as a pgvector literal, e.g. {@code [0.1,-0.2,3.0]}. */
    static String toVectorLiteral(float[] embedding) {
        StringJoiner joiner = new StringJoiner(",", "[", "]");
        for (float value : embedding) {
            joiner.add(Float.toString(value));
        }
        return joiner.toString();
    }
}
