package mag.mizarstack.repository;

import mag.mizarstack.entity.ConstructorRedefinition;
import mag.mizarstack.entity.id.ConstructorRedefinitionId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConstructorRedefinitionRepository extends JpaRepository<ConstructorRedefinition, ConstructorRedefinitionId> {
}
