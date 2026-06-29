package com.develop.snaptix.domain.event.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "구역 등록 결과")
data class ZoneCreateResult(
    @field:Schema(description = "등록된 구역 외부 식별자", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    val zoneId: String,
    @field:Schema(description = "구역명", example = "VIP")
    val name: String,
    @field:Schema(description = "구역 단가", example = "150000")
    val unitPrice: Int,
    @field:Schema(description = "구역 총 수용 인원", example = "100")
    val totalCapacity: Int,
    @field:Schema(description = "서버 내부 Redis 재고 키", example = "ZONE:501:stock")
    val redisStockKey: String,
)
