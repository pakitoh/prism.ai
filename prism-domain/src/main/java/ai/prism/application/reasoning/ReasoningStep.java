package ai.prism.application.reasoning;

/**
 * One decision made by the reasoning engine at a single point in an
 * investigation: either gather a specific piece of evidence (a tool call) or
 * conclude. The application loop switches over these exhaustively.
 *
 * <p>This is the model-agnostic vocabulary that crosses the {@code ReasoningPort}
 * boundary. How a particular provider's tool-use response maps onto these types
 * is the adapter's concern, not the application's.
 */
public sealed interface ReasoningStep
        permits QueryMetrics, SearchLogs, GetTrace, SearchTraces, SearchPastInvestigations, Conclusion {
}
