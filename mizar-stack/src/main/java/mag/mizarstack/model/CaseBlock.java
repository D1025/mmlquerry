package mag.mizarstack.model;

import lombok.*;
import org.dom4j.*;
import mag.mizarstack.xml_names.*;

@Setter
@Getter
@ToString

public class CaseBlock extends Item {

    private Block block;

    public CaseBlock(Element element) {
        super(element);
        block = Block.buildBlock(element.element(ESXElementName.BLOCK));
    }

    @Override
    public void preProcess() {
        super.preProcess();
    }

    @Override
    public void process() {
        block.run();
    }

    @Override
    public void postProcess() {
        super.postProcess();
    }
}
