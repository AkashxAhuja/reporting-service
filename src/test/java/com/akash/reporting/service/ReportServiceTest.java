package com.akash.reporting.service;

import com.akash.reporting.config.ReportProperties;
import com.akash.reporting.dto.CreateReportRequest;
import com.akash.reporting.exception.InvalidReportRequestException;
import com.akash.reporting.model.ReportJob;
import com.akash.reporting.model.ReportStatus;
import com.akash.reporting.repository.ReportJobRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReportServiceTest {

    private ReportJobRepository repository;
    private ReportService service;

    @BeforeEach
    void setUp() {
        repository = mock(ReportJobRepository.class);
        var clock = Clock.fixed(Instant.parse("2026-10-19T08:00:00.123456Z"), ZoneOffset.UTC);
        service = new ReportService(repository, new ReportProperties(731), clock);
    }

    @Test
    void insertsJobAndPendingDispatchInTheSameDocument() {
        when(repository.insert(any(ReportJob.class))).thenAnswer(call -> call.getArgument(0));

        var result = service.create(request("2026-10-01", "2026-10-02"));

        var inserted = ArgumentCaptor.forClass(ReportJob.class);
        verify(repository).insert(inserted.capture());
        assertThat(result).isSameAs(inserted.getValue());
        assertThat(UUID.fromString(result.id())).isNotNull();
        assertThat(result.startInclusive()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(result.endExclusive()).isEqualTo(Instant.parse("2026-10-02T00:00:00Z"));
        assertThat(result.submittedAt()).isEqualTo(Instant.parse("2026-10-19T08:00:00.123Z"));
        assertThat(result.status()).isEqualTo(ReportStatus.QUEUED);
        assertThat(result.format()).isEqualTo("CSV");
        assertThat(result.dispatchPending()).isTrue();
        assertThat(result.dispatch().attemptCount()).isZero();
        assertThat(result.attemptCount()).isZero();
        assertThat(result.firstStartedAt()).isNull();
        assertThat(result.completedAt()).isNull();
    }

    @ParameterizedTest
    @CsvSource({
            "2026-10-01, 2026-10-01",
            "2026-10-02, 2026-10-01",
            "2024-01-01, 2026-01-02"
    })
    void rejectsInvalidRangesWithoutWritingToMongo(String start, String end) {
        assertThatThrownBy(() -> service.create(request(start, end)))
                .isInstanceOf(InvalidReportRequestException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void acceptsTheConfiguredMaximumIncludingLeapDay() {
        when(repository.insert(any(ReportJob.class))).thenAnswer(call -> call.getArgument(0));

        assertThat(service.create(request("2024-01-01", "2026-01-01"))).isNotNull();
        verify(repository).insert(any(ReportJob.class));
    }

    @Test
    void appliesAChangedRangeLimit() {
        service = new ReportService(repository, new ReportProperties(1), Clock.systemUTC());

        assertThatThrownBy(() -> service.create(request("2026-10-01", "2026-10-03")))
                .isInstanceOf(InvalidReportRequestException.class)
                .hasMessage("Date range must not exceed 1 day(s).");
        verifyNoInteractions(repository);
    }

    @Test
    void propagatesAnUnconfirmedInsertInsteadOfReturningAJob() {
        var failure = new DataAccessResourceFailureException("MongoDB unavailable");
        when(repository.insert(any(ReportJob.class))).thenThrow(failure);

        assertThatThrownBy(() -> service.create(request("2026-10-01", "2026-10-02")))
                .isSameAs(failure);
    }

    private CreateReportRequest request(String start, String end) {
        return new CreateReportRequest(LocalDate.parse(start), LocalDate.parse(end));
    }
}
