package com.bitesync.orderservice.cqrs;

import com.bitesync.common.constants.BiteSyncCommonConstants;
import com.bitesync.orderservice.event.OrderCreatedEvent;
import com.bitesync.orderservice.event.OrderStatusChangedEvent;
import com.bitesync.orderservice.model.OrderStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderReadModelProjectorTest {

    @Mock private OrderProjectionService projectionService;

    private ObjectMapper objectMapper;
    private OrderReadModelProjector projector;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder().findAndAddModules().build();
        projector = new OrderReadModelProjector(projectionService, objectMapper);
    }

    @Test
    @DisplayName("Routes ORDER_CREATED to projectOrderCreated")
    void onOrderLifecycleEvent_Created_RoutesToProjectCreated() throws Exception {
        OrderCreatedEvent event = new OrderCreatedEvent(
                "evt-1", BiteSyncCommonConstants.EVENT_ORDER_CREATED,
                UUID.randomUUID(), UUID.randomUUID(), "65f1a29b4c8e12001a9c4b11",
                List.of(new OrderCreatedEvent.OrderItemPayload("item-101", 2)), Instant.now()
        );

        projector.onOrderLifecycleEvent(objectMapper.writeValueAsString(event));

        verify(projectionService, times(1)).projectOrderCreated(any(OrderCreatedEvent.class));
        verify(projectionService, never()).projectOrderStatusChanged(any());
    }

    @Test
    @DisplayName("Routes ORDER_CONFIRMED and ORDER_CANCELLED to projectOrderStatusChanged")
    void onOrderLifecycleEvent_StatusChanged_RoutesToProjectStatusChanged() throws Exception {
        OrderStatusChangedEvent confirmed = new OrderStatusChangedEvent(
                "evt-2", BiteSyncCommonConstants.EVENT_ORDER_CONFIRMED,
                UUID.randomUUID(), UUID.randomUUID(), "65f1a29b4c8e12001a9c4b11", "Spice Route",
                OrderStatus.CONFIRMED, new BigDecimal("698.00"), null, List.of(), Instant.now()
        );
        OrderStatusChangedEvent cancelled = new OrderStatusChangedEvent(
                "evt-3", BiteSyncCommonConstants.EVENT_ORDER_CANCELLED,
                UUID.randomUUID(), UUID.randomUUID(), "65f1a29b4c8e12001a9c4b11", "Spice Route",
                OrderStatus.CANCELLED, BigDecimal.ZERO, "Sold out", List.of(), Instant.now()
        );

        projector.onOrderLifecycleEvent(objectMapper.writeValueAsString(confirmed));
        projector.onOrderLifecycleEvent(objectMapper.writeValueAsString(cancelled));

        verify(projectionService, times(2)).projectOrderStatusChanged(any(OrderStatusChangedEvent.class));
        verify(projectionService, never()).projectOrderCreated(any());
    }

    @Test
    @DisplayName("Ignores unknown eventType safely")
    void onOrderLifecycleEvent_UnknownType_Ignored() {
        projector.onOrderLifecycleEvent("{\"eventId\":\"e9\",\"eventType\":\"SOME_OTHER_EVENT\"}");

        verifyNoInteractions(projectionService);
    }

    @Test
    @DisplayName("Throws IllegalStateException on corrupt JSON")
    void onOrderLifecycleEvent_CorruptJson_ThrowsIllegalState() {
        assertThrows(IllegalStateException.class, () -> projector.onOrderLifecycleEvent("{bad-json"));
        verifyNoInteractions(projectionService);
    }
}