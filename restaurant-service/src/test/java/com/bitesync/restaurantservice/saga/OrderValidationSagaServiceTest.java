package com.bitesync.restaurantservice.saga;

import com.bitesync.common.constants.BiteSyncCommonConstants;
import com.bitesync.restaurantservice.constants.RestaurantConstants;
import com.bitesync.restaurantservice.event.OrderCreatedEvent;
import com.bitesync.restaurantservice.event.RestaurantOrderDecisionEvent;
import com.bitesync.restaurantservice.model.MenuCategory;
import com.bitesync.restaurantservice.model.MenuItem;
import com.bitesync.restaurantservice.model.ProcessedOrderEvent;
import com.bitesync.restaurantservice.model.Restaurant;
import com.bitesync.restaurantservice.model.RestaurantStatus;
import com.bitesync.restaurantservice.repository.ProcessedOrderEventRepository;
import com.bitesync.restaurantservice.repository.RestaurantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderValidationSagaServiceTest {

    @Mock
    private RestaurantRepository restaurantRepository;

    @Mock
    private ProcessedOrderEventRepository processedOrderEventRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private ObjectMapper objectMapper;
    private OrderValidationSagaService sagaService;

    private static final String REST_ID = "65f1a29b4c8e12001a9c4b11";
    private UUID orderId;
    private UUID customerId;
    private Restaurant activeRestaurant;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder().findAndAddModules().build();
        sagaService = new OrderValidationSagaService(
                restaurantRepository,
                processedOrderEventRepository,
                kafkaTemplate,
                objectMapper
        );

        orderId = UUID.randomUUID();
        customerId = UUID.randomUUID();

        MenuItem availableBiryani = new MenuItem(
                "item-101", "Hyderabadi Chicken Dum Biryani", new BigDecimal("349.00"), true, List.of("Raita")
        );
        MenuItem soldOutHaleem = new MenuItem(
                "item-102", "Sold Out Mutton Haleem", new BigDecimal("450.00"), false, List.of()
        );
        MenuItem raita = new MenuItem(
                "item-103", "Extra Raita", new BigDecimal("45.50"), true, List.of()
        );

        activeRestaurant = new Restaurant();
        activeRestaurant.setId(REST_ID);
        activeRestaurant.setName("Spice Route Biryani");
        activeRestaurant.setStatus(RestaurantStatus.ACTIVE);
        activeRestaurant.setMenuCategories(List.of(
                new MenuCategory("Signature Biryanis", List.of(availableBiryani, soldOutHaleem)),
                new MenuCategory("Sides", List.of(raita))
        ));
    }

    @Test
    @DisplayName("Happy Path -> Validates MongoDB truth, computes authoritative BigDecimal total, emits ACCEPTED")
    void processOrderCreated_AllValid_PublishesAcceptedAndRecordsEvent() throws Exception {
        OrderCreatedEvent event = new OrderCreatedEvent(
                "evt-1",
                BiteSyncCommonConstants.EVENT_ORDER_CREATED,
                orderId,
                customerId,
                REST_ID,
                List.of(
                        new OrderCreatedEvent.OrderItemPayload("item-101", 2),
                        new OrderCreatedEvent.OrderItemPayload("item-103", 1)
                ),
                Instant.now()
        );

        when(processedOrderEventRepository.existsById("evt-1")).thenReturn(false);
        when(restaurantRepository.findById(REST_ID)).thenReturn(Optional.of(activeRestaurant));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        sagaService.processOrderCreated(event);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate, times(1)).send(
                eq(BiteSyncCommonConstants.TOPIC_RESTAURANT_EVENTS),
                eq(orderId.toString()),
                payloadCaptor.capture()
        );

        RestaurantOrderDecisionEvent decision =
                objectMapper.readValue(payloadCaptor.getValue(), RestaurantOrderDecisionEvent.class);
        assertEquals(BiteSyncCommonConstants.EVENT_RESTAURANT_ORDER_ACCEPTED, decision.eventType());
        assertEquals(orderId, decision.orderId());
        assertEquals("Spice Route Biryani", decision.restaurantName());
        assertEquals(new BigDecimal("743.50"), decision.totalAmount()); // (2 * 349.00) + 45.50
        assertEquals(2, decision.pricedItems().size());
        assertNull(decision.rejectionReason());

        ArgumentCaptor<ProcessedOrderEvent> processedCaptor = ArgumentCaptor.forClass(ProcessedOrderEvent.class);
        verify(processedOrderEventRepository, times(1)).insert(processedCaptor.capture());
        assertEquals("evt-1", processedCaptor.getValue().getEventId());
        assertEquals(BiteSyncCommonConstants.EVENT_RESTAURANT_ORDER_ACCEPTED, processedCaptor.getValue().getOutcome());
    }

    @Test
    @DisplayName("Idempotency -> Skips already-processed eventId without querying MongoDB or publishing to Kafka")
    void processOrderCreated_DuplicateEvent_Ignored() {
        OrderCreatedEvent event = new OrderCreatedEvent(
                "evt-dup", BiteSyncCommonConstants.EVENT_ORDER_CREATED,
                orderId, customerId, REST_ID,
                List.of(new OrderCreatedEvent.OrderItemPayload("item-101", 1)), Instant.now()
        );
        when(processedOrderEventRepository.existsById("evt-dup")).thenReturn(true);

        sagaService.processOrderCreated(event);

        verifyNoInteractions(restaurantRepository, kafkaTemplate);
        verify(processedOrderEventRepository, never()).insert(any(ProcessedOrderEvent.class));
    }

    @Test
    @DisplayName("Missing Restaurant -> Emits RESTAURANT_ORDER_REJECTED with restaurant-not-found reason")
    void processOrderCreated_RestaurantNotFound_PublishesRejected() throws Exception {
        OrderCreatedEvent event = new OrderCreatedEvent(
                "evt-2", BiteSyncCommonConstants.EVENT_ORDER_CREATED,
                orderId, customerId, REST_ID,
                List.of(new OrderCreatedEvent.OrderItemPayload("item-101", 1)), Instant.now()
        );
        when(processedOrderEventRepository.existsById("evt-2")).thenReturn(false);
        when(restaurantRepository.findById(REST_ID)).thenReturn(Optional.empty());
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        sagaService.processOrderCreated(event);

        RestaurantOrderDecisionEvent decision = capturePublishedDecision();
        assertEquals(BiteSyncCommonConstants.EVENT_RESTAURANT_ORDER_REJECTED, decision.eventType());
        assertEquals(RestaurantConstants.MSG_SAGA_RESTAURANT_NOT_FOUND, decision.rejectionReason());
        assertEquals(new BigDecimal("0.00"), decision.totalAmount());
    }

    @Test
    @DisplayName("Pending Restaurant -> Emits RESTAURANT_ORDER_REJECTED when status is not ACTIVE")
    void processOrderCreated_PendingRestaurant_PublishesRejected() throws Exception {
        activeRestaurant.setStatus(RestaurantStatus.PENDING_APPROVAL);
        OrderCreatedEvent event = new OrderCreatedEvent(
                "evt-3", BiteSyncCommonConstants.EVENT_ORDER_CREATED,
                orderId, customerId, REST_ID,
                List.of(new OrderCreatedEvent.OrderItemPayload("item-101", 1)), Instant.now()
        );
        when(processedOrderEventRepository.existsById("evt-3")).thenReturn(false);
        when(restaurantRepository.findById(REST_ID)).thenReturn(Optional.of(activeRestaurant));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        sagaService.processOrderCreated(event);

        RestaurantOrderDecisionEvent decision = capturePublishedDecision();
        assertEquals(BiteSyncCommonConstants.EVENT_RESTAURANT_ORDER_REJECTED, decision.eventType());
        assertEquals(RestaurantConstants.MSG_SAGA_RESTAURANT_NOT_ACTIVE, decision.rejectionReason());
    }

    @Test
    @DisplayName("Unknown Item -> Emits RESTAURANT_ORDER_REJECTED with missing itemId reason")
    void processOrderCreated_UnknownItem_PublishesRejected() throws Exception {
        OrderCreatedEvent event = new OrderCreatedEvent(
                "evt-4", BiteSyncCommonConstants.EVENT_ORDER_CREATED,
                orderId, customerId, REST_ID,
                List.of(new OrderCreatedEvent.OrderItemPayload("item-999", 1)), Instant.now()
        );
        when(processedOrderEventRepository.existsById("evt-4")).thenReturn(false);
        when(restaurantRepository.findById(REST_ID)).thenReturn(Optional.of(activeRestaurant));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        sagaService.processOrderCreated(event);

        RestaurantOrderDecisionEvent decision = capturePublishedDecision();
        assertEquals(BiteSyncCommonConstants.EVENT_RESTAURANT_ORDER_REJECTED, decision.eventType());
        assertEquals(RestaurantConstants.MSG_SAGA_ITEM_NOT_FOUND_PREFIX + "item-999", decision.rejectionReason());
    }

    @Test
    @DisplayName("Sold-Out Item -> Emits RESTAURANT_ORDER_REJECTED when item isAvailable is false")
    void processOrderCreated_SoldOutItem_PublishesRejected() throws Exception {
        OrderCreatedEvent event = new OrderCreatedEvent(
                "evt-5", BiteSyncCommonConstants.EVENT_ORDER_CREATED,
                orderId, customerId, REST_ID,
                List.of(new OrderCreatedEvent.OrderItemPayload("item-102", 1)), Instant.now()
        );
        when(processedOrderEventRepository.existsById("evt-5")).thenReturn(false);
        when(restaurantRepository.findById(REST_ID)).thenReturn(Optional.of(activeRestaurant));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        sagaService.processOrderCreated(event);

        RestaurantOrderDecisionEvent decision = capturePublishedDecision();
        assertEquals(BiteSyncCommonConstants.EVENT_RESTAURANT_ORDER_REJECTED, decision.eventType());
        assertEquals(
                RestaurantConstants.MSG_SAGA_ITEM_UNAVAILABLE_PREFIX + "Sold Out Mutton Haleem",
                decision.rejectionReason()
        );
    }

    @Test
    @DisplayName("Concurrent Duplicate -> Handles MongoDB DuplicateKeyException on insert gracefully")
    void processOrderCreated_ConcurrentDuplicateKey_DoesNotThrow() {
        OrderCreatedEvent event = new OrderCreatedEvent(
                "evt-race", BiteSyncCommonConstants.EVENT_ORDER_CREATED,
                orderId, customerId, REST_ID,
                List.of(new OrderCreatedEvent.OrderItemPayload("item-101", 1)), Instant.now()
        );
        when(processedOrderEventRepository.existsById("evt-race")).thenReturn(false);
        when(restaurantRepository.findById(REST_ID)).thenReturn(Optional.of(activeRestaurant));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(processedOrderEventRepository.insert(any(ProcessedOrderEvent.class)))
                .thenThrow(new DuplicateKeyException("Already inserted by concurrent pod"));

        assertDoesNotThrow(() -> sagaService.processOrderCreated(event));
    }

    private RestaurantOrderDecisionEvent capturePublishedDecision() throws Exception {
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate, times(1)).send(
                eq(BiteSyncCommonConstants.TOPIC_RESTAURANT_EVENTS),
                eq(orderId.toString()),
                payloadCaptor.capture()
        );
        return objectMapper.readValue(payloadCaptor.getValue(), RestaurantOrderDecisionEvent.class);
    }
}