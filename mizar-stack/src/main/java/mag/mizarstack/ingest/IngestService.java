package mag.mizarstack.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import mag.mizarstack.search.XmlAttributeCounter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
@Service
public class IngestService {

    private final JdbcClient db;
    private final S3Client s3;
    private final String bucket;
    private final HttpClient http;
    private final int timeoutMs;
    private final XmlProcessingService processor;
    private final List<String> sources;

    // tryby i GitHub
    private final String mode;    // DIRECT | GITHUB_RELEASES
    private final String ghRepo;  // owner/repo
    private final String ghToken; // opcjonalny PAT

    private final ObjectMapper om = new ObjectMapper();

    public IngestService(
            JdbcClient db,
            S3Client s3,
            @Value("${app.s3.bucket}") String bucket,
            @Value("${app.http.timeoutMs:15000}") int timeoutMs,
            @Value("${app.ingest.sources:}") List<String> sources,
            XmlProcessingService processor,
            @Value("${app.ingest.mode:DIRECT}") String mode,
            @Value("${app.github.repo:}") String ghRepo,
            @Value("${app.github.token:}") String ghToken
    ) {
        this.db = db;
        this.s3 = s3;
        this.bucket = bucket;
        this.timeoutMs = timeoutMs;
        this.sources = sources;
        this.processor = processor;
        this.mode = mode;
        this.ghRepo = ghRepo;
        this.ghToken = ghToken;

        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();
    }

    @Scheduled(cron = "${app.ingest.poll.cron}")
    public void scheduledIngest() {
        log.info("Ingest tick (mode={}, sources={}, repo={})", mode,
                sources != null ? sources.size() : 0, ghRepo);
        runIngest();
    }

    public Map<String,Object> runIngest() {
        long runId = db.sql("insert into ingest_run default values returning id")
                .query(Long.class).single();

        int seen = 0, downloaded = 0, versions = 0, failedZero = 0, failedError = 0;
        long bytes = 0;

        try {
            if ("GITHUB_RELEASES".equalsIgnoreCase(mode)) {
                Stats s = processGitHubReleasesViaZipball();
                seen += s.seen; downloaded += s.downloaded; versions += s.versions; bytes += s.bytes;
                failedZero += s.failedZero; failedError += s.failedError;
            } else {
                Stats s = processDirectSources();
                seen += s.seen; downloaded += s.downloaded; versions += s.versions; bytes += s.bytes;
                failedZero += s.failedZero; failedError += s.failedError;
            }
        } catch (Exception e) {
            log.warn("Ingest failed", e);
        }

        db.sql("""
        update ingest_run set finished_at=now(),
          files_seen=:s, files_downloaded=:d, versions_added=:v, bytes_downloaded=:b
        where id=:id
        """).params(Map.of("s", seen, "d", downloaded, "v", versions, "b", bytes, "id", runId)).update();

        return Map.of(
                "runId", runId,
                "seen", seen,
                "downloaded", downloaded,
                "versionsAdded", versions,
                "bytes", bytes,
                "failedZeroBytes", failedZero,
                "failedErrors", failedError
        );
    }

    // ---------------------- DIRECT ----------------------

    private Stats processDirectSources() {
        int seen = 0, downloaded = 0, versions = 0, failedZero = 0, failedError = 0;
        long bytes = 0;

        if (sources == null || sources.isEmpty()) {
            log.info("DIRECT mode: no sources configured");
            return new Stats(seen, downloaded, versions, bytes, failedZero, failedError);
        }

        for (String url : sources) {
            seen++;
            try {
                Long docId = db.sql("""
            insert into document(source_url) values(:u)
            on conflict (source_url) do update set source_url=excluded.source_url
            returning id
            """).param("u", url).query(Long.class).single();

                // posledni ETag/Last-Modified dla conditional GET
                var meta = db.sql("""
            select dv.etag, dv.last_modified from document_head dh
            join document_version dv on dv.id = dh.current_version_id
            where dh.document_id = :id
            """).params(Map.of("id", docId)).query(rs -> rs.next()
                        ? Map.of("etag", rs.getString(1), "lastmod", rs.getTimestamp(2)) : Map.of());

                HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                        .GET()
                        .timeout(Duration.ofMillis(timeoutMs));

                if (meta.containsKey("etag") && meta.get("etag") != null) {
                    rb.header("If-None-Match", Objects.toString(meta.get("etag")));
                }
                if (meta.containsKey("lastmod") && meta.get("lastmod") != null) {
                    rb.header("If-Modified-Since",
                            ((Timestamp) meta.get("lastmod")).toInstant().toString());
                }

                var resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofByteArray());
                if (resp.statusCode() == 304) continue;

                if (resp.statusCode() / 100 == 2) {
                    byte[] body = resp.body();

                    if (body == null || body.length == 0) {
                        failedZero++;
                        log.warn("DIRECT: {} returned 0 bytes — skipping version", url);
                        continue;
                    }

                    bytes += body.length;
                    String sha256 = sha256Hex(body);

                    Long existing = db.sql("""
              select dv.id from document_version dv where dv.document_id=:did and dv.sha256=:h
              """).params(Map.of("did", docId, "h", sha256))
                            .query(Long.class).optional().orElse(null);

                    if (existing == null) {
                        String key = "files/%d/%s.xml".formatted(docId, sha256);
                        s3.putObject(PutObjectRequest.builder()
                                        .bucket(bucket).key(key).contentType("application/xml").build(),
                                software.amazon.awssdk.core.sync.RequestBody.fromBytes(body));

                        String etag = resp.headers().firstValue("etag").orElse(null);
                        Instant lastMod = resp.headers().firstValue("last-modified")
                                .flatMap(this::parseInstantSafe).orElseGet(Instant::now);

                        Long vId = db.sql("""
                insert into document_version(document_id, etag, last_modified, sha256, size_bytes, s3_key)
                values(:did,:e,:lm,:h,:sz,:k) returning id
                """).params(Map.of(
                                "did", docId, "e", etag, "lm", Timestamp.from(lastMod),
                                "h", sha256, "sz", body.length, "k", key
                        )).query(Long.class).single();

                        db.sql("""
                insert into document_head(document_id, current_version_id)
                values(:d,:v)
                on conflict (document_id) do update set current_version_id=excluded.current_version_id
                """).params(Map.of("d", docId, "v", vId)).update();

                        // Zlicz atrybuty i zapisz do cache
                        try {
                            long attrDefs = XmlAttributeCounter.countAttributes(new ByteArrayInputStream(body));
                            db.sql("insert into attribute_count(document_version_id, attribute_definitions) values(:id,:c) on conflict (document_version_id) do update set attribute_definitions=excluded.attribute_definitions, counted_at=now()")
                                    .params(Map.of("id", vId, "c", attrDefs))
                                    .update();
                        } catch (Exception exCount) {
                            log.warn("Counting attributes failed for docVersion {}", vId, exCount);
                        }

                        downloaded++;
                        versions++;
                        processor.processXml(body);
                    }
                } else {
                    log.warn("DIRECT: HTTP {} for {}", resp.statusCode(), url);
                }
            } catch (Exception ex) {
                failedError++;
                log.warn("DIRECT: error for {}", url, ex);
            }
        }

        return new Stats(seen, downloaded, versions, bytes, failedZero, failedError);
    }

    // ------------------ GITHUB_RELEASES (zipball) ------------------

    private Stats processGitHubReleasesViaZipball() throws Exception {
        int seen = 0, downloaded = 0, versions = 0, failedZero = 0, failedError = 0;
        long bytes = 0;

        if (ghRepo == null || ghRepo.isBlank()) {
            log.warn("GITHUB_RELEASES mode enabled but app.github.repo is empty");
            return new Stats(seen, downloaded, versions, bytes, failedZero, failedError);
        }

        // 1) meta „latest release” z ETag (304 przy braku zmian nie schodzi z limitu, jeśli autoryzowane)
        String latestUrl = "https://api.github.com/repos/%s/releases/latest".formatted(ghRepo);
        String prevEtag = db.sql("select etag from github_source_state where repo=:r")
                .params(Map.of("r", ghRepo)).query(String.class).optional().orElse(null);

        HttpRequest.Builder metaReq = HttpRequest.newBuilder(URI.create(latestUrl))
                .GET()
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Accept", "application/vnd.github+json");
        if (ghToken != null && !ghToken.isBlank()) metaReq.header("Authorization", "Bearer " + ghToken);
        if (prevEtag != null && !prevEtag.isBlank()) metaReq.header("If-None-Match", prevEtag);

        HttpResponse<byte[]> metaResp = http.send(metaReq.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (metaResp.statusCode() == 304) {
            log.info("GitHub: no new release (304) for {}", ghRepo);
            return new Stats(seen, downloaded, versions, bytes, failedZero, failedError);
        }
        if (metaResp.statusCode() / 100 != 2) {
            log.warn("GitHub: HTTP {} for {}", metaResp.statusCode(), latestUrl);
            return new Stats(seen, downloaded, versions, bytes, failedZero, failedError);
        }

        String etag = metaResp.headers().firstValue("etag").orElse(null);
        JsonNode root = om.readTree(metaResp.body());
        String tag = root.path("tag_name").asText(null);
        long releaseId = root.path("id").asLong();
        Instant publishedAt = parseInstantSafe(root.path("published_at").asText(null)).orElseGet(Instant::now);

        if (tag == null || tag.isBlank()) {
            log.warn("GitHub: latest release returned no tag_name");
            // nie zapisujemy stanu — pozwala to na retry
            return new Stats(seen, downloaded, versions, bytes, failedZero, failedError);
        }

        // 2) Pobierz Source code (zip) zipball dla taga (redirect-friendly)
        HttpResponse<java.io.InputStream> zipResp = downloadZipball(ghRepo, tag);
        if (zipResp.statusCode() / 100 != 2) {
            log.warn("GitHub: ZIPBALL HTTP {} for tag {}", zipResp.statusCode(), tag);
            // nie zapisujemy stanu — retry przy kolejnym przebiegu
            return new Stats(seen, downloaded, versions, bytes, failedZero, failedError);
        }

        boolean hadFailures = false;

        // 3) Rozpakuj zipball, filtruj *.esx, zapisz do S3 i DB
        try (ZipInputStream zis = new ZipInputStream(zipResp.body())) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) { zis.closeEntry(); continue; }

                String fullName = entry.getName();
                String rel = stripTopDir(fullName);

                if (!rel.endsWith(".esx")) { zis.closeEntry(); continue; }
                if (!(rel.startsWith("esx_mml/") || rel.startsWith("esx_abstr/"))) {
                    zis.closeEntry(); continue;
                }

                seen++;

                try {
                    byte[] body = readAll(zis);

                    if (body == null || body.length == 0) {
                        failedZero++;
                        hadFailures = true;
                        log.warn("RELEASE {}: entry {} returned 0 bytes — skipping version", tag, rel);
                        zis.closeEntry();
                        continue;
                    }

                    bytes += body.length;
                    String sha256 = sha256Hex(body);

                    String documentKey = "gh://%s/%s".formatted(ghRepo, rel);

                    Long docId = db.sql("""
              insert into document(source_url) values(:u)
              on conflict (source_url) do update set source_url=excluded.source_url
              returning id
              """).param("u", documentKey).query(Long.class).single();

                    Long existing = db.sql("""
              select dv.id from document_version dv where dv.document_id=:did and dv.sha256=:h
              """).params(Map.of("did", docId, "h", sha256))
                            .query(Long.class).optional().orElse(null);

                    if (existing == null) {
                        String s3Key = "releases/%s/%s".formatted(tag, rel);
                        s3.putObject(PutObjectRequest.builder()
                                        .bucket(bucket).key(s3Key).contentType("application/xml").build(),
                                software.amazon.awssdk.core.sync.RequestBody.fromBytes(body));

                        Long vId = db.sql("""
                insert into document_version(document_id, etag, last_modified, sha256, size_bytes, s3_key)
                values(:did,:e,:lm,:h,:sz,:k) returning id
                """).params(Map.of(
                                "did", docId,
                                "e", etag,
                                "lm", Timestamp.from(publishedAt),
                                "h", sha256,
                                "sz", body.length,
                                "k", s3Key
                        )).query(Long.class).single();

                        db.sql("""
                insert into document_head(document_id, current_version_id)
                values(:d,:v)
                on conflict (document_id) do update set current_version_id=excluded.current_version_id
                """).params(Map.of("d", docId, "v", vId)).update();

                        // Zlicz atrybuty i zapisz do cache (release)
                        try {
                            long attrDefs = XmlAttributeCounter.countAttributes(new ByteArrayInputStream(body));
                            db.sql("insert into attribute_count(document_version_id, attribute_definitions) values(:id,:c) on conflict (document_version_id) do update set attribute_definitions=excluded.attribute_definitions, counted_at=now()")
                                    .params(Map.of("id", vId, "c", attrDefs))
                                    .update();
                        } catch (Exception exCount) {
                            log.warn("Counting attributes failed (release) for docVersion {}", vId, exCount);
                        }

                        downloaded++;
                        versions++;
                        processor.processXml(body);
                    }
                } catch (Exception ex) {
                    failedError++;
                    hadFailures = true;
                    log.warn("RELEASE {}: failed processing entry {}", tag, rel, ex);
                } finally {
                    zis.closeEntry();
                }
            }
        }

        // 4) Aktualizuj stan Releases tylko, jeśli CAŁE wydanie przeszło bez błędów
        if (!hadFailures) {
            upsertGitHubState(etag, releaseId, tag);
        } else {
            log.warn("RELEASE {}: some entries failed (failedZero={}, failedError={}) — keeping old ETag to retry next run",
                    tag, failedZero, failedError);
            // brak upsert = pozostaje poprzedni ETag => kolejny przebieg pobierze ponownie to samo wydanie.
            // To bezpieczne dzięki deduplikacji po SHA-256.
        }

        return new Stats(seen, downloaded, versions, bytes, failedZero, failedError);
    }

    // -------- Helpers --------

    private HttpResponse<java.io.InputStream> downloadZipball(String repo, String ref)
            throws IOException, InterruptedException {
        // Oficjalny endpoint zipball — zwraca redirect do archiwum ZIP
        // https://docs.github.com/en/rest/repos/contents#download-a-repository-archive-zip
        String zipballUrl = "https://api.github.com/repos/%s/zipball/%s".formatted(repo, ref);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(zipballUrl))
                .GET()
                .timeout(Duration.ofMillis(Math.max(timeoutMs, 60_000)));
        if (ghToken != null && !ghToken.isBlank()) b.header("Authorization", "Bearer " + ghToken);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
    }

    private static String stripTopDir(String name) {
        int idx = name.indexOf('/');
        return idx >= 0 ? name.substring(idx + 1) : name;
    }

    private void upsertGitHubState(String etag, long releaseId, String tag) {
        db.sql("""
        insert into github_source_state(repo, etag, last_release_id, last_tag)
        values(:r,:e,:id,:t)
        on conflict (repo) do update set
          etag=excluded.etag,
          last_release_id=excluded.last_release_id,
          last_tag=excluded.last_tag,
          last_checked=now()
        """).params(Map.of("r", ghRepo, "e", etag, "id", releaseId, "t", tag)).update();
    }

    private static String sha256Hex(byte[] body) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body));
    }

    private static byte[] readAll(ZipInputStream zis) throws IOException {
        byte[] buf = new byte[64 * 1024];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int r;
        while ((r = zis.read(buf)) != -1) out.write(buf, 0, r);
        return out.toByteArray();
    }

    private Optional<Instant> parseInstantSafe(String v) {
        if (v == null || v.isBlank()) return Optional.empty();
        try { return Optional.of(Instant.parse(v)); }
        catch (Exception ignore) { return Optional.empty(); }
    }

    private record Stats(int seen, int downloaded, int versions, long bytes, int failedZero, int failedError) {}
}
