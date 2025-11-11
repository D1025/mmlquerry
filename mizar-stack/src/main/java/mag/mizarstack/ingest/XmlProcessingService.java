package mag.mizarstack.ingest;

import lombok.extern.slf4j.Slf4j;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;
import org.dom4j.ElementHandler;
import org.dom4j.ElementPath;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class XmlProcessingService {

    /**
     * Przetwarza dokument XML „kawałkami” – bez trzymania całości w pamięci.
     * Zwraca liczbę przetworzonych fragmentów pasujących do zarejestrowanych ścieżek.
     */
    public int processXml(byte[] xmlBytes) {
        var processed = new AtomicInteger(0);

        try {
            SAXReader reader = new SAXReader();
            // Przykład: reagujemy na każdy <AdjectiveCluster> (dostosuj do swojej struktury ESX)
            reader.addHandler("//AdjectiveCluster", new ElementHandler() {
                @Override public void onStart(ElementPath path) { /* opcjonalnie */ }
                @Override public void onEnd(ElementPath path) {
                    Element el = path.getCurrent();
                    try {
                        // tutaj tworzysz swój obiekt na bazie dom4j.Element:
                        // new AdjectiveCluster(el).run();
                        processed.incrementAndGet();
                    } finally {
                        // bardzo ważne: odłącz gałąź, żeby nie rosła pamięć
                        el.detach();
                    }
                }
            });

            // Parsujemy ze strumienia (nie tworzymy Stringa gdy nie trzeba)
            try (var in = new ByteArrayInputStream(xmlBytes)) {
                reader.read(in);
            }
        } catch (Exception e) {
            log.warn("XML processing failed", e);
        }

        return processed.get();
    }
}
