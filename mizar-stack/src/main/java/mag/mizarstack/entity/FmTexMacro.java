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
 * Entity representing the 'fm_tex_macro' table from mizar_schema.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "fm_tex_macro")
public class FmTexMacro {
    @Id
    private UUID id;

    private String name;

    private String expansion;

    @Column(name = "article_id")
    private UUID articleId;
}
