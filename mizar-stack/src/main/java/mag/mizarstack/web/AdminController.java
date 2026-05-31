package mag.mizarstack.web;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.servlet.http.HttpServletResponse;
import mag.mizarstack.admin.AdminOperationEvent;
import mag.mizarstack.admin.AdminAuthService;
import mag.mizarstack.admin.AdminOperationManager;
import mag.mizarstack.admin.AdminOperationSnapshot;
import mag.mizarstack.ingest.dto.DownloadResult;
import mag.mizarstack.ingest.dto.FullIngestResult;
import mag.mizarstack.ingest.dto.IndexResult;
import mag.mizarstack.ingest.service.IngestService;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final IngestService ingestService;
    private final AdminOperationManager operationManager;
    private final AdminAuthService adminAuthService;

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "ok", true,
                "configured", adminAuthService.isConfigured()
        );
    }

    @PostMapping("/operations/download")
    public AdminOperationSnapshot startDownload() {
        return operationManager.start("download", operationId -> {
            DownloadResult result = ingestService.downloadLatestReleaseToS3(
                    operationManager.progressLogger(operationId)
            );
            return toDownloadMap(result);
        });
    }

    @PostMapping("/operations/index")
    public AdminOperationSnapshot startIndex(@RequestParam String prefix) {
        String normalizedPrefix = prefix == null ? "" : prefix.trim();
        if (normalizedPrefix.isBlank()) {
            throw new IllegalArgumentException("Parametr 'prefix' jest wymagany.");
        }
        return operationManager.start("index", operationId -> {
            IndexResult result = ingestService.indexS3Prefix(
                    normalizedPrefix,
                    operationManager.progressLogger(operationId)
            );
            return toIndexMap(result);
        });
    }

    @PostMapping("/operations/full")
    public AdminOperationSnapshot startFull() {
        return operationManager.start("full", operationId -> {
            FullIngestResult result = ingestService.downloadAndIndex(
                    operationManager.progressLogger(operationId)
            );
            return toFullMap(result);
        });
    }

    @GetMapping("/operations")
    public Map<String, Object> listOperations(@RequestParam(defaultValue = "20") int limit) {
        List<AdminOperationSnapshot> operations = operationManager.list(limit).stream()
                .map(AdminController::toSummary)
                .toList();
        return Map.of(
                "count", operations.size(),
                "items", operations
        );
    }

    @GetMapping("/operations/{operationId}")
    public AdminOperationSnapshot getOperation(@PathVariable String operationId) {
        return operationManager.find(operationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Operacja nie istnieje."));
    }

    @GetMapping(path = "/operations/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamOperations(
            @RequestParam(defaultValue = "30") int limit,
            HttpServletResponse response
    ) {
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("X-Accel-Buffering", "no");

        SseEmitter emitter = new SseEmitter(0L);
        String subscriptionId = operationManager.subscribe(event -> sendStreamEvent(emitter, event));

        emitter.onCompletion(() -> operationManager.unsubscribe(subscriptionId));
        emitter.onTimeout(() -> {
            operationManager.unsubscribe(subscriptionId);
            emitter.complete();
        });
        emitter.onError(error -> operationManager.unsubscribe(subscriptionId));

        if (!sendBootstrapEvent(emitter, limit)) {
            operationManager.unsubscribe(subscriptionId);
        }
        return emitter;
    }

    private static Map<String, Object> toDownloadMap(DownloadResult result) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("tagName", result.tagName());
        map.put("s3Prefix", result.s3Prefix());
        map.put("filesUploaded", result.filesUploaded());
        map.put("bytesUploaded", result.bytesUploaded());
        map.put("durationSeconds", result.duration().getSeconds());
        return map;
    }

    private static Map<String, Object> toIndexMap(IndexResult result) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("runId", result.runId());
        map.put("filesSeen", result.filesSeen());
        map.put("filesProcessed", result.filesProcessed());
        map.put("newVersions", result.newVersions());
        map.put("filesFailed", result.filesFailed());
        map.put("totalBytes", result.totalBytes());
        map.put("durationSeconds", result.duration().getSeconds());
        return map;
    }

    private static Map<String, Object> toFullMap(FullIngestResult result) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("download", toDownloadMap(result.download()));
        map.put("index", toIndexMap(result.index()));
        return map;
    }

    private static AdminOperationSnapshot toSummary(AdminOperationSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        return new AdminOperationSnapshot(
                snapshot.id(),
                snapshot.type(),
                snapshot.status(),
                snapshot.queuedAt(),
                snapshot.startedAt(),
                snapshot.finishedAt(),
                List.of(),
                snapshot.result(),
                snapshot.error()
        );
    }

    private boolean sendBootstrapEvent(SseEmitter emitter, int limit) {
        List<AdminOperationSnapshot> operations = operationManager.list(limit).stream()
                .map(AdminController::toSummary)
                .toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "bootstrap");
        payload.put("serverTime", Instant.now().toString());
        payload.put("operations", operations);

        try {
            emitter.send(SseEmitter.event()
                    .id(UUID.randomUUID().toString())
                    .name("bootstrap")
                    .data(payload));
            return true;
        } catch (IOException ex) {
            emitter.completeWithError(ex);
            return false;
        }
    }

    private void sendStreamEvent(SseEmitter emitter, AdminOperationEvent event) {
        if (event == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", event.type());
        payload.put("serverTime", event.emittedAt());
        if (event.operation() != null) {
            payload.put("operation", event.operation());
        }

        try {
            emitter.send(SseEmitter.event()
                    .id(UUID.randomUUID().toString())
                    .name(event.type())
                    .data(payload));
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to deliver admin stream event.", ex);
        }
    }
}
