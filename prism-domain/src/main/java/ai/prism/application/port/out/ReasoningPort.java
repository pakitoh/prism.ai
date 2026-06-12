package ai.prism.application.port.out;

import ai.prism.application.reasoning.ReasoningStep;

/**
 * Outbound port for the reasoning engine.
 *
 * <p>Deliberately narrow: given the investigation so far, decide the
 * <em>single</em> next step — gather a specific piece of evidence, or conclude.
 * The application drives the loop; this port only answers "what next?".
 *
 * <p>The implementing adapter is the only place that knows about a specific
 * provider's tool-use protocol and which model is configured.
 */
public interface ReasoningPort {

    ReasoningStep nextStep(InvestigationContext context);
}
