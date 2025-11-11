package mag.mizarstack.model;

import lombok.*;
import org.dom4j.*;
import mag.mizarstack.xml_names.*;
import mag.mizarstack.util.Errors;

@Setter
@Getter
@ToString

public class Formula extends XMLElement implements SmallExpression {

    public Formula(Element element) {
        super(element);
    }

    public static Formula buildFormula(Element element) {
        return switch (element.getName()) {
            case ESXElementName.BICONDITIONAL_FORMULA -> new BiconditionalFormula(element);
            case ESXElementName.CONDITIONAL_FORMULA -> new ConditionalFormula(element);
            case ESXElementName.CONJUNCTIVE_FORMULA -> new ConjunctiveFormula(element);
            case ESXElementName.CONTRADICTION -> new Contradiction(element);
            case ESXElementName.DISJUNCTIVE_FORMULA -> new DisjunctiveFormula(element);
            case ESXElementName.EXISTENTIAL_QUANTIFIER_FORMULA -> new ExistentialQuantifierFormula(element);
            case ESXElementName.FLEXARYCONJUNCTIVE_FORMULA -> new FlexaryConjunctiveFormula(element);
            case ESXElementName.FLEXARYDISJUNCTIVE_FORMULA -> new FlexaryDisjunctiveFormula(element);
            case ESXElementName.MULTI_ATTRIBUTIVE_FORMULA -> new MultiAttributiveFormula(element);
            case ESXElementName.MULTI_RELATION_FORMULA -> new MultiRelationFormula(element);
            case ESXElementName.NEGATED_FORMULA -> new NegatedFormula(element);
            case ESXElementName.QUALIFYING_FORMULA -> new QualifyingFormula(element);
            case ESXElementName.PRIVATE_PREDICATE_FORMULA -> new PrivatePredicateFormula(element);
            case ESXElementName.RELATION_FORMULA -> new RelationFormula(element);
            case ESXElementName.THESIS -> new Thesis(element);
            case ESXElementName.UNIVERSAL_QUANTIFIER_FORMULA -> new UniversalQuantifierFormula(element);
            default -> {
                Errors.error(element, "Missing Element in buildFormula [" + element.getName() + "]");
                yield null;
            }
        };
    }

    @Override
    public void preProcess() {
        super.preProcess();
    }

    @Override
    public void process() {}

    @Override
    public void postProcess() {
        super.postProcess();
    }
}
