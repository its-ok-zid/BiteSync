package com.bitesync.orderservice.outbox;

import com.bitesync.orderservice.model.OutboxEvent;
import com.bitesync.orderservice.model.OutboxStatus;
import com.bitesync.orderservice.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class OutboxRelayScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayScheduler.class);
    static final int BATCH_SIZE = 50;
    static final int MAX_RETRIES = 5;
    private static final long KAFKA_SEND_TIMEOUT_SECONDS = 5;

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public OutboxRelayScheduler(
            OutboxEventRepository outboxEventRepository,
            KafkaTemplate<String, String> kafkaTemplate) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelayString = "${bitesync.outbox.relay.delay-ms:1000}")
    @Transactional
    public void relayPendingEvents() {
        List<OutboxEvent> batch = outboxEventRepository.findPendingBatchForUpdate(BATCH_SIZE);
        if (batch.isEmpty()) {
            return;
        }

        for (OutboxEvent event : batch) {
            try {
                // Partition key = aggregateId (orderId) guarantees strict per-order FIFO ordering
                kafkaTemplate.send(event.getTopic(), event.getAggregateId(), event.getPayload())
                        .get(KAFKA_SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                event.setStatus(OutboxStatus.PUBLISHED);
                event.setPublishedAt(Instant.now());
                log.info("[OUTBOX PUBLISHED] eventId={} type={} topic={} key={}",
                        event.getId(), event.getEventType(), event.getTopic(), event.getAggregateId());

            } catch (Exception ex) {
                int attempts = event.getRetryCount() + 1;
                event.setRetryCount(attempts);
                if (attempts >= MAX_RETRIES) {
                    event.setStatus(OutboxStatus.FAILED);
                    log.error("[OUTBOX FAILED] Max retries ({}) reached for eventId={}: {}",
                            MAX_RETRIES, event.getId(), ex.getMessage());
                } else {
                    log.warn("[OUTBOX RETRY {}/{}] Failed to publish eventId={}: {}",
                            attempts, MAX_RETRIES, event.getId(), ex.getMessage());
                }
            }
        }

        outboxEventRepository.saveAll(batch);
    }
}