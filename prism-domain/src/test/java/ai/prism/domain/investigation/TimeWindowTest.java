package ai.prism.domain.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TimeWindowTest {

    private static final Instant FROM = Instant.parse("2026-06-10T10:00:00Z");
    private static final Instant TO = Instant.parse("2026-06-10T10:30:00Z");

    @Test
    void exposesItsDuration() {
        assertThat(new TimeWindow(FROM, TO).duration()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void allowsAZeroLengthWindow() {
        assertThat(new TimeWindow(FROM, FROM).duration()).isZero();
    }

    @Test
    void rejectsFromAfterTo() {
        assertThatThrownBy(() -> new TimeWindow(TO, FROM))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullBounds() {
        assertThatNullPointerException().isThrownBy(() -> new TimeWindow(null, TO));
        assertThatNullPointerException().isThrownBy(() -> new TimeWindow(FROM, null));
    }
}
