package com.example.user.service;

import com.example.user.dto.OrderEvent;
import com.example.user.dto.UserOrderRequest;
import com.example.user.entity.UserOrder;
import com.example.user.repository.UserOrderRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserOrderService {

    private final UserOrderRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public String order(UserOrderRequest request) {

        String eventId = UUID.randomUUID().toString();

        UserOrder userOrder = UserOrder.builder()
                .eventId(eventId)
                .username(request.getUsername())
                .product(request.getProduct())
                .status("REQUESTED")
                .build();
        repository.save(userOrder);

        OrderEvent event = OrderEvent.builder()
                .eventId(eventId)
                .username(request.getUsername())
                .product(request.getProduct())
                .status("REQUESTED")
                .build();

        try {
            String json = objectMapper.writeValueAsString(event);

            // ★ [Saga 보상] .get()으로 동기 전송 확인
            //   기존 코드: kafkaTemplate.send(...) → 비동기라 실패해도 예외 발생 안 함
            //   변경 후:   kafkaTemplate.send(...).get() → 실제 전송 완료를 확인
            kafkaTemplate.send("order-events", eventId, json).get();

            log.info("✅ 주문 이벤트 발행 완료: eventId={}, user={}, product={}",
                    eventId, request.getUsername(), request.getProduct());

        } catch (Exception e) {
            // ★ [Saga 보상 트랜잭션] 이벤트 발행 실패 시 DB 레코드 롤백
            //
            //   이 시점에서 DB에는 이미 "주문요청" 레코드가 커밋된 상태이지만
            //   Kafka에는 이벤트가 발행되지 않았으므로 order-service는 이 주문을 모름
            //   → order-result도 영영 돌아오지 않음
            //   → "주문요청" 상태로 영원히 방치되는 문제 발생
            //
            //   따라서 동일 엔티티의 상태를 "발행실패"로 변경하여 보상 처리
            //   JPA 영속성 컨텍스트에 남아있는 같은 객체이므로 UPDATE로 동작
            userOrder.setStatus("발행실패");
            repository.save(userOrder);
            log.error("❌ 이벤트 발행 실패, 보상 처리 완료: eventId={}", eventId, e);
            return "주문 처리 중 오류가 발생했습니다.";
        }

        return request.getUsername() + "님의 " + request.getProduct()
                + " 주문이 접수되었습니다. (eventId: " + eventId + ")";
    }
}
