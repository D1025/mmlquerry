package mag.mizarstack.repository;

import mag.mizarstack.entity.FormatSymbol;
import mag.mizarstack.entity.id.FormatSymbolId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FormatSymbolRepository extends JpaRepository<FormatSymbol, FormatSymbolId> {
}
