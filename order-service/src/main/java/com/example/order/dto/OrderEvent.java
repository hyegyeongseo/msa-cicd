package com.example.order.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderEvent {
    private String eventId;      
    private String username;
    private String product;
    private String status;       // REQUESTED, COMPLETED, FAILED
}
