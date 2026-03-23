package mag.mizarstack.ingest.stats;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public class FileInsertStats {

    public static final String ARTICLE = "article";
    public static final String MML_ITEM = "mml_item";
    public static final String CONSTRUCTOR = "constructor";
    public static final String NOTATION = "notation";
    public static final String STATEMENT = "statement";
    public static final String REGISTRATION = "registration";
    public static final String SYMBOL = "symbol";
    public static final String FORMAT = "format";
    public static final String ITEM_CONSTRUCTOR_REF = "item_constructor_ref";
    public static final String NOTATION_SYMBOL = "notation_symbol";
    public static final String NOTATION_CONSTRUCTOR = "notation_constructor";
    public static final String NOTATION_FORMAT = "notation_format";
    public static final String FORMAT_SYMBOL = "format_symbol";
    public static final String CONSTRUCTOR_DEFINITION = "constructor_definition";
    public static final String CONSTRUCTOR_DEFINIENS = "constructor_definiens";
    public static final String REGISTRATION_RELATION = "registration_relation";
    public static final String ITEM_NODE = "item_node";
    public static final String ITEM_NODE_WITH_CONSTRUCTOR = "item_node.constructor_item_id";
    public static final String ITEM_NODE_WITH_SYMBOL = "item_node.symbol_id";
    public static final String ITEM_NODE_WITH_FORMAT = "item_node.format_id";

    private static final List<String> ORDERED_TYPES = List.of(
            ARTICLE,
            MML_ITEM,
            CONSTRUCTOR,
            NOTATION,
            STATEMENT,
            REGISTRATION,
            SYMBOL,
            FORMAT,
            ITEM_CONSTRUCTOR_REF,
            NOTATION_SYMBOL,
            NOTATION_CONSTRUCTOR,
            NOTATION_FORMAT,
            FORMAT_SYMBOL,
            CONSTRUCTOR_DEFINITION,
            CONSTRUCTOR_DEFINIENS,
            REGISTRATION_RELATION,
            ITEM_NODE,
            ITEM_NODE_WITH_CONSTRUCTOR,
            ITEM_NODE_WITH_SYMBOL,
            ITEM_NODE_WITH_FORMAT
    );

    private final Map<String, LongAdder> inserted = new ConcurrentHashMap<>();

    public void add(String type, long delta) {
        if (type == null || type.isBlank() || delta <= 0) {
            return;
        }
        inserted.computeIfAbsent(type, ignored -> new LongAdder()).add(delta);
    }

    public Map<String, Long> snapshotOrdered() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String type : ORDERED_TYPES) {
            out.put(type, value(type));
        }

        List<String> extras = new ArrayList<>(inserted.keySet());
        extras.removeAll(ORDERED_TYPES);
        extras.sort(String::compareTo);
        for (String extra : extras) {
            out.put(extra, value(extra));
        }

        return out;
    }

    private long value(String type) {
        LongAdder adder = inserted.get(type);
        return adder == null ? 0L : adder.sum();
    }
}


