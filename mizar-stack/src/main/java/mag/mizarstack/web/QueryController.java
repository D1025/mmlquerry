package mag.mizarstack.web;

import lombok.RequiredArgsConstructor;
import mag.mizarstack.query.eval.AstJsonSerializer;
import mag.mizarstack.query.integration.QueryExecutionService;
import mag.mizarstack.query.integration.QueryItemFragmentService;
import mag.mizarstack.query.integration.QueryWarmupService;
import mag.mizarstack.xml_names.ESXAttributeName;
import mag.mizarstack.xml_names.ESXElementName;
import org.springframework.web.bind.annotation.*;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
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

        QueryExecutionService.QueryPageRequest pageRequest = new QueryExecutionService.QueryPageRequest(
                request.page(),
                request.size(),
                request.sortBy(),
                request.sortDirection(),
                request.filter()
        );
        QueryExecutionService.QueryExecutionOutcome outcome = queryExecutionService.execute(request.query(), true, pageRequest);
        QueryExecutionService.QueryExecutionMetrics metrics = outcome.metrics();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("query", request.query());
        response.put("ast", astJsonSerializer.serializeQuery(outcome.ast()));
        response.put("description", outcome.rawResult().getDescription());
        response.put("count", outcome.totalCount());
        response.put("page", outcome.page());
        response.put("size", outcome.size());
        response.put("sortBy", outcome.sortBy());
        response.put("sortDirection", outcome.sortDirection());
        response.put("filter", outcome.filter());
        response.put("returnedCount", outcome.responseItems().size());
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
                        "list of theorem butnot list of definition",
                        "list of theorem and not list of definition",
                        "list of theorem and not (list of theorem where proposition has Thesis)",
                        "list of theorem | ref | occur",
                        "list of theorem where proposition has Thesis",
                        "list of theorem where proposition has InfixTerm[spelling='Element']",
                        "list of theorem where proposition has not InfixTerm[spelling='Element']",
                        "list of theorem where proposition has InfixTerm spelling 'Element'",
                        "list of theorem where proposition has InfixTerm[absolutepatternmmlid='RELAT_1:3'] and proposition has InfixTerm",
                        "list of theorem where proposition has InfixTerm[absolutepatternmmlid='RELAT_1:3'] and proposition has InfixTerm[absolutepatternmmlid='XBOOLE_0:2']",
                        "list of statement where proposition has InfixTerm spelling '+' and proposition has InfixTerm spelling '\\*'",
                        "list of statement where proposition has InfixTerm[absolutepatternmmlid='XCMPLX_0:4'] and proposition has InfixTerm[absolutepatternmmlid='XCMPLX_0:5']",
                        "list of symbols",
                        "list of symbols where spelling '+'",
                        "list of symbols where spelling '\\*'",
                        "occurrences of symbols",
                        "occurrences of symbols | filter('spelling=ali*')",
                        "occurrences of symbols | filter('spelling=ali\\_ke')",
                        "occurrences of symbols | filter('spelling=\\*')",
                        "list of definition | nodes Item where has \"*-pattern\"[spelling='Noeth*']",
                        "list of definition | nodes Item where has \"*-pattern\"[spelling='A\\*B\\_C']",
                        "list of definition where item has Redefine[occurs='true'] and item has AttributePattern[spelling='Noetherian']",
                        "list of definition | nodes Item[kind='Attribute-Definition'] where has Redefine[occurs='true'] and has AttributePattern[spelling='Noetherian']",
                        "list of definition | nodes Item where has Redefine[occurs='true'] and has *[spelling='Noetherian']",
                        "list of definition | nodes Item where has * spelling 'Noetherian'",
                        "list of definition | nodes Item where has * not spelling 'Noetherian'",
                        "list of definition | nodes Item where spelling 'Noetherian'",
                        "list of definition | nodes Item where not spelling 'Noetherian'",
                        "list of definition | nodes Item where not has Redefine[occurs='true']",
                        "list of definition | nodes Item where has not AttributePattern[spelling='Noetherian']",
                        "list of definition | nodes Item where redefine true and has *[spelling='Noetherian']",
                        "list of theorem | wherege(ref,2)",
                        "list of theorem | grep('field')"
                ),
                "supportedOperators", List.of(
                        "and", "or", "butnot", "not"
                ),
                "supportedPipelineOperations", List.of(
                        "ref", "occur", "definition", "notation", "redef", "origin", "copy",
                        "termtype ref", "deftype ref", "main mode", "main functor",
                        "nodes NodeName[attr='value'] where has Child[attr='value']",
                        "nodes ... where has Node spelling 'value' (shortcut dla Node[spelling='value'])",
                        "nodes ... where spelling 'value' (shortcut dla has *[spelling='value'])",
                        "nodes ... where not has Node[...] / has not Node[...] / not spelling 'value'",
                        "nodes NodeName where redefine true|false|both",
                        "filter('key=value' or 'text') - wildcard: * oraz _, literal: \\* oraz \\_ (escape: \\)",
                        "grep('regex')", "reverse", "invert",
                        "whereeq(op,n)", "wherege(op,n)", "wherele(op,n)", "wheregt(op,n)", "wherelt(op,n)"
                ),
                "supportedNodeNames", readInterfaceStringConstants(ESXElementName.class),
                "supportedAttributeNames", readInterfaceStringConstants(ESXAttributeName.class)
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

    public record ExecuteQueryRequest(
            String query,
            Integer page,
            Integer size,
            String sortBy,
            String sortDirection,
            String filter
    ) {
    }

    public record WarmupRequest(List<String> queries) {
    }

    private static List<String> readInterfaceStringConstants(Class<?> type) {
        TreeSet<String> values = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Field field : type.getFields()) {
            int modifiers = field.getModifiers();
            if (!Modifier.isPublic(modifiers) || !Modifier.isStatic(modifiers) || !Modifier.isFinal(modifiers)) {
                continue;
            }
            if (!String.class.equals(field.getType())) {
                continue;
            }
            try {
                Object raw = field.get(null);
                if (raw instanceof String value && !value.isBlank()) {
                    values.add(value);
                }
            } catch (IllegalAccessException ignored) {
                // Interface constants should be accessible; ignore defensive fallback.
            }
        }
        return List.copyOf(values);
    }
}
