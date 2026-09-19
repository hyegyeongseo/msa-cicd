package com.example.order.repository;

import com.example.order.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /**
     * ★ [Saga 추가] 멱등성 확인용
     * - 동일 eventId로 이미 처리된 주문이 있는지 빠르게 확인
     */
    boolean existsByEventId(String eventId);

    /**
     * ★ [Saga 추가] 보상 시 기존 레코드 조회용
     * - 결과 발행 실패 시 이미 저장한 주문을 찾아 삭제/롤백하는 데 사용
     */
    Optional<Order> findByEventId(String eventId);
}
