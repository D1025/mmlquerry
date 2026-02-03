package mag.mizarstack.web;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mag.mizarstack.ingest.IngestService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller for ingestion operations:
 * - Download ESX files from GitHub releases to S3/MinIO
 * - Index ESX files from S3 to mizar_schema tables
 * 
 * All progress is printed to the server console.
 */
@Slf4j
@RestController
@RequestMapping("/ingest")
@RequiredArgsConstructor
public class IngestController {

    private final IngestService ingestService;

    /**
     * Download the latest GitHub release and upload ESX files to S3/MinIO.
     * 
     * POST /ingest/download
     * 
     * @return Summary of the download operation including S3 prefix
     */
    @PostMapping("/download")
    public Map<String, Object> downloadFromGitHub() {
        log.info("Received request to download latest release from GitHub");
        
        IngestService.DownloadResult result = ingestService.downloadLatestReleaseToS3();
        
        return Map.of(
                "tagName", result.tagName(),
                "s3Prefix", result.s3Prefix(),
                "filesUploaded", result.filesUploaded(),
                "bytesUploaded", result.bytesUploaded(),
                "durationSeconds", result.duration().getSeconds()
        );
    }

    /**
     * Index all ESX files from an S3 prefix to mizar_schema tables.
     * 
     * The prefix should point to the esx_mml folder.
     *
     * Structure in S3: mizar-esx/releases/{tagName}/esx_mml/{article}.esx
     *
     * Example request:
     *   POST /ingest/index?prefix=mizar-esx/releases/esx-mizar-8.1.11_5.68.1412/esx_mml
     *
     * Where:
     *   - mizar-esx/releases = base path in S3
     *   - esx-mizar-8.1.11_5.68.1412 = tagName from GitHub release
     *   - /esx_mml = folder containing article .esx files (MUST be included in prefix)
     *
     * Note: For automatic download from GitHub + indexing, use POST /ingest/full instead.
     *
     * @param prefix The S3 prefix pointing to esx_mml folder (e.g., mizar-esx/releases/TAG/esx_mml)
     * @return Summary of the indexing operation including runId, filesSeen, filesProcessed, newVersions, etc.
     */
    @PostMapping("/index")
    public Map<String, Object> indexFromS3(@RequestParam String prefix) {
        log.info("Received request to index S3 prefix: {}", prefix);
        
        IngestService.IndexResult result = ingestService.indexS3Prefix(prefix);
        
        return Map.of(
                "runId", result.runId(),
                "filesSeen", result.filesSeen(),
                "filesProcessed", result.filesProcessed(),
                "newVersions", result.newVersions(),
                "filesFailed", result.filesFailed(),
                "totalBytes", result.totalBytes(),
                "durationSeconds", result.duration().getSeconds()
        );
    }

    /**
     * Full ingest pipeline: Download latest release from GitHub to S3, then index to mizar_schema.
     *
     * This is the recommended endpoint. It performs:
     * 1. Fetch latest release from GitHub (MizarProject/Mizar)
     * 2. Extract esx_mml/ and esx_abstr/ folders from zipball
     * 3. Upload to S3/MinIO at: mizar-esx/releases/{tagName}/esx_mml/
     * 4. Index all .esx files from mizar-esx/releases/{tagName}/esx_mml/ into PostgreSQL mizar_schema
     *
     * Structure created:
     *   mizar-esx/releases/{tagName}/esx_mml/{article}.esx  -> Article table + mml_item + constructor + ...
     *
     * POST /ingest/full
     * 
     * @return Summary of both download and index operations (tagName, s3Prefix, files, duration, etc.)
     */
    @PostMapping("/full")
    public Map<String, Object> fullIngest() {
        log.info("Received request for full ingest (download + index)");
        
        IngestService.FullIngestResult result = ingestService.downloadAndIndex();
        
        return Map.of(
                "download", Map.of(
                        "tagName", result.download().tagName(),
                        "s3Prefix", result.download().s3Prefix(),
                        "filesUploaded", result.download().filesUploaded(),
                        "bytesUploaded", result.download().bytesUploaded(),
                        "durationSeconds", result.download().duration().getSeconds()
                ),
                "index", Map.of(
                        "runId", result.index().runId(),
                        "filesSeen", result.index().filesSeen(),
                        "filesProcessed", result.index().filesProcessed(),
                        "newVersions", result.index().newVersions(),
                        "filesFailed", result.index().filesFailed(),
                        "totalBytes", result.index().totalBytes(),
                        "durationSeconds", result.index().duration().getSeconds()
                )
        );
    }

    /**
     * Get the latest indexing run statistics.
     * 
     * GET /ingest/stats/latest
     */
    @GetMapping("/stats/latest")
    public Map<String, Object> getLatestStats() {
        return ingestService.getLatestRunStats();
    }
}
