package mag.mizarstack.repository;

import mag.mizarstack.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DocumentRepository extends JpaRepository<Document, Long> {
    Optional<Document> findBySourceUrl(String sourceUrl);
}
