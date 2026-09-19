package com.example.user.controller;

import com.example.user.dto.UserOrderRequest;
import com.example.user.service.UserOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class UserOrderController {

    private final UserOrderService service;

    @PostMapping("/order")
    public String order(@RequestBody UserOrderRequest request) {
        return service.order(request);
    }
}