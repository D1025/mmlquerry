package mag.mizarstack.repository;

import mag.mizarstack.entity.RegistrationRelation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RegistrationRelationRepository extends JpaRepository<RegistrationRelation, UUID> {
    boolean existsByRegistrationItemIdAndConstructorItemIdAndRole(UUID registrationItemId, UUID constructorItemId, String role);
}
