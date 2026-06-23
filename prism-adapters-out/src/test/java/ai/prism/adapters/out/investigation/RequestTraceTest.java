package ai.prism.adapters.out.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RequestTraceTest {

    @Test
    void exposesTheTraceIdOnlyWithinRunWith() {
        assertThat(RequestTrace.currentTraceId()).isEmpty();
        RequestTrace.runWith("trace-abc", () ->
                assertThat(RequestTrace.currentTraceId()).hasValue("trace-abc"));
        assertThat(RequestTrace.currentTraceId()).isEmpty();   // cleared afterwards
    }

    @Test
    void treatsBlankOrNullAsAbsent() {
        RequestTrace.runWith(null, () -> assertThat(RequestTrace.currentTraceId()).isEmpty());
        RequestTrace.runWith("  ", () -> assertThat(RequestTrace.currentTraceId()).isEmpty());
    }

    @Test
    void clearsEvenWhenTheTaskThrows() {
        assertThatThrownBy(() -> RequestTrace.runWith("trace-abc", () -> {
            throw new RuntimeException("boom");
        })).isInstanceOf(RuntimeException.class);
        assertThat(RequestTrace.currentTraceId()).isEmpty();
    }
}
