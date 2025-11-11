package mag.mizarstack.model;

import lombok.*;
import org.dom4j.*;
import mag.mizarstack.xml_names.*;

@Setter
@Getter
@ToString

public class PrivateFunctorTerm extends TermWithArguments {

    public PrivateFunctorTerm(Element element) {
        super(element);
    }

    @Override
    public void preProcess() {
        super.preProcess();
    }

    @Override
    public void process() {
        super.process();
    }

    @Override
    public void postProcess() {
        super.postProcess();
    }
}
