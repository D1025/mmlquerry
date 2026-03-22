package mag.mizarstack.repository;

import mag.mizarstack.entity.MmlItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MmlItemRepository extends JpaRepository<MmlItem, UUID> {
    Optional<MmlItem> findByArticleIdAndSubkindAndNumber(UUID articleId, String subkind, Integer number);

    List<MmlItem> findAllByLibIdOrderByIdAsc(String libId);

    @Query(value = """
            select id
            from mml_item
            where article_id = :articleId
              and subkind = :subkind
              and number = :number
            """, nativeQuery = true)
    List<UUID> findIdsByArticleAndSubkindAndNumber(
            @Param("articleId") UUID articleId,
            @Param("subkind") String subkind,
            @Param("number") Integer number
    );
}
