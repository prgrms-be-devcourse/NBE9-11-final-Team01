package com.develop.snaptix.domain.zone.repository

data class ZoneCreateCommand(
    val publicId: String,
    val name: String,
    val unitPrice: Int,
    val totalCapacity: Int,
)
