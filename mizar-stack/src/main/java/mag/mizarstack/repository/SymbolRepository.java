package mag.mizarstack.repository;

import mag.mizarstack.entity.Symbol;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SymbolRepository extends JpaRepository<Symbol, UUID> {
    Optional<Symbol> findFirstByTextAndArticleId(String text, UUID articleId);
}
