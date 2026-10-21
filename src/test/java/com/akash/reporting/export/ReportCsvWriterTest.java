package com.akash.reporting.export;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import com.akash.reporting.model.ReportEvent;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ReportCsvWriterTest {
    @Test
    void escapesQuotesNewlinesAndCommasAndWritesUtcAndUnicode() throws Exception {
        var bytes = new ByteArrayOutputStream();
        var csv = new ReportCsvWriter(bytes);
        csv.write(new ReportEvent(42, LocalDateTime.parse("2026-10-01T12:00:00.123456"),
                "café, \"quoted\"\nnext", new BigDecimal("-12.30"), "SETTLED"));
        csv.flush();
        assertThat(bytes.toString(java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(
                "id,occurred_at,reference,amount,status\r\n"
                + "42,2026-10-01T12:00:00.123456Z,\"café, \"\"quoted\"\"\nnext\",-12.30,SETTLED\r\n");
    }

    @Test
    void anEmptyReportStillHasAHeaderAndNullColumnsAreEmpty() throws Exception {
        var bytes = new ByteArrayOutputStream();
        var csv = new ReportCsvWriter(bytes);
        csv.flush();
        assertThat(bytes.toString()).isEqualTo("id,occurred_at,reference,amount,status\r\n");
        csv.write(new ReportEvent(1, LocalDateTime.parse("2026-10-01T00:00:00"), null, null, "PENDING"));
        csv.flush();
        assertThat(bytes.toString()).endsWith("1,2026-10-01T00:00:00Z,,,PENDING\r\n");
    }
}
