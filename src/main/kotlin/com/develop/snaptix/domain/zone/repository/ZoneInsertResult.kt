package com.develop.snaptix.domain.zone.repository

data class ZoneInsertResult(
    val id: Long,
    val publicId: String,
    val name: String,
    val unitPrice: Int,
    val totalCapacity: Int,
)
