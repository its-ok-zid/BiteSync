package com.bitesync.restaurantservice.model;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "processed_events")
public class ProcessedOrderEvent {

    @Id
    private String eventId;

    private String orderId;
    private String outcome;

    @CreatedDate
    private Instant processedAt;

    public ProcessedOrderEvent() {
    }

    public ProcessedOrderEvent(String eventId, String orderId, String outcome, Instant processedAt) {
        this.eventId = eventId;
        this.orderId = orderId;
        this.outcome = outcome;
        this.processedAt = processedAt;
    }

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }

    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }

    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }
}