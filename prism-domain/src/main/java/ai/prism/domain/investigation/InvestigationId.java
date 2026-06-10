package ai.prism.domain.investigation;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity of an {@link Investigation}.
 */
public record InvestigationId(UUID value) {

    public InvestigationId {
        Objects.requireNonNull(value, "InvestigationId value must not be null");
    }

    public static InvestigationId newId() {
        return new InvestigationId(UUID.randomUUID());
    }

    public static InvestigationId of(String value) {
        return new InvestigationId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
