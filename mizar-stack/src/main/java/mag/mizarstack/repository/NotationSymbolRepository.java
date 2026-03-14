package mag.mizarstack.repository;

import mag.mizarstack.entity.NotationSymbol;
import mag.mizarstack.entity.id.NotationSymbolId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotationSymbolRepository extends JpaRepository<NotationSymbol, NotationSymbolId> {
}
