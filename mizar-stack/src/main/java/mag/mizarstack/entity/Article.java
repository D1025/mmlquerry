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
 * Entity representing the 'article' table from mizar_schema.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "article")
public class Article {
    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    private String title;

    @Column(name = "file_path")
    private String filePath;

    @Column(name = "xml_content")
    private String xmlContent;

    @Column(name = "created_at")
    private Instant createdAt;
}
