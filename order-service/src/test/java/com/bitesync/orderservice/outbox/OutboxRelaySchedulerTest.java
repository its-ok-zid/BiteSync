package com.bitesync.orderservice.outbox;

import com.bitesync.common.constants.BiteSyncCommonConstants;
import com.bitesync.orderservice.model.OutboxEvent;
import com.bitesync.orderservice.model.OutboxStatus;
import com.bitesync.orderservice.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxRelaySchedulerTest {

    @Mock private OutboxEventRepository outboxEventRepository;
    @Mock private KafkaTemplate<String, String> kafkaTemplate;

    private OutboxRelayScheduler relayScheduler;

    @BeforeEach
    void setUp() {
        relayScheduler = new OutboxRelayScheduler(outboxEventRepository, kafkaTemplate);
    }

    @Test
    @DisplayName("Empty Batch -> Returns immediately without touching Kafka or saveAll")
    void relayPendingEvents_EmptyBatch_DoesNothing() {
        when(outboxEventRepository.findPendingBatchForUpdate(OutboxRelayScheduler.BATCH_SIZE))
                .thenReturn(List.of());

        relayScheduler.relayPendingEvents();

        verifyNoInteractions(kafkaTemplate);
        verify(outboxEventRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("Success -> Publishes to Kafka keyed by aggregateId and flips status to PUBLISHED")
    void relayPendingEvents_KafkaSuccess_MarksPublished() {
        OutboxEvent event = buildPendingEvent(0);
        when(outboxEventRepository.findPendingBatchForUpdate(OutboxRelayScheduler.BATCH_SIZE))
                .thenReturn(List.of(event));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        relayScheduler.relayPendingEvents();

        verify(kafkaTemplate, times(1))
                .send(event.getTopic(), event.getAggregateId(), event.getPayload());
        assertEquals(OutboxStatus.PUBLISHED, event.getStatus());
        assertNotNull(event.getPublishedAt());
        verify(outboxEventRepository, times(1)).saveAll(List.of(event));
    }

    @Test
    @DisplayName("Transient Failure -> Increments retryCount and keeps status PENDING for next poll")
    void relayPendingEvents_TransientFailure_IncrementsRetryAndKeepsPending() {
        OutboxEvent event = buildPendingEvent(1);
        when(outboxEventRepository.findPendingBatchForUpdate(OutboxRelayScheduler.BATCH_SIZE))
                .thenReturn(List.of(event));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Broker unavailable")));

        relayScheduler.relayPendingEvents();

        assertEquals(OutboxStatus.PENDING, event.getStatus());
        assertEquals(2, event.getRetryCount());
        assertNull(event.getPublishedAt());
        verify(outboxEventRepository, times(1)).saveAll(List.of(event));
    }

    @Test
    @DisplayName("Poison-Pill Protection -> Marks status FAILED when retryCount reaches MAX_RETRIES (5)")
    void relayPendingEvents_MaxRetriesReached_MarksFailed() {
        OutboxEvent event = buildPendingEvent(OutboxRelayScheduler.MAX_RETRIES - 1);
        when(outboxEventRepository.findPendingBatchForUpdate(OutboxRelayScheduler.BATCH_SIZE))
                .thenReturn(List.of(event));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Serialization/broker failure")));

        relayScheduler.relayPendingEvents();

        assertEquals(OutboxStatus.FAILED, event.getStatus());
        assertEquals(OutboxRelayScheduler.MAX_RETRIES, event.getRetryCount());
        verify(outboxEventRepository, times(1)).saveAll(List.of(event));
    }

    private OutboxEvent buildPendingEvent(int retryCount) {
        OutboxEvent event = new OutboxEvent();
        event.setId(UUID.randomUUID());
        event.setAggregateType("ORDER");
        event.setAggregateId(UUID.randomUUID().toString());
        event.setEventType(BiteSyncCommonConstants.EVENT_ORDER_CREATED);
        event.setTopic(BiteSyncCommonConstants.TOPIC_ORDER_EVENTS);
        event.setPayload("{\"eventType\":\"ORDER_CREATED\"}");
        event.setStatus(OutboxStatus.PENDING);
        event.setRetryCount(retryCount);
        event.setCreatedAt(Instant.now());
        return event;
    }
}