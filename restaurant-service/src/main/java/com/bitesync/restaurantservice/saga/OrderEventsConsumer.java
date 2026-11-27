package com.bitesync.restaurantservice.saga;

import com.bitesync.common.constants.BiteSyncCommonConstants;
import com.bitesync.restaurantservice.constants.RestaurantConstants;
import com.bitesync.restaurantservice.event.OrderCreatedEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class OrderEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventsConsumer.class);

    private final OrderValidationSagaService orderValidationSagaService;
    private final ObjectMapper objectMapper;

    public OrderEventsConsumer(
            OrderValidationSagaService orderValidationSagaService,
            ObjectMapper objectMapper) {
        this.orderValidationSagaService = orderValidationSagaService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = BiteSyncCommonConstants.TOPIC_ORDER_EVENTS,
            groupId = RestaurantConstants.KAFKA_GROUP_SAGA
    )
    public void onOrderEvent(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            String eventType = root.path("eventType").asText("");

            // Ignore ORDER_CONFIRMED / ORDER_CANCELLED events published on the same canonical topic
            if (!BiteSyncCommonConstants.EVENT_ORDER_CREATED.equals(eventType)) {
                return;
            }

            OrderCreatedEvent event = objectMapper.treeToValue(root, OrderCreatedEvent.class);
            log.info("[SAGA CONSUMED] eventType={} eventId={} orderId={}",
                    event.eventType(), event.eventId(), event.orderId());
            orderValidationSagaService.processOrderCreated(event);

        } catch (Exception ex) {
            log.error("[SAGA ERROR] Failed processing order event: {}", ex.getMessage(), ex);
            throw new IllegalStateException("Failed processing order event", ex);
        }
    }
}