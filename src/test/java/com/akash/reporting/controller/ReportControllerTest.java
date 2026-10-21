package com.akash.reporting.controller;

import com.akash.reporting.exception.InvalidReportRequestException;
import com.akash.reporting.exception.ReportNotFoundException;
import com.akash.reporting.model.ReportJob;
import com.akash.reporting.model.DispatchMetadata;
import com.akash.reporting.model.ReportStatus;
import com.akash.reporting.service.ReportService;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReportController.class)
class ReportControllerTest {

    private static final UUID REPORT_ID = UUID.fromString("778048b2-3b55-4318-b8d1-a7dc4225bc2b");
    private static final String VALID_REQUEST = """
            {"startDate":"2026-10-01","endDate":"2026-10-02"}
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ReportService service;

    @MockitoBean
    private com.akash.reporting.service.ReportDownloadService downloads;

    @Test
    void returnsAcceptedWithStatusLocationAndInitialMetadata() throws Exception {
        when(service.create(any())).thenReturn(queuedJob());

        mvc.perform(post("/api/v1/reports").contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/v1/reports/" + REPORT_ID))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.reportId").value(REPORT_ID.toString()))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.format").value("CSV"))
                .andExpect(jsonPath("$.attemptCount").value(0))
                .andExpect(jsonPath("$.retryCount").value(0))
                .andExpect(jsonPath("$.submittedAt").value("2026-10-19T08:00:00Z"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"startDate\":\"2026-10-01\"}",
            "{\"startDate\":null,\"endDate\":\"2026-10-02\"}",
            "{\"startDate\":\"2026-02-30\",\"endDate\":\"2026-10-02\"}",
            "{\"startDate\":\"2026-10-01\",\"endDate\":\"2026-10-02\",\"filter\":\"ignored?\"}",
            "{",
            "null"
    })
    void rejectsMalformedOrIncompleteRequestsBeforeCallingTheService(String body) throws Exception {
        mvc.perform(post("/api/v1/reports").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void includesMissingFieldsInValidationErrors() throws Exception {
        mvc.perform(post("/api/v1/reports").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.startDate").value("must not be null"))
                .andExpect(jsonPath("$.errors.endDate").value("must not be null"));
    }

    @Test
    void mapsInvalidDateRangesToBadRequest() throws Exception {
        when(service.create(any())).thenThrow(new InvalidReportRequestException("Invalid range."));

        mvc.perform(post("/api/v1/reports").contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Invalid range."));
    }

    @Test
    void returnsStoredStatus() throws Exception {
        when(service.get(REPORT_ID)).thenReturn(queuedJob());

        mvc.perform(get("/api/v1/reports/{id}", REPORT_ID))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.startDate").value("2026-10-01"))
                .andExpect(jsonPath("$.endDate").value("2026-10-02"))
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    void returnsNotFoundForAnUnknownReport() throws Exception {
        when(service.get(REPORT_ID)).thenThrow(new ReportNotFoundException());

        mvc.perform(get("/api/v1/reports/{id}", REPORT_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsMalformedIds() throws Exception {
        mvc.perform(get("/api/v1/reports/not-a-uuid")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void doesNotReturnAcceptedWhenStorageCannotConfirmTheWrite() throws Exception {
        when(service.create(any())).thenThrow(new DataAccessResourceFailureException("internal details"));

        mvc.perform(post("/api/v1/reports").contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.detail").value(
                        "Report metadata storage is unavailable. The operation could not be confirmed."));
    }

    @Test
    void returnsSignedDownloadWithNoStore() throws Exception {
        var expires = Instant.parse("2026-10-20T00:05:00Z");
        when(downloads.download(REPORT_ID)).thenReturn(new com.akash.reporting.dto.ReportDownloadResponse(
                java.net.URI.create("http://localhost:8333/reports/file.csv?signature=example"), expires, "report.csv"));
        mvc.perform(get("/api/v1/reports/{id}/download", REPORT_ID))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.fileName").value("report.csv"));
    }

    @Test
    void unavailableDownloadIsConflictAndUnknownDownloadIsNotFound() throws Exception {
        when(downloads.download(REPORT_ID)).thenThrow(new com.akash.reporting.exception.ReportNotReadyException());
        mvc.perform(get("/api/v1/reports/{id}/download", REPORT_ID)).andExpect(status().isConflict());
        org.mockito.Mockito.doThrow(new ReportNotFoundException()).when(downloads).download(REPORT_ID);
        mvc.perform(get("/api/v1/reports/{id}/download", REPORT_ID)).andExpect(status().isNotFound());
    }

    private ReportJob queuedJob() {
        return new ReportJob(REPORT_ID.toString(),
                Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-02T00:00:00Z"),
                "CSV", ReportStatus.QUEUED, Instant.parse("2026-10-19T08:00:00Z"),
                null, null, 0, true, DispatchMetadata.pending(), null, null, null);
    }
}
