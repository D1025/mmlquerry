package mag.mizarstack.repository;

import mag.mizarstack.entity.Notation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface NotationRepository extends JpaRepository<Notation, UUID> {
}
