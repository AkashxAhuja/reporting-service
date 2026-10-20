package com.akash.reporting.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import com.akash.reporting.config.DispatchProperties;
import com.akash.reporting.exception.ReportPublishException;
import com.akash.reporting.messaging.ReportQueuePublisher;
import com.akash.reporting.model.DispatchClaim;
import com.akash.reporting.repository.ReportDispatchRepository;
import com.akash.reporting.support.DispatchTestSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReportDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");
    private final DispatchClaim claim = new DispatchClaim("report-1", "claim-1");
    private ReportDispatchRepository repository;
    private ReportQueuePublisher publisher;
    private DispatchProperties properties;
    private ReportDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        repository = mock(ReportDispatchRepository.class);
        publisher = mock(ReportQueuePublisher.class);
        properties = DispatchTestSettings.defaults();
        dispatcher = new ReportDispatcher(repository, publisher, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void recordsPublicationOnlyAfterThePublisherReturnsSuccessfully() {
        when(repository.claimNext(NOW, properties.leaseDuration())).thenReturn(Optional.of(claim), Optional.empty());
        when(repository.markPublished(claim, NOW)).thenReturn(true);

        dispatcher.dispatchPendingJobs();

        var order = inOrder(repository, publisher);
        order.verify(repository).claimNext(NOW, properties.leaseDuration());
        order.verify(publisher).publish(claim.reportId());
        order.verify(repository).markPublished(claim, NOW);
        verify(repository, never()).reschedule(any(), any(), any());
    }

    @Test
    void failedPublicationIsRescheduledAndEndsThisPollingCycle() {
        when(repository.claimNext(NOW, properties.leaseDuration())).thenReturn(Optional.of(claim));
        doThrow(new ReportPublishException("No confirmation.")).when(publisher).publish(claim.reportId());

        dispatcher.dispatchPendingJobs();

        verify(repository).reschedule(claim, NOW.plus(properties.retryDelay()), "No confirmation.");
        verify(repository, never()).markPublished(any(), any());
        verify(repository, times(1)).claimNext(any(), any());
    }

    @Test
    void metadataFailureAfterConfirmationLeavesRecoveryToTheLease() {
        when(repository.claimNext(NOW, properties.leaseDuration())).thenReturn(Optional.of(claim));
        when(repository.markPublished(claim, NOW)).thenThrow(new DataAccessResourceFailureException("unconfirmed update"));

        dispatcher.dispatchPendingJobs();

        verify(publisher).publish(claim.reportId());
        verify(repository, never()).reschedule(any(), any(), any());
        verify(repository, times(1)).claimNext(any(), any());
    }

    @Test
    void doesNotPublishWhenNoJobCanBeClaimed() {
        when(repository.claimNext(any(), any())).thenReturn(Optional.empty());

        dispatcher.dispatchPendingJobs();

        verifyNoInteractions(publisher);
        verify(repository, never()).markPublished(any(), any());
    }

    @Test
    void processesAtMostTheConfiguredNumberOfJobsPerPoll() {
        properties = new DispatchProperties(true, properties.pollInterval(), properties.leaseDuration(),
                properties.confirmTimeout(), properties.retryDelay(), 2,
                properties.exchange(), properties.queue(), properties.routingKey());
        dispatcher = new ReportDispatcher(repository, publisher, properties, Clock.fixed(NOW, ZoneOffset.UTC));
        when(repository.claimNext(any(), any())).thenReturn(Optional.of(claim));

        dispatcher.dispatchPendingJobs();

        verify(publisher, times(2)).publish(claim.reportId());
        verify(repository, times(2)).claimNext(any(), any());
    }
}
