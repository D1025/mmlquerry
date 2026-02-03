package mag.mizarstack.search;

import org.dom4j.Document;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.util.*;

/**
 * Utility class to count XML attributes in a document.
 * Used for analyzing the complexity of XML documents.
 */
@Slf4j
public class XmlAttributeCounter {

    /**
     * Count the total number of attributes in an XML document.
     * @param inputStream the XML input stream
     * @return total count of attributes
     */
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

    /**
     * Recursively count attributes in an element and its children.
     * @param element the element to start from
     * @return total count of attributes
     */
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

