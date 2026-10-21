package com.akash.reporting.repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import com.akash.reporting.config.WorkerProperties;
import com.akash.reporting.model.ReportEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(prefix = "reporting.worker", name = "enabled", havingValue = "true")
public class ReportEventRepository {
    private final JdbcTemplate jdbc;
    private final WorkerProperties properties;

    public ReportEventRepository(JdbcTemplate jdbc, WorkerProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    public List<ReportEvent> nextBatch(Instant start, Instant end, ReportEvent after) {
        String sql = "SELECT id, occurred_at, reference, amount, status FROM report_events "
                + "WHERE occurred_at >= ? AND occurred_at < ? "
                + (after == null ? "" : "AND (occurred_at > ? OR (occurred_at = ? AND id > ?)) ")
                + "ORDER BY occurred_at, id LIMIT ?";
        return jdbc.query(sql, statement -> {
            statement.setObject(1, LocalDateTime.ofInstant(start, ZoneOffset.UTC));
            statement.setObject(2, LocalDateTime.ofInstant(end, ZoneOffset.UTC));
            int index = 3;
            if (after != null) {
                statement.setObject(index++, after.occurredAt());
                statement.setObject(index++, after.occurredAt());
                statement.setLong(index++, after.id());
            }
            statement.setInt(index, properties.batchSize());
        }, (row, number) -> new ReportEvent(row.getLong("id"), row.getObject("occurred_at", LocalDateTime.class),
                row.getString("reference"), row.getBigDecimal("amount"), row.getString("status")));
    }
}
