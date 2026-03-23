package mag.mizarstack.ingest.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MappedMmlItem {
    public String kind;
    public String subKind;
    public int number;
    public String libId;
    public String title;
    public String textContent;
    public String rawXml;
    public String shortName;
}


