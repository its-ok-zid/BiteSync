package com.bitesync.orderservice.cqrs;

import com.bitesync.common.constants.BiteSyncCommonConstants;
import com.bitesync.orderservice.constants.OrderConstants;
import com.bitesync.orderservice.event.OrderCreatedEvent;
import com.bitesync.orderservice.event.OrderStatusChangedEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class OrderReadModelProjector {

    private static final Logger log = LoggerFactory.getLogger(OrderReadModelProjector.class);

    private final OrderProjectionService orderProjectionService;
    private final ObjectMapper objectMapper;

    public OrderReadModelProjector(
            OrderProjectionService orderProjectionService,
            ObjectMapper objectMapper) {
        this.orderProjectionService = orderProjectionService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = BiteSyncCommonConstants.TOPIC_ORDER_EVENTS,
            groupId = OrderConstants.KAFKA_GROUP_PROJECTOR
    )
    public void onOrderLifecycleEvent(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            String eventType = root.path("eventType").asText("");

            switch (eventType) {
                case BiteSyncCommonConstants.EVENT_ORDER_CREATED -> {
                    OrderCreatedEvent event = objectMapper.treeToValue(root, OrderCreatedEvent.class);
                    orderProjectionService.projectOrderCreated(event);
                }
                case BiteSyncCommonConstants.EVENT_ORDER_CONFIRMED,
                     BiteSyncCommonConstants.EVENT_ORDER_CANCELLED -> {
                    OrderStatusChangedEvent event = objectMapper.treeToValue(root, OrderStatusChangedEvent.class);
                    orderProjectionService.projectOrderStatusChanged(event);
                }
                default -> log.warn("[CQRS PROJECTOR WARN] Ignoring unknown eventType={}", eventType);
            }
        } catch (Exception ex) {
            log.error("[CQRS PROJECTOR ERROR] Failed projecting order lifecycle event: {}",
                    ex.getMessage(), ex);
            throw new IllegalStateException("Failed projecting order lifecycle event", ex);
        }
    }
}