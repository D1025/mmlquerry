package mag.mizarstack.ingest.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import mag.mizarstack.entity.Article;
import mag.mizarstack.entity.Format;
import mag.mizarstack.entity.MmlItem;
import mag.mizarstack.entity.Symbol;
import mag.mizarstack.ingest.dto.MappedMmlItem;
import mag.mizarstack.repository.ArticleRepository;
import mag.mizarstack.repository.ConstructorRepository;
import mag.mizarstack.repository.FormatRepository;
import mag.mizarstack.repository.MmlItemRepository;
import mag.mizarstack.repository.NotationRepository;
import mag.mizarstack.repository.RegistrationRepository;
import mag.mizarstack.repository.StatementRepository;
import mag.mizarstack.repository.SymbolRepository;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static mag.mizarstack.ingest.persistence.EsxMmlSqlQueries.*;

@Repository
@RequiredArgsConstructor
public class EsxMmlJpaDao {

    private final ArticleRepository articleRepository;
    private final MmlItemRepository mmlItemRepository;
    private final ConstructorRepository constructorRepository;
    private final NotationRepository notationRepository;
    private final StatementRepository statementRepository;
    private final RegistrationRepository registrationRepository;
    private final SymbolRepository symbolRepository;
    private final FormatRepository formatRepository;
    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;

    @Transactional
    public UpsertResult upsertArticle(String articleName, String s3Key, String xmlContent) {
        acquireTransactionLock("article:" + safeLockToken(articleName));
        Optional<Article> existing = articleRepository.findByName(articleName);
        boolean inserted = existing.isEmpty();
        Article article = existing.orElseGet(() -> Article.builder()
                .id(UUID.randomUUID())
                .name(articleName)
                .createdAt(Instant.now())
                .build());

        article.setFilePath(s3Key);
        article.setXmlContent(xmlContent);
        if (article.getCreatedAt() == null) {
            article.setCreatedAt(Instant.now());
        }

        return new UpsertResult(articleRepository.save(article).getId(), inserted);
    }

    @Transactional
    public UpsertResult upsertMmlItem(UUID articleId, MappedMmlItem it) {
        if (articleId == null || it == null) {
            return new UpsertResult(null, false);
        }

        List<String> lockKeys = buildItemUpsertLockKeys(articleId, it);
        acquireTransactionItemLocks(lockKeys);
        return upsertMmlItemLocked(articleId, it);
    }

    private UpsertResult upsertMmlItemLocked(UUID articleId, MappedMmlItem it) {

        if (it.libId != null && !it.libId.isBlank()) {
            List<MmlItem> existingByLib = mmlItemRepository.findAllByLibIdOrderByIdAsc(it.libId);
            if (!existingByLib.isEmpty()) {
                MmlItem existingItem = existingByLib.get(0);
                boolean kindMismatch = existingItem.getKind() != null
                        && it.kind != null
                        && !existingItem.getKind().equals(it.kind);

                if (kindMismatch && !"constructor".equals(it.kind)) {
                    it.libId = disambiguateLibId(it.libId, it.kind, it.subKind, it.number);
                    return insertOrUpdateCanonical(articleId, it);
                }

                if (kindMismatch && "constructor".equals(it.kind)) {
                    deleteSpecializationsExcept(existingItem.getId(), "constructor");
                }

                existingItem.setKind(it.kind);
                existingItem.setTitle(it.title);
                existingItem.setTextContent(it.textContent);
                existingItem.setRawXml(it.rawXml);
                mmlItemRepository.save(existingItem);

                if (it.subKind != null && !it.subKind.isBlank() && it.number > 0) {
                    List<UUID> canonical = mmlItemRepository.findIdsByArticleAndSubkindAndNumber(articleId, it.subKind, it.number);
                    if (!canonical.isEmpty()) {
                        return new UpsertResult(canonical.get(0), false);
                    }
                }

                return new UpsertResult(existingItem.getId(), false);
            }
        }

        Optional<MmlItem> canonicalCandidate = findCanonicalItem(articleId, it.subKind, it.number);
        if (canonicalCandidate.isPresent()) {
            MmlItem canonicalItem = canonicalCandidate.get();
            applyItemPayload(canonicalItem, articleId, it, true);
            return new UpsertResult(mmlItemRepository.save(canonicalItem).getId(), false);
        }

        return insertOrUpdateCanonical(articleId, it);
    }

    private List<String> buildItemUpsertLockKeys(UUID articleId, MappedMmlItem it) {
        List<String> keys = new ArrayList<>(2);
        if (it.libId != null && !it.libId.isBlank()) {
            keys.add("lib:" + it.libId);
        }
        if (it.subKind != null && !it.subKind.isBlank() && it.number > 0) {
            keys.add("canon:" + articleId + ":" + it.subKind + ":" + it.number);
        }
        if (keys.isEmpty()) {
            // Stable fallback keeps lock effective for repeated attempts of the same shape.
            keys.add("fallback:" + articleId + ":" + safeLockToken(it.kind) + ":" + safeLockToken(it.subKind) + ":" + it.number);
        }
        keys.sort(String::compareTo);
        return keys;
    }

    private void acquireTransactionItemLocks(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        for (String key : keys) {
            acquireTransactionLock(key);
        }
    }

    private void acquireTransactionLock(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        entityManager.createNativeQuery(ACQUIRE_TRANSACTION_LOCK)
                .setParameter("lockKey", advisoryLockKey(key))
                .getSingleResult();
    }

    private static String safeLockToken(String value) {
        return (value == null || value.isBlank()) ? "_" : value;
    }

    private static long advisoryLockKey(String key) {
        byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
        long hash = 0xcbf29ce484222325L; // FNV-1a 64-bit offset basis
        for (byte b : bytes) {
            hash ^= (b & 0xffL);
            hash *= 0x100000001b3L; // FNV prime
        }
        return hash;
    }

    private UpsertResult insertOrUpdateCanonical(UUID articleId, MappedMmlItem it) {
        Optional<MmlItem> canonicalCandidate = findCanonicalItem(articleId, it.subKind, it.number);
        if (canonicalCandidate.isPresent()) {
            MmlItem canonicalItem = canonicalCandidate.get();
            applyItemPayload(canonicalItem, articleId, it, true);
            return new UpsertResult(mmlItemRepository.save(canonicalItem).getId(), false);
        }

        MmlItem item = new MmlItem();
        item.setId(UUID.randomUUID());
        item.setCreatedAt(Instant.now());
        applyItemPayload(item, articleId, it, true);
        return new UpsertResult(mmlItemRepository.save(item).getId(), true);
    }

    private Optional<MmlItem> findCanonicalItem(UUID articleId, String subKind, int number) {
        if (subKind == null || subKind.isBlank()) {
            return Optional.empty();
        }
        return mmlItemRepository.findByArticleIdAndSubkindAndNumber(articleId, subKind, number);
    }

    private void applyItemPayload(MmlItem target, UUID articleId, MappedMmlItem source, boolean includeLibId) {
        target.setArticleId(articleId);
        target.setKind(source.kind);
        target.setSubkind(source.subKind);
        target.setNumber(source.number);
        if (includeLibId) {
            target.setLibId(source.libId);
        }
        target.setTitle(source.title);
        target.setTextContent(source.textContent);
        target.setRawXml(source.rawXml);
    }

    private static String disambiguateLibId(String libId, String kind, String subKind, int number) {
        String base = (libId == null || libId.isBlank()) ? "item" : libId;
        String kindPart = (kind == null || kind.isBlank()) ? "item" : kind;
        String subPart = (subKind == null || subKind.isBlank()) ? "unk" : subKind;
        String nrPart = (number > 0) ? Integer.toString(number) : "0";
        return base + "#as:" + kindPart + ":" + subPart + ":" + nrPart;
    }

    @Transactional
    public void deleteSpecializationsExcept(UUID itemId, String keepKind) {
        if (itemId == null || keepKind == null) {
            return;
        }

        if (!"constructor".equals(keepKind)) {
            safeDelete(() -> constructorRepository.deleteById(itemId));
        }
        if (!"notation".equals(keepKind)) {
            safeDelete(() -> notationRepository.deleteById(itemId));
        }
        if (!"statement".equals(keepKind)) {
            safeDelete(() -> statementRepository.deleteById(itemId));
        }
        if (!"registration".equals(keepKind)) {
            safeDelete(() -> registrationRepository.deleteById(itemId));
        }
    }

    @Transactional
    public int insertConstructor(UUID itemId, String constructorKind, String shortName) {
        if (itemId == null || constructorKind == null || constructorKind.isBlank()) {
            return 0;
        }
        return executeNativeUpdate(INSERT_CONSTRUCTOR, params(
                "itemId", itemId,
                "constructorKind", constructorKind,
                "shortName", shortName
        ));
    }

    @Transactional
    public int insertNotation(UUID itemId, String notationKind) {
        if (itemId == null || notationKind == null || notationKind.isBlank()) {
            return 0;
        }
        return executeNativeUpdate(INSERT_NOTATION, params(
                "itemId", itemId,
                "notationKind", notationKind
        ));
    }

    @Transactional
    public int insertStatement(UUID itemId, String statementKind, String text) {
        if (itemId == null) {
            return 0;
        }
        String kind = (statementKind == null || statementKind.isBlank()) ? "th" : statementKind;
        return executeNativeUpdate(INSERT_STATEMENT, params(
                "itemId", itemId,
                "statementKind", kind,
                "statementText", text
        ));
    }

    @Transactional
    public int insertRegistration(UUID itemId, String registrationKind) {
        if (itemId == null) {
            return 0;
        }
        String kind = (registrationKind == null || registrationKind.isBlank()) ? "condreg" : registrationKind;
        return executeNativeUpdate(INSERT_REGISTRATION, params(
                "itemId", itemId,
                "registrationKind", kind
        ));
    }

    @Transactional
    public int insertItemConstructorRef(UUID itemId, UUID constructorItemId, String role, boolean isPositive, int occurrences, Map<String, Object> details) {
        if (itemId == null || constructorItemId == null || role == null || role.isBlank()) {
            return 0;
        }
        return executeNativeUpdate(INSERT_ITEM_CONSTRUCTOR_REF, params(
                "id", UUID.randomUUID(),
                "itemId", itemId,
                "constructorItemId", constructorItemId,
                "role", role,
                "isPositive", isPositive,
                "occurrences", occurrences,
                "details", serializeDetails(details)
        ));
    }

    @Transactional
    public int insertNotationFormat(UUID notationItemId, UUID formatId) {
        if (notationItemId == null || formatId == null) {
            return 0;
        }
        return executeNativeUpdate(INSERT_NOTATION_FORMAT, params(
                "notationItemId", notationItemId,
                "formatId", formatId
        ));
    }

    @Transactional
    public int insertFormatSymbol(UUID formatId, UUID symbolId, int pos) {
        if (formatId == null || symbolId == null) {
            return 0;
        }
        return executeNativeUpdate(INSERT_FORMAT_SYMBOL, params(
                "formatId", formatId,
                "symbolId", symbolId,
                "pos", pos
        ));
    }

    @Transactional
    public int insertConstructorDefinition(UUID constructorItemId, UUID definitionStatementItemId) {
        if (constructorItemId == null || definitionStatementItemId == null) {
            return 0;
        }
        return executeNativeUpdate(INSERT_CONSTRUCTOR_DEFINITION, params(
                "constructorItemId", constructorItemId,
                "definitionStatementItemId", definitionStatementItemId
        ));
    }

    @Transactional
    public int insertConstructorDefiniens(UUID definiensStatementItemId, UUID constructorItemId) {
        if (constructorItemId == null || definiensStatementItemId == null) {
            return 0;
        }
        return executeNativeUpdate(INSERT_CONSTRUCTOR_DEFINIENS, params(
                "definiensStatementItemId", definiensStatementItemId,
                "constructorItemId", constructorItemId
        ));
    }

    @Transactional
    public int insertRegistrationRelation(UUID registrationItemId, UUID constructorItemId, String role, boolean isPositive) {
        if (registrationItemId == null || constructorItemId == null || role == null || role.isBlank()) {
            return 0;
        }
        return executeNativeUpdate(INSERT_REGISTRATION_RELATION, params(
                "id", UUID.randomUUID(),
                "registrationItemId", registrationItemId,
                "constructorItemId", constructorItemId,
                "role", role,
                "isPositive", isPositive
        ));
    }

    @Transactional
    public int insertItemNode(
            UUID nodeId,
            UUID itemId,
            UUID parentNodeId,
            String nodePath,
            String nodeType,
            UUID constructorItemId,
            UUID symbolId,
            UUID formatId,
            int pos,
            int depth,
            String raw,
            Map<String, Object> details
    ) {
        if (nodeId == null || itemId == null || nodePath == null || nodePath.isBlank() || nodeType == null || nodeType.isBlank()) {
            return 0;
        }
        return executeNativeUpdate(INSERT_ITEM_NODE, params(
                "id", nodeId,
                "itemId", itemId,
                "parentNodeId", parentNodeId,
                "nodePath", nodePath,
                "nodeType", nodeType,
                "constructorItemId", constructorItemId,
                "symbolId", symbolId,
                "formatId", formatId,
                "pos", pos,
                "depth", depth,
                "raw", raw,
                "details", serializeDetails(details)
        ));
    }

    @Transactional
    public int updateItemNodeConstructor(UUID nodeId, UUID constructorItemId) {
        if (nodeId == null || constructorItemId == null) {
            return 0;
        }
        return executeNativeUpdate(UPDATE_ITEM_NODE_CONSTRUCTOR, params(
                "nodeId", nodeId,
                "constructorItemId", constructorItemId
        ));
    }

    public Map<String, UUID> findConstructorItemIdsByLibIds(Set<String> libIds) {
        if (libIds == null || libIds.isEmpty()) {
            return Map.of();
        }
        Map<String, UUID> result = new HashMap<>();
        List<String> allLibIds = new ArrayList<>(libIds);
        final int chunkSize = 2000;

        for (int from = 0; from < allLibIds.size(); from += chunkSize) {
            int to = Math.min(allLibIds.size(), from + chunkSize);
            Set<String> chunk = new LinkedHashSet<>(allLibIds.subList(from, to));

            @SuppressWarnings("unchecked")
            List<Object[]> rows = entityManager.createNativeQuery(FIND_CONSTRUCTOR_ITEM_IDS_BY_LIB_IDS)
                    .setParameter("libIds", chunk)
                    .getResultList();

            for (Object[] row : rows) {
                if (row == null || row.length < 2 || row[0] == null || row[1] == null) continue;
                String libId = row[0].toString();
                UUID constructorItemId = (row[1] instanceof UUID uuid) ? uuid : UUID.fromString(row[1].toString());
                result.put(libId, constructorItemId);
            }
        }
        return result;
    }

    @Transactional
    public UpsertResult insertSymbolIfAbsent(UUID articleId, String text) {
        if (text == null || text.isBlank()) {
            return new UpsertResult(null, false);
        }
        return symbolRepository.findFirstByTextAndArticleId(text, articleId)
                .map(existing -> new UpsertResult(existing.getId(), false))
                .orElseGet(() -> {
                    Symbol symbol = new Symbol();
                    symbol.setId(UUID.randomUUID());
                    symbol.setText(text);
                    symbol.setNormalized(text.trim());
                    symbol.setArticleId(articleId);
                    return new UpsertResult(symbolRepository.saveAndFlush(symbol).getId(), true);
                });
    }

    @Transactional
    public int insertNotationSymbol(UUID notationItemId, UUID symbolId, int pos) {
        if (notationItemId == null || symbolId == null) {
            return 0;
        }
        return executeNativeUpdate(INSERT_NOTATION_SYMBOL, params(
                "notationItemId", notationItemId,
                "symbolId", symbolId,
                "pos", pos
        ));
    }

    @Transactional
    public int insertNotationConstructor(UUID notationItemId, UUID constructorItemId, String role) {
        if (notationItemId == null || constructorItemId == null) {
            return 0;
        }
        return executeNativeUpdate(INSERT_NOTATION_CONSTRUCTOR, params(
                "notationItemId", notationItemId,
                "constructorItemId", constructorItemId,
                "role", role
        ));
    }

    @Transactional
    public UpsertResult upsertFormat(UUID articleId, String name, String representation) {
        if (name == null || name.isBlank()) {
            return new UpsertResult(null, false);
        }
        return formatRepository.findFirstByNameAndArticleId(name, articleId)
                .map(existing -> new UpsertResult(existing.getId(), false))
                .orElseGet(() -> {
                    Format format = new Format();
                    format.setId(UUID.randomUUID());
                    format.setName(name);
                    format.setRepresentation(representation);
                    format.setArticleId(articleId);
                    return new UpsertResult(formatRepository.saveAndFlush(format).getId(), true);
                });
    }

    private int executeNativeUpdate(String sql, Map<String, Object> params) {
        Query query = entityManager.createNativeQuery(sql);
        params.forEach(query::setParameter);
        return query.executeUpdate();
    }

    private Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            String key = (String) kv[i];
            Object value = kv[i + 1];
            m.put(key, value);
        }
        return m;
    }

    private String serializeDetails(Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException ex) {
            return details.toString();
        }
    }

    private void safeDelete(Runnable deletion) {
        try {
            deletion.run();
        } catch (EmptyResultDataAccessException ignored) {
        }
    }
}


