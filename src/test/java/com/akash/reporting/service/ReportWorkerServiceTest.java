package com.akash.reporting.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.*;
import java.util.*;
import com.akash.reporting.config.WorkerProperties;
import com.akash.reporting.model.*;
import com.akash.reporting.repository.*;
import com.akash.reporting.storage.ReportStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReportWorkerServiceTest {
    private final Instant now = Instant.parse("2026-10-20T00:00:00Z");
    private final WorkerClaim claim = new WorkerClaim(UUID.randomUUID().toString(), "attempt-token",
            now.minusSeconds(86400), now);
    private ReportWorkerRepository workers;
    private ReportJobRepository jobs;
    private ReportEventRepository events;
    private ReportStorage storage;
    private ReportWorkerService service;
    private MemoryUpload upload;

    @BeforeEach
    void setup() {
        workers = mock(ReportWorkerRepository.class); jobs = mock(ReportJobRepository.class);
        events = mock(ReportEventRepository.class); storage = mock(ReportStorage.class);
        var properties = new WorkerProperties(true, 2, Duration.ofSeconds(60), Duration.ofSeconds(10),
                Duration.ofMinutes(10), 30, "jdbc:mysql://localhost/reporting", "reader", "password");
        service = new ReportWorkerService(workers, jobs, events, storage, properties, Clock.fixed(now, ZoneOffset.UTC));
        when(workers.claim(eq(claim.reportId()), any(), any())).thenReturn(Optional.of(claim));
        when(workers.complete(eq(claim), any(), any())).thenReturn(true);
        when(workers.fail(eq(claim), any(), any())).thenReturn(true);
        upload = new MemoryUpload();
        when(storage.begin(anyString())).thenReturn(upload);
    }

    @Test
    void writesBatchesAndCompletesOnlyAfterTheUpload() {
        var first = new ReportEvent(1, LocalDateTime.parse("2026-10-19T00:00:00"), "First", null, "SETTLED");
        when(events.nextBatch(claim.startInclusive(), claim.endExclusive(), null)).thenReturn(List.of(first));
        when(events.nextBatch(claim.startInclusive(), claim.endExclusive(), first)).thenReturn(List.of());
        when(workers.complete(eq(claim), any(), any())).thenAnswer(call -> {
            assertThat(upload.completed).isTrue(); return true;
        });
        assertThat(service.process(claim.reportId())).isTrue();
        var artifact = ArgumentCaptor.forClass(ReportArtifact.class);
        verify(workers).complete(eq(claim), eq(now), artifact.capture());
        assertThat(artifact.getValue().rowCount()).isEqualTo(1);
        assertThat(artifact.getValue().byteSize()).isEqualTo(upload.bytes.size());
        assertThat(artifact.getValue().objectKey()).endsWith("/attempt-token.csv");
        verify(storage, never()).delete(anyString());
    }

    @Test
    void sourceFailureIsSavedBeforeAcknowledgementCanBeAllowed() {
        when(events.nextBatch(any(), any(), any())).thenThrow(new DataAccessResourceFailureException("MySQL failed"));
        assertThat(service.process(claim.reportId())).isTrue();
        verify(workers).fail(eq(claim), eq(now), any());
        verify(workers, never()).complete(any(), any(), any());
        assertThat(upload.closed).isTrue(); assertThat(upload.completed).isFalse();
        verify(storage).delete(anyString());
    }

    @Test
    void unconfirmedCompletionPreservesTheUploadedObjectAndRequiresRedelivery() {
        when(events.nextBatch(any(), any(), any())).thenReturn(List.of());
        when(workers.complete(any(), any(), any())).thenThrow(new DataAccessResourceFailureException("Mongo timeout"));
        assertThatThrownBy(() -> service.process(claim.reportId())).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(upload.completed).isTrue();
        verify(storage, never()).delete(anyString());
        verify(workers, never()).fail(any(), any(), any());
    }

    @Test
    void staleOwnerCannotPublishCompletionAndRemovesOnlyItsOwnObject() {
        when(events.nextBatch(any(), any(), any())).thenReturn(List.of());
        when(workers.complete(any(), any(), any())).thenReturn(false);
        assertThat(service.process(claim.reportId())).isFalse();
        verify(storage).delete("reports/" + claim.reportId() + "/attempt-token.csv");
    }

    @Test
    void duplicateOfACompletedJobDoesNotQueryOrUpload() {
        when(workers.claim(any(), any(), any())).thenReturn(Optional.empty());
        var completed = mock(ReportJob.class); when(completed.status()).thenReturn(ReportStatus.COMPLETED);
        when(jobs.findById(claim.reportId())).thenReturn(Optional.of(completed));
        assertThat(service.process(claim.reportId())).isTrue();
        verifyNoInteractions(events, storage);
    }

    @Test
    void activeOwnerKeepsTheDuplicateAvailableForRedelivery() {
        when(workers.claim(any(), any(), any())).thenReturn(Optional.empty());
        var running = mock(ReportJob.class); when(running.status()).thenReturn(ReportStatus.RUNNING);
        when(jobs.findById(claim.reportId())).thenReturn(Optional.of(running));
        assertThat(service.process(claim.reportId())).isFalse();
        verifyNoInteractions(events, storage);
    }

    private static final class MemoryUpload extends ReportStorage.Upload {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        boolean completed; boolean closed;
        @Override public void write(int value) { bytes.write(value); }
        @Override public void write(byte[] b, int off, int len) { bytes.write(b, off, len); }
        @Override public long complete() { completed = true; return bytes.size(); }
        @Override public void close() { closed = true; }
    }
}
