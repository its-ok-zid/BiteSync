package com.bitesync.orderservice.saga;

import com.bitesync.common.constants.BiteSyncCommonConstants;
import com.bitesync.orderservice.event.RestaurantOrderDecisionEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RestaurantEventsConsumerTest {

    @Mock private OrderSagaService orderSagaService;

    private ObjectMapper objectMapper;
    private RestaurantEventsConsumer consumer;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder().findAndAddModules().build();
        consumer = new RestaurantEventsConsumer(orderSagaService, objectMapper);
    }

    @Test
    @DisplayName("Should deserialize decision JSON and delegate to OrderSagaService")
    void onRestaurantDecisionEvent_ValidJson_Delegates() throws Exception {
        UUID orderId = UUID.randomUUID();
        RestaurantOrderDecisionEvent event = new RestaurantOrderDecisionEvent(
                "dec-10", BiteSyncCommonConstants.EVENT_RESTAURANT_ORDER_ACCEPTED,
                orderId, "65f1a29b4c8e12001a9c4b11", "Spice Route",
                List.of(), new BigDecimal("698.00"), null, Instant.now()
        );

        consumer.onRestaurantDecisionEvent(objectMapper.writeValueAsString(event));

        ArgumentCaptor<RestaurantOrderDecisionEvent> captor =
                ArgumentCaptor.forClass(RestaurantOrderDecisionEvent.class);
        verify(orderSagaService, times(1)).handleRestaurantDecision(captor.capture());
        assertEquals("dec-10", captor.getValue().eventId());
        assertEquals(orderId, captor.getValue().orderId());
    }

    @Test
    @DisplayName("Should throw IllegalStateException on corrupt JSON")
    void onRestaurantDecisionEvent_CorruptJson_ThrowsIllegalState() {
        assertThrows(IllegalStateException.class, () -> consumer.onRestaurantDecisionEvent("{bad-json"));
        verifyNoInteractions(orderSagaService);
    }
}