package com.develop.snaptix.domain.payment.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Mock PG 결제 처리 결과")
enum class MockPaymentStatus {
    SUCCESS,
    FAIL,
}
