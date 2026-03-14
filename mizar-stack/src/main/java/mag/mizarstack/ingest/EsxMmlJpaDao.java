package mag.mizarstack.ingest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import mag.mizarstack.entity.Article;
import mag.mizarstack.entity.Constructor;
import mag.mizarstack.entity.ConstructorDefiniens;
import mag.mizarstack.entity.ConstructorDefinition;
import mag.mizarstack.entity.Format;
import mag.mizarstack.entity.FormatSymbol;
import mag.mizarstack.entity.ItemConstructorRef;
import mag.mizarstack.entity.MmlItem;
import mag.mizarstack.entity.Notation;
import mag.mizarstack.entity.NotationConstructor;
import mag.mizarstack.entity.NotationFormat;
import mag.mizarstack.entity.NotationSymbol;
import mag.mizarstack.entity.Registration;
import mag.mizarstack.entity.RegistrationRelation;
import mag.mizarstack.entity.Statement;
import mag.mizarstack.entity.Symbol;
import mag.mizarstack.entity.id.ConstructorDefiniensId;
import mag.mizarstack.entity.id.ConstructorDefinitionId;
import mag.mizarstack.entity.id.NotationConstructorId;
import mag.mizarstack.entity.id.NotationFormatId;
import mag.mizarstack.repository.ArticleRepository;
import mag.mizarstack.repository.ConstructorDefiniensRepository;
import mag.mizarstack.repository.ConstructorDefinitionRepository;
import mag.mizarstack.repository.ConstructorRepository;
import mag.mizarstack.repository.FormatRepository;
import mag.mizarstack.repository.FormatSymbolRepository;
import mag.mizarstack.repository.ItemConstructorRefRepository;
import mag.mizarstack.repository.MmlItemRepository;
import mag.mizarstack.repository.NotationConstructorRepository;
import mag.mizarstack.repository.NotationFormatRepository;
import mag.mizarstack.repository.NotationRepository;
import mag.mizarstack.repository.NotationSymbolRepository;
import mag.mizarstack.repository.RegistrationRelationRepository;
import mag.mizarstack.repository.RegistrationRepository;
import mag.mizarstack.repository.StatementRepository;
import mag.mizarstack.repository.SymbolRepository;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class EsxMmlJpaDao {

    private final ArticleRepository articleRepository;
    private final MmlItemRepository mmlItemRepository;
    private final ConstructorRepository constructorRepository;
    private final NotationRepository notationRepository;
    private final StatementRepository statementRepository;
    private final RegistrationRepository registrationRepository;
    private final ItemConstructorRefRepository itemConstructorRefRepository;
    private final NotationFormatRepository notationFormatRepository;
    private final FormatSymbolRepository formatSymbolRepository;
    private final ConstructorDefinitionRepository constructorDefinitionRepository;
    private final ConstructorDefiniensRepository constructorDefiniensRepository;
    private final RegistrationRelationRepository registrationRelationRepository;
    private final SymbolRepository symbolRepository;
    private final NotationSymbolRepository notationSymbolRepository;
    private final NotationConstructorRepository notationConstructorRepository;
    private final FormatRepository formatRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public UUID upsertArticle(String articleName, String s3Key, String xmlContent) {
        Article article = articleRepository.findByName(articleName)
                .orElseGet(() -> Article.builder()
                        .id(UUID.randomUUID())
                        .name(articleName)
                        .createdAt(Instant.now())
                        .build());

        article.setFilePath(s3Key);
        article.setXmlContent(xmlContent);
        if (article.getCreatedAt() == null) {
            article.setCreatedAt(Instant.now());
        }

        return articleRepository.save(article).getId();
    }

    @Transactional
    public UUID upsertMmlItem(UUID articleId, MappedMmlItem it) {
        if (articleId == null || it == null) {
            return null;
        }

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
                        return canonical.get(0);
                    }
                }

                return existingItem.getId();
            }
        }

        Optional<MmlItem> canonicalCandidate = findCanonicalItem(articleId, it.subKind, it.number);
        if (canonicalCandidate.isPresent()) {
            MmlItem canonicalItem = canonicalCandidate.get();
            applyItemPayload(canonicalItem, articleId, it, true);
            return mmlItemRepository.save(canonicalItem).getId();
        }

        return insertOrUpdateCanonical(articleId, it);
    }

    private UUID insertOrUpdateCanonical(UUID articleId, MappedMmlItem it) {
        Optional<MmlItem> canonicalCandidate = findCanonicalItem(articleId, it.subKind, it.number);
        if (canonicalCandidate.isPresent()) {
            MmlItem canonicalItem = canonicalCandidate.get();
            applyItemPayload(canonicalItem, articleId, it, true);
            return mmlItemRepository.save(canonicalItem).getId();
        }

        MmlItem item = new MmlItem();
        item.setId(UUID.randomUUID());
        item.setCreatedAt(Instant.now());
        applyItemPayload(item, articleId, it, true);
        return mmlItemRepository.save(item).getId();
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
    public void insertConstructor(UUID itemId, String constructorKind, String shortName) {
        if (itemId == null || constructorKind == null || constructorKind.isBlank()) {
            return;
        }
        if (constructorRepository.existsById(itemId)) {
            return;
        }
        Constructor constructor = new Constructor();
        constructor.setItemId(itemId);
        constructor.setConstructorKind(constructorKind);
        constructor.setShortName(shortName);
        constructor.setCreatedAt(Instant.now());
        constructorRepository.save(constructor);
    }

    @Transactional
    public void insertNotation(UUID itemId, String notationKind) {
        if (itemId == null || notationKind == null || notationKind.isBlank()) {
            return;
        }
        if (notationRepository.existsById(itemId)) {
            return;
        }
        Notation notation = new Notation();
        notation.setItemId(itemId);
        notation.setNotationKind(notationKind);
        notationRepository.save(notation);
    }

    @Transactional
    public void insertStatement(UUID itemId, String statementKind, String text) {
        if (itemId == null) {
            return;
        }
        String kind = (statementKind == null || statementKind.isBlank()) ? "th" : statementKind;
        if (statementRepository.existsById(itemId)) {
            return;
        }
        Statement statement = new Statement();
        statement.setItemId(itemId);
        statement.setStatementKind(kind);
        statement.setStatementText(text);
        statementRepository.save(statement);
    }

    @Transactional
    public void insertRegistration(UUID itemId, String registrationKind) {
        if (itemId == null) {
            return;
        }
        String kind = (registrationKind == null || registrationKind.isBlank()) ? "condreg" : registrationKind;
        if (registrationRepository.existsById(itemId)) {
            return;
        }
        Registration registration = new Registration();
        registration.setItemId(itemId);
        registration.setRegistrationKind(kind);
        registrationRepository.save(registration);
    }

    @Transactional
    public void insertItemConstructorRef(UUID itemId, UUID constructorItemId, String role, boolean isPositive, int occurrences, Map<String, Object> details) {
        if (itemId == null || constructorItemId == null || role == null || role.isBlank()) {
            return;
        }
        if (!constructorExists(constructorItemId)) {
            return;
        }
        if (itemConstructorRefRepository.existsByItemIdAndConstructorItemIdAndRole(itemId, constructorItemId, role)) {
            return;
        }

        ItemConstructorRef ref = new ItemConstructorRef();
        ref.setId(UUID.randomUUID());
        ref.setItemId(itemId);
        ref.setConstructorItemId(constructorItemId);
        ref.setRole(role);
        ref.setIsPositive(isPositive);
        ref.setOccurrences(occurrences);
        ref.setDetails(serializeDetails(details));
        ref.setCreatedAt(Instant.now());
        itemConstructorRefRepository.save(ref);
    }

    @Transactional
    public void insertNotationFormat(UUID notationItemId, UUID formatId) {
        if (notationItemId == null || formatId == null) {
            return;
        }
        NotationFormatId id = new NotationFormatId(notationItemId, formatId);
        if (notationFormatRepository.existsById(id)) {
            return;
        }
        NotationFormat relation = new NotationFormat();
        relation.setNotationItemId(notationItemId);
        relation.setFormatId(formatId);
        notationFormatRepository.save(relation);
    }

    @Transactional
    public void insertFormatSymbol(UUID formatId, UUID symbolId, int pos) {
        if (formatId == null || symbolId == null) {
            return;
        }
        FormatSymbol relation = new FormatSymbol();
        relation.setFormatId(formatId);
        relation.setSymbolId(symbolId);
        relation.setPos(pos);
        formatSymbolRepository.save(relation);
    }

    @Transactional
    public void insertConstructorDefinition(UUID constructorItemId, UUID definitionStatementItemId) {
        if (constructorItemId == null || definitionStatementItemId == null) {
            return;
        }
        ConstructorDefinitionId id = new ConstructorDefinitionId(constructorItemId, definitionStatementItemId);
        if (constructorDefinitionRepository.existsById(id)) {
            return;
        }
        ConstructorDefinition relation = new ConstructorDefinition();
        relation.setConstructorItemId(constructorItemId);
        relation.setDefinitionStatementItemId(definitionStatementItemId);
        constructorDefinitionRepository.save(relation);
    }

    @Transactional
    public void insertConstructorDefiniens(UUID definiensStatementItemId, UUID constructorItemId) {
        if (constructorItemId == null || definiensStatementItemId == null) {
            return;
        }
        ConstructorDefiniensId id = new ConstructorDefiniensId(definiensStatementItemId, constructorItemId);
        if (constructorDefiniensRepository.existsById(id)) {
            return;
        }
        ConstructorDefiniens relation = new ConstructorDefiniens();
        relation.setDefiniensStatementItemId(definiensStatementItemId);
        relation.setConstructorItemId(constructorItemId);
        constructorDefiniensRepository.save(relation);
    }

    @Transactional
    public void insertRegistrationRelation(UUID registrationItemId, UUID constructorItemId, String role, boolean isPositive) {
        if (registrationItemId == null || constructorItemId == null || role == null || role.isBlank()) {
            return;
        }
        if (!constructorExists(constructorItemId)) {
            return;
        }
        if (registrationRelationRepository.existsByRegistrationItemIdAndConstructorItemIdAndRole(registrationItemId, constructorItemId, role)) {
            return;
        }
        RegistrationRelation relation = new RegistrationRelation();
        relation.setId(UUID.randomUUID());
        relation.setRegistrationItemId(registrationItemId);
        relation.setConstructorItemId(constructorItemId);
        relation.setRole(role);
        relation.setIsPositive(isPositive);
        registrationRelationRepository.save(relation);
    }

    public Optional<UUID> findConstructorItemIdByLibId(String libId) {
        if (libId == null || libId.isBlank()) {
            return Optional.empty();
        }
        return mmlItemRepository.findBestConstructorItemIdByLibId(libId);
    }

    public boolean constructorExists(UUID constructorItemId) {
        return constructorItemId != null && constructorRepository.existsById(constructorItemId);
    }

    @Transactional
    public UUID insertSymbolIfAbsent(UUID articleId, String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return symbolRepository.findFirstByTextAndArticleId(text, articleId)
                .map(Symbol::getId)
                .orElseGet(() -> {
                    Symbol symbol = new Symbol();
                    symbol.setId(UUID.randomUUID());
                    symbol.setText(text);
                    symbol.setNormalized(text.trim());
                    symbol.setArticleId(articleId);
                    return symbolRepository.save(symbol).getId();
                });
    }

    @Transactional
    public void insertNotationSymbol(UUID notationItemId, UUID symbolId, int pos) {
        if (notationItemId == null || symbolId == null) {
            return;
        }
        NotationSymbol relation = new NotationSymbol();
        relation.setNotationItemId(notationItemId);
        relation.setSymbolId(symbolId);
        relation.setPos(pos);
        notationSymbolRepository.save(relation);
    }

    @Transactional
    public void insertNotationConstructor(UUID notationItemId, UUID constructorItemId, String role) {
        if (notationItemId == null || constructorItemId == null) {
            return;
        }
        NotationConstructorId id = new NotationConstructorId(notationItemId, constructorItemId);
        if (notationConstructorRepository.existsById(id)) {
            return;
        }
        NotationConstructor relation = new NotationConstructor();
        relation.setNotationItemId(notationItemId);
        relation.setConstructorItemId(constructorItemId);
        relation.setRole(role);
        notationConstructorRepository.save(relation);
    }

    @Transactional
    public UUID upsertFormat(UUID articleId, String name, String representation) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return formatRepository.findFirstByNameAndArticleId(name, articleId)
                .map(Format::getId)
                .orElseGet(() -> {
                    Format format = new Format();
                    format.setId(UUID.randomUUID());
                    format.setName(name);
                    format.setRepresentation(representation);
                    format.setArticleId(articleId);
                    return formatRepository.save(format).getId();
                });
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
