package mag.mizarstack.model;

import lombok.*;
import mag.mizarstack.xml_names.ESXElementName;
import org.dom4j.*;

@Setter
@Getter
@ToString

public class Reduction extends Item {

    private Redex redex;
    private Reduct reduct;

    public Reduction(Element element) {
        super(element);
        redex = new Redex(element.element(ESXElementName.REDEX));
        reduct = new Reduct(element.element(ESXElementName.REDUCT));
    }

    @Override
    public void preProcess() {
        super.preProcess();
    }

    @Override
    public void process() {
        redex.run();
        reduct.run();
    }

    @Override
    public void postProcess() {
        super.postProcess();
    }
}
