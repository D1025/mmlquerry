package mag.mizarstack.repository;

import mag.mizarstack.entity.FmKeyword;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface FmKeywordRepository extends JpaRepository<FmKeyword, UUID> {
}
