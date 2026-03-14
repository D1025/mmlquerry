package mag.mizarstack.repository;

import mag.mizarstack.entity.Article;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ArticleRepository extends JpaRepository<Article, UUID> {
    Optional<Article> findByName(String name);
}
