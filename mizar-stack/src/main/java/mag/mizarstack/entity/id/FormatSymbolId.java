package mag.mizarstack.entity.id;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FormatSymbolId implements Serializable {
    private UUID formatId;
    private Integer pos;
}
