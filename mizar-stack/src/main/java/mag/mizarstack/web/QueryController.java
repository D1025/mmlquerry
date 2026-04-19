package mag.mizarstack.web;

import lombok.RequiredArgsConstructor;
import mag.mizarstack.query.eval.AstJsonSerializer;
import mag.mizarstack.query.integration.QueryExecutionService;
import mag.mizarstack.query.integration.QueryItemFragmentService;
import mag.mizarstack.query.integration.QueryWarmupService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/query")
@RequiredArgsConstructor
public class QueryController {

    private final QueryExecutionService queryExecutionService;
    private final QueryWarmupService queryWarmupService;
    private final QueryItemFragmentService queryItemFragmentService;
    private final AstJsonSerializer astJsonSerializer;

    @PostMapping("/execute")
    public Map<String, Object> execute(@RequestBody ExecuteQueryRequest request) {
        if (request == null || request.query() == null || request.query().isBlank()) {
            throw new IllegalArgumentException("Request must contain non-empty field: query");
        }

        QueryExecutionService.QueryExecutionOutcome outcome = queryExecutionService.execute(request.query(), true);
        QueryExecutionService.QueryExecutionMetrics metrics = outcome.metrics();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("query", request.query());
        response.put("ast", astJsonSerializer.serializeQuery(outcome.ast()));
        response.put("description", outcome.rawResult().getDescription());
        response.put("count", outcome.projectedCount());
        response.put("items", outcome.responseItems());
        response.put("timing", Map.of(
                "parseMs", metrics.parseMs(),
                "executeMs", metrics.executeMs(),
                "projectionMs", metrics.projectionMs(),
                "totalMs", metrics.totalMs()
        ));
        return response;
    }

    @PostMapping("/warmup")
    public QueryWarmupService.WarmupSummary warmup(@RequestBody(required = false) WarmupRequest request) {
        List<String> queries = request == null ? null : request.queries();
        return queryWarmupService.runWarmup(queries);
    }

    @GetMapping("/syntax")
    public Map<String, Object> syntax() {
        return Map.of(
                "examples", List.of(
                        "list of theorem",
                        "list of theorem in ABCMIZ_0",
                        "ABCMIZ_0:func 1 | ref | occur",
                        "list of constructor and list of theorem in ABCMIZ_0",
                        "list of theorem where proposition has infix-term[absolutepatternmmlid='RELAT_1:3'] and proposition has infix-term",
                        "list of theorem where proposition has infix-term[absolutepatternmmlid='RELAT_1:3'] and proposition has infix-term[absolutepatternmmlid='XBOOLE_0:2']",
                        "list of constructor | wherege(ref,2)",
                        "list of theorem | grep('field')"
                ),
                "supportedOperators", List.of(
                        "and", "or", "butnot", "not"
                ),
                "supportedPipelineOperations", List.of(
                        "ref", "occur", "definition", "notation", "redef", "origin", "copy",
                        "termtype ref", "deftype ref", "main mode", "main functor",
                        "filter('key=value' or 'text')", "grep('regex')", "reverse", "invert",
                        "whereeq(op,n)", "wherege(op,n)", "wherele(op,n)", "wheregt(op,n)", "wherelt(op,n)"
                )
        );
    }

    @GetMapping("/items/{itemId}/fragment")
    public Map<String, Object> itemFragment(@PathVariable UUID itemId) {
        QueryItemFragmentService.ItemFragment fragment = queryItemFragmentService.fetchItemFragment(itemId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("item_id", fragment.itemId() == null ? "" : fragment.itemId().toString());
        response.put("lib_id", fragment.libId());
        response.put("article_name", fragment.articleName());
        response.put("source", fragment.source());
        response.put("raw", fragment.raw());
        return response;
    }

    public record ExecuteQueryRequest(String query) {
    }

    public record WarmupRequest(List<String> queries) {
    }
}
