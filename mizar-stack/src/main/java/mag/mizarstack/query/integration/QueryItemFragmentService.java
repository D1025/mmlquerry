package mag.mizarstack.query.integration;

import lombok.RequiredArgsConstructor;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.DocumentHelper;
import org.dom4j.Node;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class QueryItemFragmentService {

    private static final Pattern XML_ID_PATTERN = Pattern.compile("\\bxmlid\\s*=\\s*\"([^\"]+)\"");

    private final JdbcClient jdbcClient;
    private final S3Client s3Client;

    @Value("${app.s3.bucket}")
    private String bucket;

    public ItemFragment fetchItemFragment(UUID itemId) {
        if (itemId == null) {
            throw new IllegalArgumentException("Path variable itemId is required");
        }

        ItemFragmentContext context = loadContext(itemId);
        ResolvedFragment resolved = resolveFragment(context);

        return new ItemFragment(
                context.itemId(),
                context.libId(),
                context.articleName(),
                resolved.source(),
                resolved.raw()
        );
    }

    private ItemFragmentContext loadContext(UUID itemId) {
        String sql = """
                select mi.id as item_id,
                       mi.lib_id,
                       a.name as article_name,
                       a.file_path as s3_key,
                       mi.raw_xml as item_raw_xml,
                       rn.raw as root_raw,
                       coalesce(rn.details -> 'attrs' ->> 'xmlid', rn.details -> 'attrs' ->> 'xmlId') as root_xmlid
                from mml_item mi
                join article a on a.id = mi.article_id
                left join view_item_root_nodes rn on rn.item_id = mi.id
                where mi.id = :itemId
                limit 1
                """;

        return jdbcClient.sql(sql)
                .param("itemId", itemId)
                .query((rs, rowNum) -> new ItemFragmentContext(
                        asUuid(rs.getObject("item_id")),
                        safeToString(rs.getObject("lib_id")),
                        safeToString(rs.getObject("article_name")),
                        safeToString(rs.getObject("s3_key")),
                        safeToString(rs.getObject("item_raw_xml")),
                        safeToString(rs.getObject("root_raw")),
                        safeToString(rs.getObject("root_xmlid"))
                ))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Item not found for itemId=" + itemId));
    }

    private ResolvedFragment resolveFragment(ItemFragmentContext context) {
        if (context.s3Key().isBlank()) {
            throw new IllegalArgumentException(
                    "S3 path is missing for article " + context.articleName() + " (itemId=" + context.itemId() + ")"
            );
        }

        String articleXml = fetchS3Xml(context.s3Key());
        String xmlId = firstNonBlank(
                context.rootXmlId(),
                extractXmlId(context.rootRaw()),
                extractXmlId(context.itemRawXml())
        );

        if (!xmlId.isBlank()) {
            Optional<String> fromS3 = findByXmlId(articleXml, xmlId);
            if (fromS3.isPresent()) {
                return new ResolvedFragment("s3", fromS3.get());
            }
        }

        if (!context.itemRawXml().isBlank()) {
            return new ResolvedFragment("db_raw_xml_fallback", context.itemRawXml());
        }

        throw new IllegalArgumentException(
                "Could not locate XML fragment in article " + context.articleName() + " for itemId=" + context.itemId()
        );
    }

    private String fetchS3Xml(String s3Key) {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucket)
                .key(s3Key)
                .build();

        try (ResponseInputStream<GetObjectResponse> stream = s3Client.getObject(request)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (NoSuchKeyException ex) {
            throw new IllegalArgumentException("S3 object not found: " + s3Key);
        } catch (S3Exception ex) {
            String message = ex.awsErrorDetails() != null
                    ? ex.awsErrorDetails().errorMessage()
                    : ex.getMessage();
            throw new IllegalStateException("S3 read failed for key " + s3Key + ": " + message, ex);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not read S3 stream for key " + s3Key, ex);
        }
    }

    private Optional<String> findByXmlId(String articleXml, String xmlId) {
        try {
            Document document = DocumentHelper.parseText(articleXml);
            String xpath = "//*[@xmlid=" + toXPathLiteral(xmlId) + "]";
            Node node = document.selectSingleNode(xpath);
            return node == null ? Optional.empty() : Optional.ofNullable(node.asXML());
        } catch (DocumentException ex) {
            throw new IllegalStateException("Could not parse article XML from S3", ex);
        }
    }

    private String extractXmlId(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        Matcher matcher = XML_ID_PATTERN.matcher(raw);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String toXPathLiteral(String value) {
        if (value.indexOf('\'') < 0) {
            return "'" + value + "'";
        }
        if (value.indexOf('"') < 0) {
            return "\"" + value + "\"";
        }
        String[] parts = value.split("'");
        StringBuilder out = new StringBuilder("concat(");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                out.append(",\"'\",");
            }
            out.append("'").append(parts[i]).append("'");
        }
        out.append(")");
        return out.toString();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String safeToString(Object value) {
        return value == null ? "" : value.toString();
    }

    private UUID asUuid(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof UUID uuid) {
            return uuid;
        }
        try {
            return UUID.fromString(raw.toString());
        } catch (Exception ignored) {
            return null;
        }
    }

    public record ItemFragment(
            UUID itemId,
            String libId,
            String articleName,
            String source,
            String raw
    ) {
    }

    private record ItemFragmentContext(
            UUID itemId,
            String libId,
            String articleName,
            String s3Key,
            String itemRawXml,
            String rootRaw,
            String rootXmlId
    ) {
    }

    private record ResolvedFragment(String source, String raw) {
    }
}
