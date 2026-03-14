package mag.mizarstack.repository;

import mag.mizarstack.entity.Constructor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ConstructorRepository extends JpaRepository<Constructor, UUID> {
}
