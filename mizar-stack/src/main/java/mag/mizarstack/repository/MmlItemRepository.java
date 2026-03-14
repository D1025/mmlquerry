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

    @Query(value = """
            select m.id
            from mml_item m
            join constructor c on c.item_id = m.id
            where m.lib_id = :libId
            order by m.component_rank desc nulls last, m.id
            limit 1
            """, nativeQuery = true)
    Optional<UUID> findBestConstructorItemIdByLibId(@Param("libId") String libId);
}
