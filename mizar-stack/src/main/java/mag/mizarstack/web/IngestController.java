package mag.mizarstack.web;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mag.mizarstack.ingest.IngestService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/ingest")
@RequiredArgsConstructor
public class IngestController {

    private final IngestService ingest;
    private final JdbcClient db;

    @PostMapping("/run")
    public Map<String,Object> run() { return ingest.runIngest(); }

    @GetMapping("/stats/latest")
    public Map<String,Object> latest() {
        return db.sql("""
      select id, started_at, finished_at, files_seen, files_downloaded, versions_added, bytes_downloaded
      from ingest_run order by id desc limit 1
      """).query(rs -> rs.next()
                ? Map.of(
                "runId", rs.getLong("id"),
                "startedAt", rs.getTimestamp("started_at"),
                "finishedAt", rs.getTimestamp("finished_at"),
                "filesSeen", rs.getInt("files_seen"),
                "filesDownloaded", rs.getInt("files_downloaded"),
                "versionsAdded", rs.getInt("versions_added"),
                "bytesDownloaded", rs.getLong("bytes_downloaded"))
                : Map.of("message", "no runs yet"));
    }
}
