package mag.mizarstack.search;

import org.dom4j.Document;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.util.*;

@Slf4j
public class XmlAttributeCounter {

    public static long countAttributes(InputStream inputStream) {
        try {
            SAXReader reader = new SAXReader();
            Document doc = reader.read(inputStream);
            return countAttributesInElement(doc.getRootElement());
        } catch (Exception e) {
            log.warn("Failed to count attributes", e);
            return 0;
        }
    }

    private static long countAttributesInElement(Element element) {
        long count = element.attributeCount();

        @SuppressWarnings("unchecked")
        List<Element> children = element.elements();
        for (Element child : children) {
            count += countAttributesInElement(child);
        }

        return count;
    }
}

