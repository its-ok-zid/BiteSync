package com.bitesync.orderservice.cqrs;

import com.bitesync.common.constants.BiteSyncCommonConstants;
import com.bitesync.orderservice.constants.OrderConstants;
import com.bitesync.orderservice.event.OrderCreatedEvent;
import com.bitesync.orderservice.event.OrderStatusChangedEvent;
import com.bitesync.orderservice.model.OrderStatus;
import com.bitesync.orderservice.model.OrderSummaryView;
import com.bitesync.orderservice.model.ProcessedEvent;
import com.bitesync.orderservice.model.ProcessedEventId;
import com.bitesync.orderservice.repository.OrderSummaryViewRepository;
import com.bitesync.orderservice.repository.ProcessedEventRepository;
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
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderProjectionServiceTest {

    @Mock private OrderSummaryViewRepository viewRepository;
    @Mock private ProcessedEventRepository processedEventRepository;

    private ObjectMapper objectMapper;
    private OrderProjectionService projectionService;

    private UUID orderId;
    private UUID customerId;
    private static final String REST_ID = "65f1a29b4c8e12001a9c4b11";

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder().findAndAddModules().build();
        projectionService = new OrderProjectionService(viewRepository, processedEventRepository, objectMapper);
        orderId = UUID.randomUUID();
        customerId = UUID.randomUUID();
    }

    @Test
    @DisplayName("projectOrderCreated -> Inserts PENDING view with summed itemCount, itemsJson & ProcessedEvent")
    void projectOrderCreated_NewOrder_InsertsPendingView() {
        OrderCreatedEvent event = new OrderCreatedEvent(
                "evt-c1", BiteSyncCommonConstants.EVENT_ORDER_CREATED,
                orderId, customerId, REST_ID,
                List.of(
                        new OrderCreatedEvent.OrderItemPayload("item-101", 2),
                        new OrderCreatedEvent.OrderItemPayload("item-103", 3)
                ),
                Instant.now()
        );
        when(processedEventRepository.existsById(new ProcessedEventId("evt-c1", OrderConstants.KAFKA_GROUP_PROJECTOR)))
                .thenReturn(false);
        when(viewRepository.findById(orderId)).thenReturn(Optional.empty());

        projectionService.projectOrderCreated(event);

        ArgumentCaptor<OrderSummaryView> captor = ArgumentCaptor.forClass(OrderSummaryView.class);
        verify(viewRepository, times(1)).save(captor.capture());

        OrderSummaryView saved = captor.getValue();
        assertEquals(orderId, saved.getOrderId());
        assertEquals(OrderStatus.PENDING, saved.getStatus());
        assertEquals(5, saved.getItemCount());
        assertEquals(new BigDecimal("0.00"), saved.getTotalAmount());
        assertTrue(saved.getItemsJson().contains("item-101"));
        verify(processedEventRepository, times(1)).save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("projectOrderCreated -> Ignores duplicate eventId")
    void projectOrderCreated_Duplicate_Ignored() {
        OrderCreatedEvent event = new OrderCreatedEvent(
                "evt-dup", BiteSyncCommonConstants.EVENT_ORDER_CREATED,
                orderId, customerId, REST_ID, List.of(), Instant.now()
        );
        when(processedEventRepository.existsById(new ProcessedEventId("evt-dup", OrderConstants.KAFKA_GROUP_PROJECTOR)))
                .thenReturn(true);

        projectionService.projectOrderCreated(event);

        verifyNoInteractions(viewRepository);
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("projectOrderCreated -> Out-of-order guard skips overwriting already CONFIRMED view")
    void projectOrderCreated_OutOfOrder_DoesNotDowngradeConfirmedView() {
        OrderSummaryView existingConfirmed = new OrderSummaryView();
        existingConfirmed.setOrderId(orderId);
        existingConfirmed.setStatus(OrderStatus.CONFIRMED);

        OrderCreatedEvent lateCreated = new OrderCreatedEvent(
                "evt-late", BiteSyncCommonConstants.EVENT_ORDER_CREATED,
                orderId, customerId, REST_ID,
                List.of(new OrderCreatedEvent.OrderItemPayload("item-101", 1)), Instant.now()
        );

        when(processedEventRepository.existsById(any())).thenReturn(false);
        when(viewRepository.findById(orderId)).thenReturn(Optional.of(existingConfirmed));

        projectionService.projectOrderCreated(lateCreated);

        verify(viewRepository, never()).save(any());
        verify(processedEventRepository, times(1)).save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("projectOrderStatusChanged -> Updates existing PENDING view to CONFIRMED with priced items")
    void projectOrderStatusChanged_UpdatesExistingViewToConfirmed() {
        OrderSummaryView existing = new OrderSummaryView();
        existing.setOrderId(orderId);
        existing.setCustomerId(customerId);
        existing.setRestaurantId(REST_ID);
        existing.setStatus(OrderStatus.PENDING);

        OrderStatusChangedEvent confirmed = new OrderStatusChangedEvent(
                "evt-conf", BiteSyncCommonConstants.EVENT_ORDER_CONFIRMED,
                orderId, customerId, REST_ID, "Spice Route Biryani",
                OrderStatus.CONFIRMED, new BigDecimal("698.00"), null,
                List.of(new OrderStatusChangedEvent.OrderItemSnapshot(
                        "item-101", "Hyderabadi Chicken Dum Biryani", 2, new BigDecimal("349.00")
                )),
                Instant.now()
        );

        when(processedEventRepository.existsById(any())).thenReturn(false);
        when(viewRepository.findById(orderId)).thenReturn(Optional.of(existing));

        projectionService.projectOrderStatusChanged(confirmed);

        verify(viewRepository, times(1)).save(existing);
        assertEquals(OrderStatus.CONFIRMED, existing.getStatus());
        assertEquals("Spice Route Biryani", existing.getRestaurantName());
        assertEquals(new BigDecimal("698.00"), existing.getTotalAmount());
        assertEquals(2, existing.getItemCount());
        assertTrue(existing.getItemsJson().contains("Hyderabadi Chicken Dum Biryani"));
        verify(processedEventRepository, times(1)).save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("projectOrderStatusChanged -> Upserts new CANCELLED view safely if ORDER_CREATED hasn't arrived")
    void projectOrderStatusChanged_MissingView_UpsertsCancelledView() {
        OrderStatusChangedEvent cancelled = new OrderStatusChangedEvent(
                "evt-canc", BiteSyncCommonConstants.EVENT_ORDER_CANCELLED,
                orderId, customerId, REST_ID, "Spice Route Biryani",
                OrderStatus.CANCELLED, BigDecimal.ZERO, "Menu item not found: item-999",
                List.of(new OrderStatusChangedEvent.OrderItemSnapshot("item-999", null, 1, BigDecimal.ZERO)),
                Instant.now()
        );

        when(processedEventRepository.existsById(any())).thenReturn(false);
        when(viewRepository.findById(orderId)).thenReturn(Optional.empty());

        projectionService.projectOrderStatusChanged(cancelled);

        ArgumentCaptor<OrderSummaryView> captor = ArgumentCaptor.forClass(OrderSummaryView.class);
        verify(viewRepository, times(1)).save(captor.capture());

        OrderSummaryView upserted = captor.getValue();
        assertEquals(orderId, upserted.getOrderId());
        assertEquals(OrderStatus.CANCELLED, upserted.getStatus());
        assertEquals("Menu item not found: item-999", upserted.getCancellationReason());
        assertEquals(1, upserted.getItemCount());
    }
}