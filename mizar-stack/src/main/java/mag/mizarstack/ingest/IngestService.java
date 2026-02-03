package mag.mizarstack.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import mag.mizarstack.dao.DocumentDao;
import mag.mizarstack.dao.DocumentHeadDao;
import mag.mizarstack.dao.DocumentVersionDao;
import mag.mizarstack.dao.IngestRunDao;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * IngestService that:
 * 1. Downloads ESX files from GitHub releases and stores them in S3/MinIO
 * 2. Indexes ESX files from S3 into mizar_schema tables using dom4j
 * 
 * All progress is printed to the console.
 */
@Slf4j
@Service
public class IngestService {

    private final S3Client s3;
    private final String bucket;
    private final EsxMmlMapperService esxMapper;
    private final HttpClient http;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    // GitHub config
    private final String ghRepo;
    private final String ghToken;
    private final int timeoutMs;
    
    // DAOs for document versioning
    private final DocumentDao documentDao;
    private final DocumentVersionDao documentVersionDao;
    private final DocumentHeadDao documentHeadDao;
    private final IngestRunDao ingestRunDao;

    public IngestService(
            S3Client s3,
            @Value("${app.s3.bucket}") String bucket,
            @Value("${app.github.repo:}") String ghRepo,
            @Value("${app.github.token:}") String ghToken,
            @Value("${app.http.timeoutMs:15000}") int timeoutMs,
            EsxMmlMapperService esxMapper,
            DocumentDao documentDao,
            DocumentVersionDao documentVersionDao,
            DocumentHeadDao documentHeadDao,
            IngestRunDao ingestRunDao
    ) {
        this.s3 = s3;
        this.bucket = bucket;
        this.ghRepo = ghRepo;
        this.ghToken = ghToken;
        this.timeoutMs = timeoutMs;
        this.esxMapper = esxMapper;
        this.documentDao = documentDao;
        this.documentVersionDao = documentVersionDao;
        this.documentHeadDao = documentHeadDao;
        this.ingestRunDao = ingestRunDao;
        
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();
    }

    // ==================== GitHub Download & S3 Upload ====================

    /**
     * Download the latest GitHub release and upload ESX files to S3/MinIO.
     * Returns the S3 prefix where files were uploaded.
     */
    public DownloadResult downloadLatestReleaseToS3() {
        Instant start = Instant.now();
        log.info("=== Starting GitHub Release Download ===");
        log.info("Repository: {}", ghRepo);
        log.info("Bucket: {}", bucket);

        if (ghRepo == null || ghRepo.isBlank()) {
            throw new IllegalStateException("GitHub repository not configured (app.github.repo)");
        }

        try {
            // 1. Get latest release info
            String latestUrl = "https://api.github.com/repos/%s/releases/latest".formatted(ghRepo);
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(URI.create(latestUrl))
                    .GET()
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Accept", "application/vnd.github+json");
            
            if (ghToken != null && !ghToken.isBlank()) {
                reqBuilder.header("Authorization", "Bearer " + ghToken);
            }

            HttpResponse<byte[]> metaResp = http.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofByteArray());
            
            if (metaResp.statusCode() != 200) {
                throw new RuntimeException("GitHub API returned HTTP " + metaResp.statusCode());
            }

            JsonNode root = objectMapper.readTree(metaResp.body());
            String tagName = root.path("tag_name").asText(null);
            String releaseName = root.path("name").asText(tagName);

            if (tagName == null || tagName.isBlank()) {
                throw new RuntimeException("No tag_name found in release");
            }

            log.info("Found release: {} (tag: {})", releaseName, tagName);

            // 2. Download zipball
            String s3Prefix = "mizar-esx/releases/%s".formatted(tagName);
            log.info("S3 prefix will be: {}", s3Prefix);

            HttpResponse<InputStream> zipResp = downloadZipball(tagName);
            
            if (zipResp.statusCode() / 100 != 2) {
                throw new RuntimeException("Failed to download zipball: HTTP " + zipResp.statusCode());
            }

            // 3. Extract and upload to S3
            int filesUploaded = 0;
            long bytesUploaded = 0;
            List<String> uploadedKeys = new ArrayList<>();

            try (ZipInputStream zis = new ZipInputStream(zipResp.body())) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        zis.closeEntry();
                        continue;
                    }

                    String fullName = entry.getName();
                    String rel = stripTopDir(fullName);

                    // Only process .esx files from esx_mml or esx_abstr folders
                    if (!rel.endsWith(".esx")) {
                        zis.closeEntry();
                        continue;
                    }
                    
                    if (!(rel.startsWith("esx_mml/") || rel.startsWith("esx_abstr/"))) {
                        zis.closeEntry();
                        continue;
                    }

                    try {
                        byte[] body = readAll(zis);
                        
                        if (body == null || body.length == 0) {
                            log.warn("Empty file: {}", rel);
                            zis.closeEntry();
                            continue;
                        }

                        String s3Key = s3Prefix + "/" + rel;
                        
                        // Upload to S3
                        s3.putObject(
                                PutObjectRequest.builder()
                                        .bucket(bucket)
                                        .key(s3Key)
                                        .contentType("application/xml")
                                        .build(),
                                RequestBody.fromBytes(body)
                        );

                        uploadedKeys.add(s3Key);
                        filesUploaded++;
                        bytesUploaded += body.length;

                        if (filesUploaded % 100 == 0) {
                            log.info("  Uploaded {} files...", filesUploaded);
                        }

                    } catch (Exception ex) {
                        log.error("Failed to upload {}: {}", rel, ex.getMessage());
                    } finally {
                        zis.closeEntry();
                    }
                }
            }

            Duration elapsed = Duration.between(start, Instant.now());
            
            log.info("=== Download Complete ===");
            log.info("Tag:           {}", tagName);
            log.info("Files:         {}", filesUploaded);
            log.info("Bytes:         {}", formatBytes(bytesUploaded));
            log.info("S3 Prefix:     {}", s3Prefix);
            log.info("Duration:      {}", formatDuration(elapsed));

            return new DownloadResult(tagName, s3Prefix, filesUploaded, bytesUploaded, elapsed);

        } catch (Exception ex) {
            log.error("Download failed: {}", ex.getMessage(), ex);
            throw new RuntimeException("Failed to download release", ex);
        }
    }

    private HttpResponse<InputStream> downloadZipball(String ref) throws IOException, InterruptedException {
        String zipballUrl = "https://api.github.com/repos/%s/zipball/%s".formatted(ghRepo, ref);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(zipballUrl))
                .GET()
                .timeout(Duration.ofMillis(Math.max(timeoutMs, 120_000)));
        
        if (ghToken != null && !ghToken.isBlank()) {
            builder.header("Authorization", "Bearer " + ghToken);
        }
        
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
    }

    // ==================== S3 Indexing to mizar_schema ====================

    /**
     * Index all ESX files from an S3 prefix into mizar_schema tables.
     * Progress is printed to the console.
     */
    public IndexResult indexS3Prefix(String s3Prefix) {
        Instant start = Instant.now();
        
        // Auto-deduplicate prefix (handle cases like mizar-esx/mizar-esx/releases/...)
        if (s3Prefix.contains("mizar-esx/mizar-esx/")) {
            log.warn("Detected duplicate 'mizar-esx' in prefix, auto-deduplicating");
            s3Prefix = s3Prefix.replace("mizar-esx/mizar-esx/", "mizar-esx/");
            log.info("Corrected prefix: {}", s3Prefix);
        }
        
        log.info("=== Starting S3 Indexing to mizar_schema ===");
        log.info("Bucket: {}", bucket);
        log.info("Prefix: {}", s3Prefix);

        // Create ingest run record
        Long runId = ingestRunDao.create();
        log.info("Created ingest run ID: {}", runId);

        int seen = 0;
        int processed = 0;
        int failed = 0;
        long totalBytes = 0;
        int newVersions = 0;

        try {
            ListObjectsV2Request listReq = ListObjectsV2Request.builder()
                    .bucket(bucket)
                    .prefix(s3Prefix)
                    .build();

            var paginator = s3.listObjectsV2Paginator(listReq);

            for (var page : paginator) {
                for (S3Object obj : page.contents()) {
                    String key = obj.key();

                    // Skip directory markers
                    if (key.endsWith("/")) continue;

                    // Only process .esx files
                    if (!key.endsWith(".esx")) continue;

                    seen++;
                    
                    if (seen % 50 == 0) {
                        log.info("[Progress] Processed {} files...", seen);
                    }

                    try {
                        IndexFileResult result = indexSingleS3File(key);
                        
                        if (result.isNewVersion()) {
                            newVersions++;
                            log.debug("  NEW: {} (versionId={})", key, result.versionId());
                        }

                        totalBytes += result.sizeBytes();
                        processed++;

                    } catch (Exception ex) {
                        failed++;
                        log.error("  FAILED {}", key, ex);
                    }
                }
            }

            // Update ingest run with final statistics
            ingestRunDao.complete(runId, seen, processed, newVersions, totalBytes);

            Duration elapsed = Duration.between(start, Instant.now());
            log.info("=== Indexing Complete ===");
            log.info("Run ID:          {}", runId);
            log.info("Files seen:      {}", seen);
            log.info("Files processed: {}", processed);
            log.info("New versions:    {}", newVersions);
            log.info("Files failed:    {}", failed);
            log.info("Total bytes:     {}", formatBytes(totalBytes));
            log.info("Duration:        {}", formatDuration(elapsed));

            return new IndexResult(runId, seen, processed, newVersions, failed, totalBytes, elapsed);

        } catch (Exception ex) {
            log.error("Fatal error during indexing: {}", ex.getMessage(), ex);
            ingestRunDao.complete(runId, seen, processed, newVersions, totalBytes);
            throw new RuntimeException("Indexing failed", ex);
        }
    }

    /**
     * Index a single S3 file: create document version and parse to mizar_schema.
     */
    private IndexFileResult indexSingleS3File(String s3Key) throws Exception {
        // Fetch the file from S3
        GetObjectRequest req = GetObjectRequest.builder()
                .bucket(bucket)
                .key(s3Key)
                .build();

        byte[] body;
        try (ResponseInputStream<GetObjectResponse> resp = s3.getObject(req)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            resp.transferTo(out);
            body = out.toByteArray();
        }

        if (body == null || body.length == 0) {
            throw new IllegalStateException("File has zero bytes");
        }

        // Compute SHA-256 hash
        String sha256 = sha256Hex(body);
        String documentUrl = "s3://" + bucket + "/" + s3Key;

        // Upsert document record (for versioning)
        Long docId = documentDao.upsert(documentUrl);

        // Check if this version already exists
        Optional<Long> existingVersionId = documentVersionDao.findIdByDocumentIdAndSha256(docId, sha256);
        
        if (existingVersionId.isPresent()) {
            // Already have this version - but still parse to mizar_schema
            parseAndMapToMizarSchema(body, s3Key);
            return new IndexFileResult(docId, existingVersionId.get(), false, body.length);
        }

        // Create new version
        Long versionId = documentVersionDao.insert(
                docId,
                null,
                Instant.now(),
                sha256,
                body.length,
                s3Key
        );

        // Update document head to point to new version
        documentHeadDao.upsert(docId, versionId);

        // Parse and map ESX content to mizar_schema tables using dom4j
        parseAndMapToMizarSchema(body, s3Key);

        return new IndexFileResult(docId, versionId, true, body.length);
    }

    /**
     * Parse ESX XML and map to mizar_schema tables (article, mml_item, constructor, notation, etc.)
     */
    private void parseAndMapToMizarSchema(byte[] body, String s3Key) {
        try {
            var messages = new ArrayList<String>();
            Map<String, Object> parseResult = esxMapper.processArticleXml(body, s3Key, messages::add);
            log.debug("  Parsed {}: items={}, refs={}", 
                    s3Key, 
                    parseResult.get("processedItems"), 
                    parseResult.get("pendingRefs"));
        } catch (Exception ex) {
            log.warn("  ESX parsing warning for {}", s3Key, ex);
        }
    }

    // ==================== Combined: Download + Index ====================

    /**
     * Download latest release from GitHub to S3, then index all files to mizar_schema.
     *
     * This is the complete pipeline:
     * 1. Fetch latest GitHub release (MizarProject/Mizar)
     * 2. Download and extract zipball
     * 3. Upload esx_mml/* and esx_abstr/* files to: mizar-esx/releases/{tagName}/
     * 4. Index esx_mml files from: mizar-esx/releases/{tagName}/esx_mml/
     *
     * Final S3 structure:
     *   mizar-esx/releases/{tagName}/esx_mml/{article}.esx
     *   Example: mizar-esx/releases/esx-mizar-8.1.11_5.68.1412/esx_mml/XBOOLE_0.esx
     *
     * @return FullIngestResult with both download and index statistics
     */
    public FullIngestResult downloadAndIndex() {
        log.info("=== Full Ingest: Download + Index ===");
        
        // Step 1: Download from GitHub to S3
        DownloadResult downloadResult = downloadLatestReleaseToS3();
        
        // Step 2: Index from S3 to mizar_schema
        // Index esx_mml folder (automatically appends /esx_mml to the s3Prefix)
        String esxMmlPrefix = downloadResult.s3Prefix() + "/esx_mml";
        IndexResult indexResult = indexS3Prefix(esxMmlPrefix);
        
        return new FullIngestResult(downloadResult, indexResult);
    }

    // ==================== Stats ====================

    public Map<String, Object> getLatestRunStats() {
        return ingestRunDao.getLatestRunSummary();
    }

    // ==================== Helper Methods ====================

    private static String sha256Hex(byte[] data) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
        return HexFormat.of().formatHex(hash);
    }

    private static byte[] readAll(ZipInputStream zis) throws IOException {
        byte[] buf = new byte[64 * 1024];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int r;
        while ((r = zis.read(buf)) != -1) {
            out.write(buf, 0, r);
        }
        return out.toByteArray();
    }

    private static String stripTopDir(String name) {
        int idx = name.indexOf('/');
        return idx >= 0 ? name.substring(idx + 1) : name;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private static String formatDuration(Duration d) {
        long seconds = d.getSeconds();
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return String.format("%dm %ds", seconds / 60, seconds % 60);
        return String.format("%dh %dm %ds", seconds / 3600, (seconds % 3600) / 60, seconds % 60);
    }

    // ==================== Result Records ====================

    public record DownloadResult(
            String tagName,
            String s3Prefix,
            int filesUploaded,
            long bytesUploaded,
            Duration duration
    ) {}

    public record IndexResult(
            Long runId,
            int filesSeen,
            int filesProcessed,
            int newVersions,
            int filesFailed,
            long totalBytes,
            Duration duration
    ) {}

    public record FullIngestResult(
            DownloadResult download,
            IndexResult index
    ) {}

    private record IndexFileResult(
            Long documentId,
            Long versionId,
            boolean isNewVersion,
            long sizeBytes
    ) {}
}
