package mag.mizarstack.query.integration;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class QueryVersionService {

    public static final String ALL_VERSIONS = "*";
    public static final String LEGACY_VERSION = "legacy";

    private final JdbcClient jdbcClient;

    public QueryVersionOverview loadOverview() {
        String sql = """
                select coalesce(nullif(trim(a.version_tag), ''), 'legacy') as version_tag,
                       count(distinct a.id) as article_count,
                       count(mi.id) as item_count,
                       max(mi.created_at) as last_indexed_at
                from article a
                left join mml_item mi on mi.article_id = a.id
                group by coalesce(nullif(trim(a.version_tag), ''), 'legacy')
                order by max(mi.created_at) desc nulls last, version_tag desc
                """;

        List<VersionInfo> versions = new ArrayList<>(jdbcClient.sql(sql)
                .query((rs, rowNum) -> new VersionInfo(
                        safeToString(rs.getObject("version_tag")),
                        rs.getLong("article_count"),
                        rs.getLong("item_count"),
                        toInstant(rs.getObject("last_indexed_at"))
                ))
                .list());

        String defaultVersion = versions.isEmpty() ? LEGACY_VERSION : versions.get(0).version();
        return new QueryVersionOverview(defaultVersion, versions);
    }

    public String resolveVersionOrDefault(String requestedVersion) {
        boolean explicitAllVersions = requestedVersion != null && ALL_VERSIONS.equals(requestedVersion.trim());
        if (explicitAllVersions) {
            return ALL_VERSIONS;
        }

        String normalizedRequested = normalizeVersion(requestedVersion);
        QueryVersionOverview overview = loadOverview();
        if (overview.versions().isEmpty()) {
            return LEGACY_VERSION;
        }
        if (normalizedRequested == null) {
            return overview.defaultVersion();
        }
        if (overview.versions().stream().anyMatch(v -> v.version().equals(normalizedRequested))) {
            return normalizedRequested;
        }
        return overview.defaultVersion();
    }

    public static String normalizeVersion(String rawVersion) {
        if (rawVersion == null) {
            return null;
        }
        String trimmed = rawVersion.trim();
        if (trimmed.isEmpty() || ALL_VERSIONS.equals(trimmed)) {
            return null;
        }
        return trimmed;
    }

    private static String safeToString(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private static Instant toInstant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof java.time.OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        return null;
    }

    public record QueryVersionOverview(String defaultVersion, List<VersionInfo> versions) {
    }

    public record VersionInfo(
            String version,
            long articleCount,
            long itemCount,
            Instant lastIndexedAt
    ) {
    }
}
