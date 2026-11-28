package com.bitesync.orderservice.service;

import com.bitesync.common.constants.BiteSyncCommonConstants;
import com.bitesync.common.exception.BiteSyncException;
import com.bitesync.orderservice.config.KafkaTopicConfig;
import com.bitesync.orderservice.constants.OrderConstants;
import com.bitesync.orderservice.dto.OrderItemRequest;
import com.bitesync.orderservice.dto.PlaceOrderRequest;
import com.bitesync.orderservice.dto.PlaceOrderResult;
import com.bitesync.orderservice.event.OrderCreatedEvent;
import com.bitesync.orderservice.model.IdempotencyRecord;
import com.bitesync.orderservice.model.Order;
import com.bitesync.orderservice.model.OrderStatus;
import com.bitesync.orderservice.model.OutboxEvent;
import com.bitesync.orderservice.model.OutboxStatus;
import com.bitesync.orderservice.repository.IdempotencyRecordRepository;
import com.bitesync.orderservice.repository.OrderRepository;
import com.bitesync.orderservice.repository.OutboxEventRepository;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderCommandServiceTest {

    @Mock private OrderRepository orderRepository;
    @Mock private OutboxEventRepository outboxEventRepository;
    @Mock private IdempotencyRecordRepository idempotencyRecordRepository;

    private ObjectMapper objectMapper;
    private OrderCommandService commandService;

    private static final String REST_ID = "65f1a29b4c8e12001a9c4b11";
    private UUID customerId;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder().findAndAddModules().build();
        commandService = new OrderCommandService(
                orderRepository,
                outboxEventRepository,
                idempotencyRecordRepository,
                objectMapper
        );
        customerId = UUID.randomUUID();
    }

    @Test
    @DisplayName("Fresh Order -> Atomically saves Order (PENDING), OutboxEvent (ORDER_CREATED) & IdempotencyRecord")
    void placeOrder_FreshRequest_PersistsAllThreeAtomically() throws Exception {
        PlaceOrderRequest request = new PlaceOrderRequest(
                REST_ID,
                List.of(new OrderItemRequest("item-101", 2))
        );

        when(idempotencyRecordRepository.findByIdempotencyKeyAndCustomerId("key-001", customerId))
                .thenReturn(Optional.empty());
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        PlaceOrderResult result = commandService.placeOrder(request, customerId.toString(), "  key-001  ");

        assertFalse(result.idempotentReplay());
        assertEquals(201, result.httpStatus());
        assertEquals(OrderStatus.PENDING, result.order().status());
        assertEquals(new BigDecimal("0.00"), result.order().totalAmount());
        assertEquals(customerId, result.order().customerId());
        assertEquals(1, result.order().items().size());

        // Verify OutboxEvent written with ORDER_CREATED payload
        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository, times(1)).save(outboxCaptor.capture());
        OutboxEvent savedOutbox = outboxCaptor.getValue();
        assertEquals(OrderConstants.AGGREGATE_TYPE_ORDER, savedOutbox.getAggregateType());
        assertEquals(result.order().orderId().toString(), savedOutbox.getAggregateId());
        assertEquals(BiteSyncCommonConstants.EVENT_ORDER_CREATED, savedOutbox.getEventType());
        assertEquals(KafkaTopicConfig.ORDER_EVENTS_TOPIC, savedOutbox.getTopic());
        assertEquals(OutboxStatus.PENDING, savedOutbox.getStatus());

        OrderCreatedEvent payload = objectMapper.readValue(savedOutbox.getPayload(), OrderCreatedEvent.class);
        assertEquals(result.order().orderId(), payload.orderId());
        assertEquals("item-101", payload.items().get(0).itemId());
        assertEquals(2, payload.items().get(0).quantity());

        // Verify IdempotencyRecord written with 64-char SHA-256 hash
        ArgumentCaptor<IdempotencyRecord> idemCaptor = ArgumentCaptor.forClass(IdempotencyRecord.class);
        verify(idempotencyRecordRepository, times(1)).save(idemCaptor.capture());
        assertEquals("key-001", idemCaptor.getValue().getIdempotencyKey());
        assertEquals(64, idemCaptor.getValue().getRequestHash().length());
        assertEquals(201, idemCaptor.getValue().getHttpStatus());
    }

    @Test
    @DisplayName("Idempotent Replay -> Same key + identical cart (even reversed item order) replays saved response")
    void placeOrder_CanonicalMatchingRetry_ReplaysWithoutDuplicateWrite() {
        PlaceOrderRequest original = new PlaceOrderRequest(
                REST_ID,
                List.of(new OrderItemRequest("item-200", 1), new OrderItemRequest("item-100", 2))
        );
        PlaceOrderRequest reversedRetry = new PlaceOrderRequest(
                REST_ID,
                List.of(new OrderItemRequest("item-100", 2), new OrderItemRequest("item-200", 1))
        );

        when(idempotencyRecordRepository.findByIdempotencyKeyAndCustomerId("key-replay", customerId))
                .thenReturn(Optional.empty());
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        PlaceOrderResult first = commandService.placeOrder(original, customerId.toString(), "key-replay");

        ArgumentCaptor<IdempotencyRecord> idemCaptor = ArgumentCaptor.forClass(IdempotencyRecord.class);
        verify(idempotencyRecordRepository).save(idemCaptor.capture());
        IdempotencyRecord storedRecord = idemCaptor.getValue();

        // Simulate retry arriving with items in opposite JSON order
        when(idempotencyRecordRepository.findByIdempotencyKeyAndCustomerId("key-replay", customerId))
                .thenReturn(Optional.of(storedRecord));

        PlaceOrderResult replayed = commandService.placeOrder(reversedRetry, customerId.toString(), "key-replay");

        assertTrue(replayed.idempotentReplay());
        assertEquals(201, replayed.httpStatus());
        assertEquals(first.order().orderId(), replayed.order().orderId());
        verify(orderRepository, times(1)).save(any(Order.class));
        verify(outboxEventRepository, times(1)).save(any(OutboxEvent.class));
    }

    @Test
    @DisplayName("Idempotency Conflict -> Same key with different payload throws ORDER_003")
    void placeOrder_TamperedPayloadRetry_ThrowsOrder003() {
        IdempotencyRecord existing = new IdempotencyRecord();
        existing.setIdempotencyKey("key-conflict");
        existing.setCustomerId(customerId);
        existing.setRequestHash("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

        when(idempotencyRecordRepository.findByIdempotencyKeyAndCustomerId("key-conflict", customerId))
                .thenReturn(Optional.of(existing));

        PlaceOrderRequest request = new PlaceOrderRequest(
                REST_ID, List.of(new OrderItemRequest("item-101", 5))
        );

        BiteSyncException ex = assertThrows(BiteSyncException.class,
                () -> commandService.placeOrder(request, customerId.toString(), "key-conflict"));

        assertEquals(OrderConstants.ERROR_IDEMPOTENCY_CONFLICT, ex.getErrorCode());
        verifyNoInteractions(orderRepository, outboxEventRepository);
    }

    @Test
    @DisplayName("Guard -> Rejects missing or blank Idempotency-Key with ORDER_002")
    void placeOrder_MissingIdempotencyKey_ThrowsOrder002() {
        PlaceOrderRequest request = new PlaceOrderRequest(REST_ID, List.of(new OrderItemRequest("item-101", 1)));

        assertEquals(OrderConstants.ERROR_INVALID_ORDER_REQUEST,
                assertThrows(BiteSyncException.class,
                        () -> commandService.placeOrder(request, customerId.toString(), null)).getErrorCode());
        assertEquals(OrderConstants.ERROR_INVALID_ORDER_REQUEST,
                assertThrows(BiteSyncException.class,
                        () -> commandService.placeOrder(request, customerId.toString(), "   ")).getErrorCode());
        verifyNoInteractions(orderRepository, outboxEventRepository, idempotencyRecordRepository);
    }

    @Test
    @DisplayName("Guard -> Rejects Idempotency-Key longer than 128 chars with ORDER_002")
    void placeOrder_TooLongIdempotencyKey_ThrowsOrder002() {
        PlaceOrderRequest request = new PlaceOrderRequest(REST_ID, List.of(new OrderItemRequest("item-101", 1)));
        String tooLong = "x".repeat(129);

        BiteSyncException ex = assertThrows(BiteSyncException.class,
                () -> commandService.placeOrder(request, customerId.toString(), tooLong));

        assertEquals(OrderConstants.ERROR_INVALID_ORDER_REQUEST, ex.getErrorCode());
        verifyNoInteractions(orderRepository);
    }

    @Test
    @DisplayName("Guard -> Rejects invalid customer UUID subject with ORDER_002")
    void placeOrder_InvalidCustomerUuid_ThrowsOrder002() {
        PlaceOrderRequest request = new PlaceOrderRequest(REST_ID, List.of(new OrderItemRequest("item-101", 1)));

        BiteSyncException ex = assertThrows(BiteSyncException.class,
                () -> commandService.placeOrder(request, "not-a-uuid", "key-1"));

        assertEquals(OrderConstants.ERROR_INVALID_ORDER_REQUEST, ex.getErrorCode());
        verifyNoInteractions(orderRepository);
    }

    @Test
    @DisplayName("Guard -> Rejects malformed MongoDB restaurantId with ORDER_002")
    void placeOrder_InvalidRestaurantId_ThrowsOrder002() {
        PlaceOrderRequest request = new PlaceOrderRequest("bad-id", List.of(new OrderItemRequest("item-101", 1)));

        BiteSyncException ex = assertThrows(BiteSyncException.class,
                () -> commandService.placeOrder(request, customerId.toString(), "key-1"));

        assertEquals(OrderConstants.ERROR_INVALID_ORDER_REQUEST, ex.getErrorCode());
        verifyNoInteractions(orderRepository);
    }

    @Test
    @DisplayName("Guard -> Rejects duplicate itemId in order payload with ORDER_002")
    void placeOrder_DuplicateItemId_ThrowsOrder002() {
        PlaceOrderRequest request = new PlaceOrderRequest(
                REST_ID,
                List.of(new OrderItemRequest("item-101", 1), new OrderItemRequest(" item-101 ", 2))
        );

        BiteSyncException ex = assertThrows(BiteSyncException.class,
                () -> commandService.placeOrder(request, customerId.toString(), "key-1"));

        assertEquals(OrderConstants.ERROR_INVALID_ORDER_REQUEST, ex.getErrorCode());
        verifyNoInteractions(orderRepository);
    }
}