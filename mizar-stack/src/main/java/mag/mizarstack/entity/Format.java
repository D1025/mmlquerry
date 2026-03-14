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
 * Entity representing the 'format' table from mizar_schema.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "format")
public class Format {
    @Id
    private UUID id;

    private String name;

    private String representation;

    @Column(name = "article_id")
    private UUID articleId;

    private String description;
}
