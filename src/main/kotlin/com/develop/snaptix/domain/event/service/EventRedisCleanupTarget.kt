package com.develop.snaptix.domain.event.service

data class EventRedisCleanupTarget(
    val eventPublicId: String,
    val zoneIds: List<Long>,
)
