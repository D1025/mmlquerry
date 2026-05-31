package mag.mizarstack.admin;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminOperationManager {

    private static final int MAX_LOG_LINES = 2_500;
    private static final int MAX_OPERATIONS = 60;
    private static final long HEARTBEAT_INTERVAL_SECONDS = 20L;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "admin-ingest-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService streamHeartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "admin-stream-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    private final Map<String, OperationState> operations = new ConcurrentHashMap<>();
    private final Deque<String> order = new ConcurrentLinkedDeque<>();
    private final Map<String, Consumer<AdminOperationEvent>> subscribers = new ConcurrentHashMap<>();

    @FunctionalInterface
    public interface AdminOperationAction {
        Map<String, Object> run(String operationId) throws Exception;
    }

    @PostConstruct
    void startHeartbeat() {
        streamHeartbeatExecutor.scheduleAtFixedRate(
                this::publishHeartbeat,
                HEARTBEAT_INTERVAL_SECONDS,
                HEARTBEAT_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );
    }

    public AdminOperationSnapshot start(String type, AdminOperationAction action) {
        String normalizedType = normalizeType(type);
        String id = UUID.randomUUID().toString();
        OperationState state = new OperationState(id, normalizedType);
        operations.put(id, state);
        order.addFirst(id);
        trimOldOperations();
        publishOperationUpdate(state.snapshot());

        executor.submit(() -> executeOperation(state, action));
        return state.snapshot();
    }

    public String subscribe(Consumer<AdminOperationEvent> subscriber) {
        if (subscriber == null) {
            throw new IllegalArgumentException("Subscriber is required.");
        }
        String subscriptionId = UUID.randomUUID().toString();
        subscribers.put(subscriptionId, subscriber);
        return subscriptionId;
    }

    public void unsubscribe(String subscriptionId) {
        if (subscriptionId == null || subscriptionId.isBlank()) {
            return;
        }
        subscribers.remove(subscriptionId);
    }

    public Consumer<String> progressLogger(String operationId) {
        return message -> appendLog(operationId, message);
    }

    public Optional<AdminOperationSnapshot> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        OperationState state = operations.get(id.trim());
        if (state == null) {
            return Optional.empty();
        }
        return Optional.of(state.snapshot());
    }

    public List<AdminOperationSnapshot> list(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_OPERATIONS));
        List<AdminOperationSnapshot> snapshots = new ArrayList<>(safeLimit);
        int added = 0;
        for (String id : order) {
            if (added >= safeLimit) {
                break;
            }
            OperationState state = operations.get(id);
            if (state == null) {
                continue;
            }
            snapshots.add(state.snapshot());
            added++;
        }
        return snapshots;
    }

    public void appendLog(String operationId, String message) {
        if (operationId == null || operationId.isBlank() || message == null || message.isBlank()) {
            return;
        }
        OperationState state = operations.get(operationId);
        if (state == null) {
            return;
        }
        state.appendLog(message);
        publishOperationUpdate(state.snapshot());
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
        streamHeartbeatExecutor.shutdownNow();
        subscribers.clear();
    }

    private void executeOperation(OperationState state, AdminOperationAction action) {
        state.setStatus(AdminOperationStatus.RUNNING);
        state.setStartedAt(Instant.now());
        publishOperationUpdate(state.snapshot());
        appendLog(state.id, "Operation started.");

        try {
            Map<String, Object> result = action.run(state.id);
            state.setResult(result == null ? Map.of() : result);
            state.setStatus(AdminOperationStatus.SUCCESS);
            publishOperationUpdate(state.snapshot());
            appendLog(state.id, "Operation completed successfully.");
        } catch (Exception ex) {
            String error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            state.setError(error);
            state.setStatus(AdminOperationStatus.FAILED);
            publishOperationUpdate(state.snapshot());
            appendLog(state.id, "Operation failed: " + error);
            log.error("Admin operation {} failed", state.id, ex);
        } finally {
            state.setFinishedAt(Instant.now());
            publishOperationUpdate(state.snapshot());
        }
    }

    private void trimOldOperations() {
        while (order.size() > MAX_OPERATIONS) {
            String removed = order.pollLast();
            if (removed == null) {
                break;
            }
            operations.remove(removed);
        }
    }

    private static String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            return "unknown";
        }
        return type.trim().toLowerCase(Locale.ROOT);
    }

    private void publishOperationUpdate(AdminOperationSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        publishEvent(AdminOperationEvent.operation(snapshot));
    }

    private void publishHeartbeat() {
        publishEvent(AdminOperationEvent.heartbeat());
    }

    private void publishEvent(AdminOperationEvent event) {
        if (event == null || subscribers.isEmpty()) {
            return;
        }
        List<String> staleSubscribers = new ArrayList<>();
        subscribers.forEach((subscriptionId, subscriber) -> {
            try {
                subscriber.accept(event);
            } catch (RuntimeException ex) {
                staleSubscribers.add(subscriptionId);
                log.debug("Removing stale admin stream subscriber {}", subscriptionId, ex);
            }
        });
        for (String staleSubscriber : staleSubscribers) {
            subscribers.remove(staleSubscriber);
        }
    }

    private static final class OperationState {
        private final String id;
        private final String type;
        private volatile AdminOperationStatus status;
        private final Instant queuedAt;
        private volatile Instant startedAt;
        private volatile Instant finishedAt;
        private final List<String> logs;
        private volatile Map<String, Object> result;
        private volatile String error;

        private OperationState(String id, String type) {
            this.id = id;
            this.type = type;
            this.status = AdminOperationStatus.QUEUED;
            this.queuedAt = Instant.now();
            this.logs = Collections.synchronizedList(new ArrayList<>());
            this.result = Map.of();
            this.error = null;
        }

        private void appendLog(String message) {
            String line = "[" + Instant.now() + "] " + message;
            synchronized (logs) {
                logs.add(line);
                if (logs.size() > MAX_LOG_LINES) {
                    int remove = logs.size() - MAX_LOG_LINES;
                    for (int i = 0; i < remove; i++) {
                        logs.remove(0);
                    }
                }
            }
        }

        private void setStatus(AdminOperationStatus status) {
            this.status = status;
        }

        private void setStartedAt(Instant startedAt) {
            this.startedAt = startedAt;
        }

        private void setFinishedAt(Instant finishedAt) {
            this.finishedAt = finishedAt;
        }

        private void setResult(Map<String, Object> result) {
            this.result = result;
        }

        private void setError(String error) {
            this.error = error;
        }

        private AdminOperationSnapshot snapshot() {
            List<String> logsCopy;
            synchronized (logs) {
                logsCopy = List.copyOf(logs);
            }
            Map<String, Object> resultCopy = result == null ? Map.of() : new LinkedHashMap<>(result);
            return new AdminOperationSnapshot(
                    id,
                    type,
                    status,
                    queuedAt,
                    startedAt,
                    finishedAt,
                    logsCopy,
                    resultCopy,
                    error
            );
        }
    }
}
