package mag.mizarstack.ingest;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MappedMmlItem {
    String kind;
    String subKind;
    int number;
    String libId;
    String title;
    String textContent;
    String rawXml;
    String shortName;
}
