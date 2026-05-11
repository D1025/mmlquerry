package mag.mizarstack.query.integration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueryWarmupService {

    private static final List<String> DEFAULT_WARMUP_QUERIES = List.of(
            "list of theorem in ABCMIZ_0",
            "list of theorem where proposition has InfixTerm[absolutepatternmmlid='RELAT_1:3'] and proposition has InfixTerm"
    );

    private final QueryExecutionService queryExecutionService;

    @Value("${app.query.warmup.enabled:true}")
    private boolean warmupEnabled;

    @EventListener(ApplicationReadyEvent.class)
    public void warmupOnStartup() {
        if (!warmupEnabled) {
            log.info("Query warmup disabled (app.query.warmup.enabled=false)");
            return;
        }
        WarmupSummary summary = runWarmup(DEFAULT_WARMUP_QUERIES);
        log.info(
                "Query warmup finished: {} queries, total={}ms, successful={}, failed={}",
                summary.entries().size(),
                summary.totalMs(),
                summary.entries().stream().filter(WarmupEntry::success).count(),
                summary.entries().stream().filter(e -> !e.success()).count()
        );
    }

    public WarmupSummary runWarmup(List<String> queries) {
        List<String> effectiveQueries = sanitizeQueries(queries);
        long start = System.currentTimeMillis();
        List<WarmupEntry> entries = new ArrayList<>();

        for (String query : effectiveQueries) {
            try {
                QueryExecutionService.QueryExecutionOutcome outcome = queryExecutionService.execute(query, false);
                QueryExecutionService.QueryExecutionMetrics metrics = outcome.metrics();
                entries.add(new WarmupEntry(
                        query,
                        true,
                        null,
                        outcome.totalCount(),
                        metrics.parseMs(),
                        metrics.executeMs(),
                        metrics.projectionMs(),
                        metrics.totalMs()
                ));
            } catch (Exception ex) {
                entries.add(new WarmupEntry(
                        query,
                        false,
                        ex.getMessage(),
                        0,
                        0,
                        0,
                        0,
                        0
                ));
            }
        }

        long totalMs = System.currentTimeMillis() - start;
        return new WarmupSummary(Instant.now().toString(), totalMs, entries);
    }

    private List<String> sanitizeQueries(List<String> queries) {
        List<String> source = (queries == null || queries.isEmpty()) ? DEFAULT_WARMUP_QUERIES : queries;
        List<String> out = new ArrayList<>();
        for (String q : source) {
            if (q != null) {
                String trimmed = q.trim();
                if (!trimmed.isBlank()) {
                    out.add(trimmed);
                }
            }
        }
        return out.isEmpty() ? DEFAULT_WARMUP_QUERIES : out;
    }

    public record WarmupSummary(
            String timestamp,
            long totalMs,
            List<WarmupEntry> entries
    ) {
    }

    public record WarmupEntry(
            String query,
            boolean success,
            String error,
            int count,
            long parseMs,
            long executeMs,
            long projectionMs,
            long totalMs
    ) {
    }
}
