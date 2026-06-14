package ai.prism.adapters.out.memory;

import ai.prism.application.port.out.MemoryPort;
import ai.prism.domain.investigation.Investigation;
import ai.prism.domain.investigation.Signal;
import ai.prism.domain.investigation.SignalType;
import java.time.Clock;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * In-memory {@link MemoryPort}: a first implementation that
 * remembers concluded investigations in process and ranks them by naive token
 * overlap. A pgvector + embeddings adapter replaces it for real semantic search
 * (and cross-restart persistence) without touching the port.
 */
public class TokenOverlapMemory implements MemoryPort {

    private static final int MAX_RESULTS = 3;

    private record Entry(String id, String query, String rootCause, String recommendedAction) {
    }

    private record Scored(Entry entry, double score) {
    }

    private final List<Entry> entries = new CopyOnWriteArrayList<>();
    private final Clock clock;

    public TokenOverlapMemory(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public void remember(Investigation investigation) {
        investigation.finding().ifPresent(finding -> {
            String id = investigation.id().toString();
            // Idempotent like the pgvector upsert: re-remembering replaces, never duplicates.
            entries.removeIf(entry -> entry.id().equals(id));
            entries.add(new Entry(id, investigation.request().query(),
                    finding.rootCause(), finding.recommendedAction()));
        });
    }

    @Override
    public Signal findSimilar(String query) {
        Set<String> queryTokens = tokenize(query);
        List<Entry> matches = entries.stream()
                .map(entry -> new Scored(entry, similarity(queryTokens, entry)))
                .filter(scored -> scored.score() > 0)
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .limit(MAX_RESULTS)
                .map(Scored::entry)
                .toList();
        return new Signal(SignalType.MEMORY, query, render(matches), clock.instant());
    }

    private static double similarity(Set<String> queryTokens, Entry entry) {
        Set<String> entryTokens = tokenize(entry.query() + " " + entry.rootCause());
        if (queryTokens.isEmpty() || entryTokens.isEmpty()) {
            return 0;
        }
        long intersection = queryTokens.stream().filter(entryTokens::contains).count();
        Set<String> union = new HashSet<>(queryTokens);
        union.addAll(entryTokens);
        return (double) intersection / union.size();
    }

    private static Set<String> tokenize(String text) {
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .filter(token -> !token.isBlank())
                .collect(Collectors.toSet());
    }

    private static String render(List<Entry> matches) {
        if (matches.isEmpty()) {
            return "No similar past investigations found.";
        }
        return matches.stream()
                .map(entry -> "- \"" + entry.query() + "\" => " + entry.rootCause()
                        + " (recommended: " + entry.recommendedAction() + ")")
                .collect(Collectors.joining("\n"));
    }
}
