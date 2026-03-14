package mag.mizarstack.repository;

import mag.mizarstack.entity.Format;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface FormatRepository extends JpaRepository<Format, UUID> {
    Optional<Format> findFirstByNameAndArticleId(String name, UUID articleId);
}
