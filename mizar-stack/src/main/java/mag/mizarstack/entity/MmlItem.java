package mag.mizarstack.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Entity representing the 'mml_item' table from mizar_schema.
 * Generic library item (constructor, notation, statement, registration).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "mml_item")
public class MmlItem {
    @Id
    private UUID id;

    @Column(name = "article_id", nullable = false)
    private UUID articleId;

    @Column(nullable = false)
    private String kind; // constructor, notation, statement, registration

    @Column(name = "subkind")
    private String subkind; // aggr, func, funcnot, th, def, exreg, etc.

    private Integer number;

    @Column(name = "lib_id")
    private String libId; // e.g., XBOOLE_0:func 1

    private String title;

    @Column(name = "text_content")
    private String textContent;

    @Column(name = "raw_xml")
    private String rawXml;

    @Column(name = "component_rank")
    private Integer componentRank;

    @Column(name = "created_at")
    private Instant createdAt;
}
