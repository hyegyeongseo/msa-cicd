package com.example.user.consumer;

import com.example.user.dto.OrderEvent;
import com.example.user.entity.UserOrder;
import com.example.user.repository.UserOrderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderResultConsumer {

    private final UserOrderRepository repository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Order Service가 주문 처리 후 "order-result" 토픽에 발행한 결과를 수신.
     * 수신한 결과에 따라 user_orders 테이블의 상태를 업데이트.
     */
    @KafkaListener(topics = "order-result", groupId = "user-service-group")
    public void consumeOrderResult(String message) {
        try {
            OrderEvent event = objectMapper.readValue(message, OrderEvent.class);

            // ★ [Saga 핵심 변경] eventId로 원본 레코드 조회
            //
            //   기존 코드의 문제:
            //     repository.save(UserOrder.builder()...build())
            //     → 매번 새 레코드를 INSERT하여 중복 데이터 발생
            //     → 원본 "주문요청" 레코드는 영원히 그 상태로 남음
            //
            //   변경 후:
            //     eventId로 원본 레코드를 찾아 상태만 UPDATE
            Optional<UserOrder> optionalOrder = repository.findByEventId(event.getEventId());

            if (optionalOrder.isEmpty()) {
                // eventId에 해당하는 레코드가 없으면 비정상 상황
                log.warn("⚠️ eventId={}에 해당하는 주문을 찾을 수 없습니다. 무시합니다.", event.getEventId());
                return;
            }

            UserOrder userOrder = optionalOrder.get();

            // ★ [멱등성 보장] 이미 최종 상태인 레코드는 중복 처리 방지
            //   Kafka는 at-least-once 전송을 보장하므로 같은 메시지가 재전송될 수 있음
            //   이미 "완료" 또는 "보상완료" 상태라면 다시 처리하지 않음
            if ("완료".equals(userOrder.getStatus()) || "보상완료".equals(userOrder.getStatus())) {
                log.info("ℹ️ 이미 처리된 이벤트, 중복 무시: eventId={}, currentStatus={}",
                        event.getEventId(), userOrder.getStatus());
                return;
            }

            // ★ [Saga 보상 트랜잭션] 결과에 따라 기존 레코드 상태 UPDATE
            if ("COMPLETED".equals(event.getStatus())) {
                // 정상 완료: Saga 성공 종료
                userOrder.setStatus("완료");
                log.info("✅ Saga 정상 완료: eventId={}, user={}, product={}",
                        event.getEventId(), event.getUsername(), event.getProduct());
            } else {
                // 실패: 보상 트랜잭션 실행
                //   order-service에서 처리 실패 → user-service 측 레코드를 "보상완료"로 변경
                //   이것이 Choreography Saga의 핵심: 실패 이벤트를 수신한 서비스가
                //   자신의 로컬 트랜잭션을 되돌리는 보상 작업을 수행
                userOrder.setStatus("보상완료");
                log.warn("🔄 Saga 보상 처리 완료: eventId={}, user={}, product={}",
                        event.getEventId(), event.getUsername(), event.getProduct());
            }

            repository.save(userOrder);

        } catch (Exception e) {
            log.error("❌ 주문 결과 처리 실패", e);
        }
    }
}
