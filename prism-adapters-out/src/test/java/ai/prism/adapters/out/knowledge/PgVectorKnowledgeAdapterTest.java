package ai.prism.adapters.out.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit-tests the pure pgvector-literal formatting. The embed + JDBC path needs a
 * live Postgres + embedding model and is covered by an end-to-end run.
 */
class PgVectorKnowledgeAdapterTest {

    @Test
    void formatsAFloatArrayAsAPgvectorLiteral() {
        assertThat(PgVectorKnowledgeAdapter.toVectorLiteral(new float[] {0.1f, -0.2f, 3.0f}))
                .isEqualTo("[0.1,-0.2,3.0]");
    }

    @Test
    void formatsAnEmptyVector() {
        assertThat(PgVectorKnowledgeAdapter.toVectorLiteral(new float[] {})).isEqualTo("[]");
    }
}
