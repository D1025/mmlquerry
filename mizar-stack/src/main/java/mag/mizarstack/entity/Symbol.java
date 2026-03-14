package mag.mizarstack.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Entity representing the 'symbol' table from mizar_schema.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "symbol")
public class Symbol {
    @Id
    private UUID id;

    @Column(nullable = false)
    private String text;

    private String normalized;

    private String kind; // vocG, vocK, vocL, vocM, vocO, vocR, vocU, vocV

    @Column(name = "article_id")
    private UUID articleId;

    private String description;
}
