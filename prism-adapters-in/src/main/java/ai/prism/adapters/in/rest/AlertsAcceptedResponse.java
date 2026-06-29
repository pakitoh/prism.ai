package ai.prism.adapters.in.rest;

import java.util.List;

/**
 * REST body for an accepted alert webhook: the ids of the investigations spawned from the firing
 * alerts (empty when the delivery carried only resolved alerts), each pollable via
 * {@code GET /investigations/{id}}.
 */
public record AlertsAcceptedResponse(List<String> investigationIds) {
}
