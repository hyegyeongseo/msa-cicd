package com.example.user.repository;

import com.example.user.entity.UserOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserOrderRepository extends JpaRepository<UserOrder, Long> {
    // eventId로 원본 주문 레코드 조회
    Optional<UserOrder> findByEventId(String eventId);
}