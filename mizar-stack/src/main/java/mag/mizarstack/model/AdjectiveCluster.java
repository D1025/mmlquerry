package mag.mizarstack.model;

import java.util.*;
import lombok.*;
import mag.mizarstack.xml_names.*;
import org.dom4j.*;

@Setter
@Getter
@ToString

public class AdjectiveCluster extends XMLElement {

    private List<Attribute> attributes = new LinkedList<>();

    public AdjectiveCluster(Element element) {
        super(element);
        for (Element element1: element.elements(ESXElementName.ATTRIBUTE)) {
            attributes.add(new Attribute(element1));
        }
    }

    @Override
    public void preProcess() {
        super.preProcess();
    }

    @Override
    public void process() {
        for (Attribute attribute: attributes) {
            attribute.run();
        }
    }

    @Override
    public void postProcess() {
        super.postProcess();
    }
}
