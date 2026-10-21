package com.akash.reporting.export;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.time.ZoneOffset;
import com.akash.reporting.model.ReportEvent;

public class ReportCsvWriter {
    private final Writer writer;

    public ReportCsvWriter(OutputStream output) throws IOException {
        writer = new BufferedWriter(new OutputStreamWriter(output, java.nio.charset.StandardCharsets.UTF_8), 65536);
        writer.write("id,occurred_at,reference,amount,status\r\n");
    }

    public void write(ReportEvent event) throws IOException {
        writer.write(Long.toString(event.id())); writer.write(',');
        field(event.occurredAt().toInstant(ZoneOffset.UTC).toString()); writer.write(',');
        field(event.reference()); writer.write(',');
        field(event.amount() == null ? null : event.amount().toPlainString()); writer.write(',');
        field(event.status()); writer.write("\r\n");
    }

    public void flush() throws IOException { writer.flush(); }

    private void field(String value) throws IOException {
        if (value == null) return;
        boolean quote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
        if (quote) writer.write('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '"') writer.write('"');
            writer.write(ch);
        }
        if (quote) writer.write('"');
    }
}
