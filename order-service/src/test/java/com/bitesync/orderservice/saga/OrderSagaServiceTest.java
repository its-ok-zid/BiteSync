package com.bitesync.orderservice.saga;

import com.bitesync.common.constants.BiteSyncCommonConstants;
import com.bitesync.orderservice.constants.OrderConstants;
import com.bitesync.orderservice.event.OrderStatusChangedEvent;
import com.bitesync.orderservice.event.RestaurantOrderDecisionEvent;
import com.bitesync.orderservice.model.Order;
import com.bitesync.orderservice.model.OrderItem;
import com.bitesync.orderservice.model.OrderStatus;
import com.bitesync.orderservice.model.OutboxEvent;
import com.bitesync.orderservice.model.OutboxStatus;
import com.bitesync.orderservice.model.ProcessedEvent;
import com.bitesync.orderservice.model.ProcessedEventId;
import com.bitesync.orderservice.repository.OrderRepository;
import com.bitesync.orderservice.repository.OutboxEventRepository;
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
class OrderSagaServiceTest {

    @Mock private OrderRepository orderRepository;
    @Mock private OutboxEventRepository outboxEventRepository;
    @Mock private ProcessedEventRepository processedEventRepository;

    private ObjectMapper objectMapper;
    private OrderSagaService orderSagaService;

    private UUID orderId;
    private Order pendingOrder;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder().findAndAddModules().build();
        orderSagaService = new OrderSagaService(
                orderRepository,
                outboxEventRepository,
                processedEventRepository,
                objectMapper
        );

        orderId = UUID.randomUUID();
        pendingOrder = new Order();
        pendingOrder.setId(orderId);
        pendingOrder.setCustomerId(UUID.randomUUID());
        pendingOrder.setRestaurantId("65f1a29b4c8e12001a9c4b11");
        pendingOrder.setStatus(OrderStatus.PENDING);
        pendingOrder.setTotalAmount(BigDecimal.ZERO.setScale(2));

        OrderItem item = new OrderItem();
        item.setId(UUID.randomUUID());
        item.setItemId("item-101");
        item.setQuantity(2);
        item.setUnitPrice(BigDecimal.ZERO.setScale(2));
        pendingOrder.addItem(item);
    }

    @Test
    @DisplayName("Accepted -> Transitions PENDING -> CONFIRMED, saves authoritative prices, emits ORDER_CONFIRMED outbox")
    void handleRestaurantDecision_Accepted_ConfirmsOrderAndWritesOutbox() throws Exception {
        RestaurantOrderDecisionEvent decision = new RestaurantOrderDecisionEvent(
                "dec-1",
                BiteSyncCommonConstants.EVENT_RESTAURANT_ORDER_ACCEPTED,
                orderId,
                pendingOrder.getRestaurantId(),
                "Spice Route Biryani",
                List.of(new RestaurantOrderDecisionEvent.PricedOrderItem(
                        "item-101", "Hyderabadi Chicken Dum Biryani", 2, new BigDecimal("349.00")
                )),
                new BigDecimal("698.00"),
                null,
                Instant.now()
        );

        when(processedEventRepository.existsById(new ProcessedEventId("dec-1", OrderConstants.KAFKA_GROUP_SAGA)))
                .thenReturn(false);
        when(orderRepository.findWithItemsById(orderId)).thenReturn(Optional.of(pendingOrder));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        orderSagaService.handleRestaurantDecision(decision);

        assertEquals(OrderStatus.CONFIRMED, pendingOrder.getStatus());
        assertEquals("Spice Route Biryani", pendingOrder.getRestaurantName());
        assertEquals(new BigDecimal("698.00"), pendingOrder.getTotalAmount());
        assertNull(pendingOrder.getCancellationReason());
        assertEquals("Hyderabadi Chicken Dum Biryani", pendingOrder.getItems().get(0).getItemName());
        assertEquals(new BigDecimal("349.00"), pendingOrder.getItems().get(0).getUnitPrice());

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository, times(1)).save(outboxCaptor.capture());
        assertEquals(BiteSyncCommonConstants.EVENT_ORDER_CONFIRMED, outboxCaptor.getValue().getEventType());
        assertEquals(OutboxStatus.PENDING, outboxCaptor.getValue().getStatus());

        OrderStatusChangedEvent emitted =
                objectMapper.readValue(outboxCaptor.getValue().getPayload(), OrderStatusChangedEvent.class);
        assertEquals(OrderStatus.CONFIRMED, emitted.status());
        assertEquals(new BigDecimal("698.00"), emitted.totalAmount());

        verify(processedEventRepository, times(1)).save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("Rejected -> Compensating rollback PENDING -> CANCELLED with reason & ORDER_CANCELLED outbox")
    void handleRestaurantDecision_Rejected_CancelsOrderAndWritesOutbox() throws Exception {
        RestaurantOrderDecisionEvent decision = new RestaurantOrderDecisionEvent(
                "dec-2",
                BiteSyncCommonConstants.EVENT_RESTAURANT_ORDER_REJECTED,
                orderId,
                pendingOrder.getRestaurantId(),
                "Spice Route Biryani",
                List.of(),
                BigDecimal.ZERO,
                "Menu item not found: item-999",
                Instant.now()
        );

        when(processedEventRepository.existsById(new ProcessedEventId("dec-2", OrderConstants.KAFKA_GROUP_SAGA)))
                .thenReturn(false);
        when(orderRepository.findWithItemsById(orderId)).thenReturn(Optional.of(pendingOrder));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        orderSagaService.handleRestaurantDecision(decision);

        assertEquals(OrderStatus.CANCELLED, pendingOrder.getStatus());
        assertEquals("Menu item not found: item-999", pendingOrder.getCancellationReason());

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository, times(1)).save(outboxCaptor.capture());
        assertEquals(BiteSyncCommonConstants.EVENT_ORDER_CANCELLED, outboxCaptor.getValue().getEventType());

        OrderStatusChangedEvent emitted =
                objectMapper.readValue(outboxCaptor.getValue().getPayload(), OrderStatusChangedEvent.class);
        assertEquals(OrderStatus.CANCELLED, emitted.status());
        assertEquals("Menu item not found: item-999", emitted.cancellationReason());

        verify(processedEventRepository, times(1)).save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("Idempotency -> Ignores already-processed eventId without querying orders")
    void handleRestaurantDecision_DuplicateEvent_Ignored() {
        RestaurantOrderDecisionEvent decision = new RestaurantOrderDecisionEvent(
                "dec-dup", BiteSyncCommonConstants.EVENT_RESTAURANT_ORDER_ACCEPTED,
                orderId, pendingOrder.getRestaurantId(), "Spice Route", List.of(), BigDecimal.TEN, null, Instant.now()
        );
        when(processedEventRepository.existsById(new ProcessedEventId("dec-dup", OrderConstants.KAFKA_GROUP_SAGA)))
                .thenReturn(true);

        orderSagaService.handleRestaurantDecision(decision);

        verifyNoInteractions(orderRepository, outboxEventRepository);
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("Missing Order -> Logs warning and exits cleanly without saving outbox or processed event")
    void handleRestaurantDecision_MissingOrder_ReturnsSafely() {
        RestaurantOrderDecisionEvent decision = new RestaurantOrderDecisionEvent(
                "dec-missing", BiteSyncCommonConstants.EVENT_RESTAURANT_ORDER_ACCEPTED,
                orderId, pendingOrder.getRestaurantId(), "Spice Route", List.of(), BigDecimal.TEN, null, Instant.now()
        );
        when(processedEventRepository.existsById(any())).thenReturn(false);
        when(orderRepository.findWithItemsById(orderId)).thenReturn(Optional.empty());

        orderSagaService.handleRestaurantDecision(decision);

        verify(orderRepository, never()).save(any());
        verifyNoInteractions(outboxEventRepository);
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("State Machine Guard -> Skips already CONFIRMED order and marks event processed")
    void handleRestaurantDecision_AlreadyConfirmed_SkipsMutation() {
        pendingOrder.setStatus(OrderStatus.CONFIRMED);
        RestaurantOrderDecisionEvent decision = new RestaurantOrderDecisionEvent(
                "dec-late", BiteSyncCommonConstants.EVENT_RESTAURANT_ORDER_REJECTED,
                orderId, pendingOrder.getRestaurantId(), "Spice Route", List.of(), BigDecimal.ZERO, "Late", Instant.now()
        );
        when(processedEventRepository.existsById(any())).thenReturn(false);
        when(orderRepository.findWithItemsById(orderId)).thenReturn(Optional.of(pendingOrder));

        orderSagaService.handleRestaurantDecision(decision);

        assertEquals(OrderStatus.CONFIRMED, pendingOrder.getStatus());
        verify(orderRepository, never()).save(any());
        verifyNoInteractions(outboxEventRepository);
        verify(processedEventRepository, times(1)).save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("Unknown Event Type -> Exits safely without mutating order")
    void handleRestaurantDecision_UnknownEventType_Ignored() {
        RestaurantOrderDecisionEvent decision = new RestaurantOrderDecisionEvent(
                "dec-unknown", "UNKNOWN_EVENT",
                orderId, pendingOrder.getRestaurantId(), "Spice Route", List.of(), BigDecimal.ZERO, null, Instant.now()
        );
        when(processedEventRepository.existsById(any())).thenReturn(false);
        when(orderRepository.findWithItemsById(orderId)).thenReturn(Optional.of(pendingOrder));

        orderSagaService.handleRestaurantDecision(decision);

        verify(orderRepository, never()).save(any());
        verifyNoInteractions(outboxEventRepository);
        verify(processedEventRepository, never()).save(any());
    }
}