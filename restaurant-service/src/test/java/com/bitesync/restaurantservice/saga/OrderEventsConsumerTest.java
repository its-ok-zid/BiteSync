package com.bitesync.restaurantservice.saga;

import com.bitesync.common.constants.BiteSyncCommonConstants;
import com.bitesync.restaurantservice.event.OrderCreatedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderEventsConsumerTest {

    @Mock
    private OrderValidationSagaService sagaService;

    private ObjectMapper objectMapper;
    private OrderEventsConsumer consumer;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder().findAndAddModules().build();
        consumer = new OrderEventsConsumer(sagaService, objectMapper);
    }

    @Test
    @DisplayName("Should deserialize ORDER_CREATED message and delegate to OrderValidationSagaService")
    void onOrderEvent_OrderCreated_DelegatesToService() throws Exception {
        UUID orderId = UUID.randomUUID();
        OrderCreatedEvent event = new OrderCreatedEvent(
                "evt-100", BiteSyncCommonConstants.EVENT_ORDER_CREATED,
                orderId, UUID.randomUUID(), "65f1a29b4c8e12001a9c4b11",
                List.of(new OrderCreatedEvent.OrderItemPayload("item-101", 2)),
                Instant.now()
        );
        String json = objectMapper.writeValueAsString(event);

        consumer.onOrderEvent(json);

        ArgumentCaptor<OrderCreatedEvent> captor = ArgumentCaptor.forClass(OrderCreatedEvent.class);
        verify(sagaService, times(1)).processOrderCreated(captor.capture());
        assertEquals("evt-100", captor.getValue().eventId());
        assertEquals(orderId, captor.getValue().orderId());
    }

    @Test
    @DisplayName("Should ignore ORDER_CONFIRMED and ORDER_CANCELLED events on canonical topic")
    void onOrderEvent_NonOrderCreatedEvents_Ignored() {
        consumer.onOrderEvent("{\"eventId\":\"e1\",\"eventType\":\"ORDER_CONFIRMED\"}");
        consumer.onOrderEvent("{\"eventId\":\"e2\",\"eventType\":\"ORDER_CANCELLED\"}");

        verifyNoInteractions(sagaService);
    }

    @Test
    @DisplayName("Should throw IllegalStateException on malformed JSON so Kafka does not silently ack")
    void onOrderEvent_CorruptJson_ThrowsIllegalState() {
        assertThrows(IllegalStateException.class, () -> consumer.onOrderEvent("{corrupt-json"));
        verifyNoInteractions(sagaService);
    }
}