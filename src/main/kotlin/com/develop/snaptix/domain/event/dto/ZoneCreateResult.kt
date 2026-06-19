package com.develop.snaptix.domain.event.dto

data class ZoneCreateResult(
    val zoneId: String,
    val name: String,
    val unitPrice: Int,
    val totalCapacity: Int,
    val redisStockKey: String,
)
