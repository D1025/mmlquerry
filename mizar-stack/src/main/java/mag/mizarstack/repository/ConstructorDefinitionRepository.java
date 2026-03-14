package mag.mizarstack.repository;

import mag.mizarstack.entity.ConstructorDefinition;
import mag.mizarstack.entity.id.ConstructorDefinitionId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConstructorDefinitionRepository extends JpaRepository<ConstructorDefinition, ConstructorDefinitionId> {
}
