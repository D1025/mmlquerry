package mag.mizarstack.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Entity representing the 'article' table from mizar_schema.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Article {
    private UUID id;
    private String name;
    private String title;
    private String filePath;
    private String xmlContent;
    private Instant createdAt;
}
