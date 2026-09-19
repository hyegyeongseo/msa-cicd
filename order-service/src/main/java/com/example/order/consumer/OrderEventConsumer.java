package com.example.order.consumer;

import com.example.order.dto.OrderEvent;
import com.example.order.entity.Order;
import com.example.order.repository.OrderRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventConsumer {

    private final OrderRepository orderRepository;
    private final StringRedisTemplate redisTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * User Service가 "order-events" 토픽에 발행한 주문 이벤트를 수신.
     * 주문을 처리하고 결과를 "order-result" 토픽에 발행.
     */
    @KafkaListener(topics = "order-events", groupId = "order-service-group")
    public void consumeOrderEvent(String message) {
        OrderEvent event;
        try {
            event = objectMapper.readValue(message, OrderEvent.class);
        } catch (Exception e) {
            log.error("❌ 이벤트 역직렬화 실패", e);
            return;
        }

        log.info("📥 주문 이벤트 수신: eventId={}, user={}, product={}",
                event.getEventId(), event.getUsername(), event.getProduct());
        
        // ★ [멱등성 보장] 동일 eventId로 이미 처리된 주문이 있는지 확인
        //   Kafka의 at-least-once 전송 특성상 컨슈머 장애 후 재시작 시
        //   이미 처리된 메시지가 다시 전달될 수 있음
        //   → 중복 처리 없이 기존 결과를 다시 발행
        if (orderRepository.existsByEventId(event.getEventId())) {
            log.info("ℹ️ 이미 처리된 이벤트, 중복 무시: eventId={}", event.getEventId());

            // 이미 처리된 주문의 결과를 다시 발행 (user-service가 결과를 못 받았을 수 있으므로)
            orderRepository.findByEventId(event.getEventId()).ifPresent(existingOrder -> {
                String existingStatus = "완료".equals(existingOrder.getStatus()) ? "COMPLETED" : "FAILED";
                publishResult(event, existingStatus);
            });
            return;
        }

        String resultStatus;
        Order savedOrder = null;

        try {
            // 1. 주문 DB에 저장 (★ eventId 포함)
            Order order = Order.builder()
                    .eventId(event.getEventId())
                    .username(event.getUsername())
                    .product(event.getProduct())
                    .status("완료")
                    .build();
            savedOrder = orderRepository.save(order);

            // 2. Redis에 상태 캐싱
            String key = "order-status:" + event.getUsername() + "-" + event.getProduct();
            redisTemplate.opsForValue().set(key, "COMPLETE");

            resultStatus = "COMPLETED";
            log.info("✅ 주문 처리 완료: eventId={}", event.getEventId());

        } catch (Exception e) {
            // ★ [Saga] 주문 처리 실패 시 "실패" 상태로 기록
            //   비즈니스 로직 실패의 근거를 DB에 남김
            resultStatus = "FAILED";
            log.error("❌ 주문 처리 실패: eventId={}", event.getEventId(), e);
        }

        // 3. 처리 결과를 "order-result" 토픽에 발행
        boolean published = publishResult(event, resultStatus);

        // ★ [Saga 보상] 결과 발행 실패 시 DB 롤백
        //   주문은 DB에 저장했는데 결과를 user-service에 알리지 못한 상황
        //   → user-service는 "주문요청" 상태로 방치됨
        //   → 이미 저장한 주문을 삭제하여 일관성 유지
        if (!published && savedOrder != null) {
            orderRepository.delete(savedOrder);
            String redisKey = "order-status:" + event.getUsername() + "-" + event.getProduct();
            redisTemplate.delete(redisKey);
            log.warn("🔄 결과 발행 실패로 주문 DB 보상 처리 완료: eventId={}", event.getEventId());
        }
    }

    /**
     * 주문 결과를 "order-result" 토픽에 발행하는 헬퍼 메서드
     * 발행 성공 여부를 반환하여 호출부에서 보상 처리를 결정할 수 있게 함
     */
    private boolean publishResult(OrderEvent originalEvent, String resultStatus) {
        OrderEvent resultEvent = OrderEvent.builder()
                .eventId(originalEvent.getEventId())
                .username(originalEvent.getUsername())
                .product(originalEvent.getProduct())
                .status(resultStatus)
                .build();

        try {
            String json = objectMapper.writeValueAsString(resultEvent);
            // ★ .get()으로 동기 전송 확인
            kafkaTemplate.send("order-result", originalEvent.getEventId(), json).get();
            log.info("📤 주문 결과 발행: eventId={}, status={}", originalEvent.getEventId(), resultStatus);
            return true;
        } catch (Exception e) {
            log.error("❌ 결과 이벤트 발행 실패: eventId={}", originalEvent.getEventId(), e);
            return false;
        }
    }
}
