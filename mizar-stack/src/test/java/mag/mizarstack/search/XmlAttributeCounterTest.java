package mag.mizarstack.search;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class XmlAttributeCounterTest {

    @Test
    void countsVariousAttributeDefinitionTags() throws Exception {
        String xml = """
                <Root>
                  <Attribute-Definition id='1'/>
                  <AttributeDefinition id='2'> <Inner/> </AttributeDefinition>
                  <Attribute_Definition id='3'>text</Attribute_Definition>
                  <Other/>
                </Root>
                """;
        long c = XmlAttributeCounter.countAttributes(new ByteArrayInputStream(xml.getBytes()));
        assertThat(c).isEqualTo(3);
    }
}

