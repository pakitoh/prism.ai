package ai.prism.adapters.out.persistence;

import ai.prism.application.port.out.InvestigationRepository;
import ai.prism.domain.investigation.Confidence;
import ai.prism.domain.investigation.Finding;
import ai.prism.domain.investigation.Investigation;
import ai.prism.domain.investigation.InvestigationId;
import ai.prism.domain.investigation.InvestigationRequest;
import ai.prism.domain.investigation.InvestigationStatus;
import ai.prism.domain.investigation.RequestSource;
import ai.prism.domain.investigation.Signal;
import ai.prism.domain.investigation.SignalType;
import ai.prism.domain.investigation.TimeWindow;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * {@link InvestigationRepository} backed by PostgreSQL via plain JDBC. The
 * aggregate is stored in a single row; its signals are kept as a JSONB array.
 */
public class PostgresInvestigationRepository implements InvestigationRepository {

    private static final String UPSERT = """
            INSERT INTO investigations (id, query, service, window_from, window_to, source, status,
                finding_root_cause, finding_evidence, finding_recommended_action, finding_confidence,
                failure_reason, signals)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            ON CONFLICT (id) DO UPDATE SET
                status = EXCLUDED.status,
                finding_root_cause = EXCLUDED.finding_root_cause,
                finding_evidence = EXCLUDED.finding_evidence,
                finding_recommended_action = EXCLUDED.finding_recommended_action,
                finding_confidence = EXCLUDED.finding_confidence,
                failure_reason = EXCLUDED.failure_reason,
                signals = EXCLUDED.signals
            """;

    private static final String SELECT_BY_ID = """
            SELECT id, query, service, window_from, window_to, source, status,
                   finding_root_cause, finding_evidence, finding_recommended_action, finding_confidence,
                   failure_reason, signals
            FROM investigations WHERE id = ?
            """;

    private final DataSource dataSource;
    private final JsonMapper jsonMapper;

    public PostgresInvestigationRepository(DataSource dataSource, JsonMapper jsonMapper) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "objectMapper must not be null");
    }

    @Override
    public void save(Investigation investigation) {
        InvestigationRequest request = investigation.request();
        Finding finding = investigation.finding().orElse(null);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(UPSERT)) {
            statement.setObject(1, investigation.id().value());
            statement.setString(2, request.query());
            statement.setString(3, request.service().orElse(null));
            statement.setObject(4, offsetOrNull(request.window().map(TimeWindow::from).orElse(null)));
            statement.setObject(5, offsetOrNull(request.window().map(TimeWindow::to).orElse(null)));
            statement.setString(6, request.source().name());
            statement.setString(7, investigation.status().name());
            statement.setString(8, finding != null ? finding.rootCause() : null);
            statement.setString(9, finding != null ? finding.evidence() : null);
            statement.setString(10, finding != null ? finding.recommendedAction() : null);
            statement.setString(11, finding != null ? finding.confidence().name() : null);
            statement.setString(12, investigation.failureReason().orElse(null));
            statement.setString(13, writeSignals(investigation.signals()));
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new PersistenceException("Failed to save investigation " + investigation.id(), failure);
        }
    }

    @Override
    public Optional<Investigation> findById(InvestigationId id) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_BY_ID)) {
            statement.setObject(1, id.value());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(reconstruct(rows)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new PersistenceException("Failed to load investigation " + id, failure);
        }
    }

    private Investigation reconstruct(ResultSet row) throws SQLException {
        InvestigationId id = new InvestigationId(row.getObject("id", java.util.UUID.class));
        InvestigationRequest request = new InvestigationRequest(
                row.getString("query"),
                Optional.ofNullable(row.getString("service")),
                window(row),
                RequestSource.valueOf(row.getString("source")));
        InvestigationStatus status = InvestigationStatus.valueOf(row.getString("status"));
        Finding finding = finding(row);
        String failureReason = row.getString("failure_reason");
        List<Signal> signals = readSignals(row.getString("signals"));
        return Investigation.rehydrate(id, request, status, signals, finding, failureReason);
    }

    private static Optional<TimeWindow> window(ResultSet row) throws SQLException {
        OffsetDateTime from = row.getObject("window_from", OffsetDateTime.class);
        OffsetDateTime to = row.getObject("window_to", OffsetDateTime.class);
        if (from == null || to == null) {
            return Optional.empty();
        }
        return Optional.of(new TimeWindow(from.toInstant(), to.toInstant()));
    }

    private static Finding finding(ResultSet row) throws SQLException {
        String rootCause = row.getString("finding_root_cause");
        if (rootCause == null) {
            return null;
        }
        return new Finding(
                rootCause,
                row.getString("finding_evidence"),
                row.getString("finding_recommended_action"),
                Confidence.valueOf(row.getString("finding_confidence")));
    }

    private static OffsetDateTime offsetOrNull(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private String writeSignals(List<Signal> signals) {
        List<SignalRow> rows = signals.stream()
                .map(s -> new SignalRow(s.type().name(), s.query(), s.content(), s.observedAt().toString()))
                .toList();
        try {
            return jsonMapper.writeValueAsString(rows);
        } catch (Exception failure) {
            throw new PersistenceException("Failed to serialize signals", failure);
        }
    }

    private List<Signal> readSignals(String json) {
        try {
            List<SignalRow> rows = jsonMapper.readValue(json, new TypeReference<List<SignalRow>>() {
            });
            return rows.stream()
                    .map(r -> new Signal(SignalType.valueOf(r.type()), r.query(), r.content(), Instant.parse(r.observedAt())))
                    .toList();
        } catch (Exception failure) {
            throw new PersistenceException("Failed to deserialize signals", failure);
        }
    }

    /** JSON shape for a persisted signal — keeps Jackson out of the domain. */
    private record SignalRow(String type, String query, String content, String observedAt) {
    }
}
