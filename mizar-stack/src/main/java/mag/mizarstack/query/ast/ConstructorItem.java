package mag.mizarstack.query.ast;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a constructor item (article, kind, number).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConstructorItem {
    private String articleName;
    private String kind;
    private int number;
}

