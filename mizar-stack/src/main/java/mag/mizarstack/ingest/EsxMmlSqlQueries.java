package mag.mizarstack.ingest;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class EsxMmlSqlQueries {

    static final String ACQUIRE_TRANSACTION_LOCK = """
            select pg_advisory_xact_lock(:lockKey)
            """;

    static final String INSERT_CONSTRUCTOR = """
            insert into constructor(item_id, constructor_kind, short_name)
            values (:itemId, :constructorKind, cast(:shortName as text))
            on conflict (item_id) do nothing
            """;

    static final String INSERT_NOTATION = """
            insert into notation(item_id, notation_kind)
            values (:itemId, :notationKind)
            on conflict (item_id) do nothing
            """;

    static final String INSERT_STATEMENT = """
            insert into statement(item_id, statement_kind, statement_text)
            values (:itemId, :statementKind, cast(:statementText as text))
            on conflict (item_id) do nothing
            """;

    static final String INSERT_REGISTRATION = """
            insert into registration(item_id, registration_kind)
            values (:itemId, :registrationKind)
            on conflict (item_id) do nothing
            """;

    static final String INSERT_ITEM_CONSTRUCTOR_REF = """
            insert into item_constructor_ref (id, item_id, constructor_item_id, role, is_positive, occurrences, details)
            select :id, :itemId, :constructorItemId, :role, :isPositive, :occurrences, cast(:details as jsonb)
            where exists (
                select 1
                from constructor c
                where c.item_id = :constructorItemId
            )
              and not exists (
                select 1
                from item_constructor_ref icr
                where icr.item_id = :itemId
                  and icr.constructor_item_id = :constructorItemId
                  and icr.role = :role
            )
            """;

    static final String INSERT_NOTATION_FORMAT = """
            insert into notation_format(notation_item_id, format_id)
            values (:notationItemId, :formatId)
            on conflict (notation_item_id, format_id) do nothing
            """;

    static final String INSERT_FORMAT_SYMBOL = """
            insert into format_symbol(format_id, symbol_id, pos)
            values (:formatId, :symbolId, :pos)
            on conflict (format_id, pos) do update
            set symbol_id = excluded.symbol_id
            """;

    static final String INSERT_CONSTRUCTOR_DEFINITION = """
            insert into constructor_definition(constructor_item_id, definition_statement_item_id)
            values (:constructorItemId, :definitionStatementItemId)
            on conflict (constructor_item_id, definition_statement_item_id) do nothing
            """;

    static final String INSERT_CONSTRUCTOR_DEFINIENS = """
            insert into constructor_definiens(definiens_statement_item_id, constructor_item_id)
            values (:definiensStatementItemId, :constructorItemId)
            on conflict (definiens_statement_item_id, constructor_item_id) do nothing
            """;

    static final String INSERT_REGISTRATION_RELATION = """
            insert into registration_relation(id, registration_item_id, constructor_item_id, role, is_positive)
            select :id, :registrationItemId, :constructorItemId, :role, :isPositive
            where exists (
                select 1
                from constructor c
                where c.item_id = :constructorItemId
            )
              and not exists (
                select 1
                from registration_relation rr
                where rr.registration_item_id = :registrationItemId
                  and rr.constructor_item_id = :constructorItemId
                  and rr.role = :role
            )
            """;

    static final String INSERT_ITEM_NODE = """
            insert into item_node(
                id, item_id, parent_node_id, node_path, node_type,
                constructor_item_id, symbol_id, format_id, pos, depth, raw, details
            )
            select
                :id, :itemId, :parentNodeId, :nodePath, :nodeType,
                :constructorItemId,
                case
                    when cast(:symbolId as uuid) is null then null
                    when exists (select 1 from symbol s where s.id = cast(:symbolId as uuid)) then cast(:symbolId as uuid)
                    else null
                end,
                case
                    when cast(:formatId as uuid) is null then null
                    when exists (select 1 from format f where f.id = cast(:formatId as uuid)) then cast(:formatId as uuid)
                    else null
                end,
                :pos, :depth, cast(:raw as text), cast(:details as jsonb)
            on conflict (id) do nothing
            """;

    static final String UPDATE_ITEM_NODE_CONSTRUCTOR = """
            update item_node n
               set constructor_item_id = :constructorItemId
             where n.id = :nodeId
               and exists (
                   select 1
                     from constructor c
                    where c.item_id = :constructorItemId
               )
            """;

    static final String FIND_CONSTRUCTOR_ITEM_IDS_BY_LIB_IDS = """
            select distinct on (mi.lib_id) mi.lib_id, c.item_id
            from constructor c
            join mml_item mi on mi.id = c.item_id
            where mi.lib_id in (:libIds)
            order by mi.lib_id, mi.component_rank desc nulls last, c.item_id
            """;

    static final String INSERT_NOTATION_SYMBOL = """
            insert into notation_symbol(notation_item_id, symbol_id, pos)
            values (:notationItemId, :symbolId, :pos)
            on conflict (notation_item_id, pos) do update
            set symbol_id = excluded.symbol_id
            """;

    static final String INSERT_NOTATION_CONSTRUCTOR = """
            insert into notation_constructor(notation_item_id, constructor_item_id, role)
            select :notationItemId, :constructorItemId, cast(:role as text)
            where exists (
                select 1
                from constructor c
                where c.item_id = :constructorItemId
            )
            on conflict (notation_item_id, constructor_item_id) do nothing
            """;
}
