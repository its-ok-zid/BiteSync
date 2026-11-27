package com.bitesync.orderservice.saga;

import com.bitesync.common.constants.BiteSyncCommonConstants;
import com.bitesync.orderservice.constants.OrderConstants;
import com.bitesync.orderservice.event.RestaurantOrderDecisionEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class RestaurantEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(RestaurantEventsConsumer.class);

    private final OrderSagaService orderSagaService;
    private final ObjectMapper objectMapper;

    public RestaurantEventsConsumer(
            OrderSagaService orderSagaService,
            ObjectMapper objectMapper) {
        this.orderSagaService = orderSagaService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = BiteSyncCommonConstants.TOPIC_RESTAURANT_EVENTS,
            groupId = OrderConstants.KAFKA_GROUP_SAGA
    )
    public void onRestaurantDecisionEvent(String message) {
        try {
            RestaurantOrderDecisionEvent event =
                    objectMapper.readValue(message, RestaurantOrderDecisionEvent.class);

            log.info("[ORDER SAGA CONSUMED] eventType={} eventId={} orderId={}",
                    event.eventType(), event.eventId(), event.orderId());
            orderSagaService.handleRestaurantDecision(event);

        } catch (Exception ex) {
            log.error("[ORDER SAGA ERROR] Failed processing restaurant decision event: {}",
                    ex.getMessage(), ex);
            throw new IllegalStateException("Failed processing restaurant decision event", ex);
        }
    }
}