package com.example.order.service;

import com.example.order.dto.OrderRequest;
import com.example.order.entity.Order;
import com.example.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final StringRedisTemplate redisTemplate;

    public String createOrder(OrderRequest request) {
        Order order = Order.builder()
                .username(request.getUsername())
                .product(request.getProduct())
                .status("완료")
                .build();

        orderRepository.save(order);

        String key = "order-status:" + request.getUsername() + "-" + request.getProduct();
        redisTemplate.opsForValue().set(key, "COMPLETE");

        return request.getUsername() + "님의 " + request.getProduct() + " 상품 주문 완료";
    }
}