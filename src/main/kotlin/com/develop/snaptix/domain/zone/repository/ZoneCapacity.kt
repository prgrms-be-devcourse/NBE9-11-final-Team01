package com.develop.snaptix.domain.zone.repository

/**
 * 정산/재구축용 zone 정원 읽기 모델. (작업 명세서 §5.5)
 *  - id           : 내부 PK (Redis 키 `ZONE:{id}:stock` 구성)
 *  - publicId     : UUID (클라이언트 노출)
 *  - totalCapacity: 정원 (expectedStock 산정 기준)
 */
data class ZoneCapacity(
    val id: Long,
    val publicId: String,
    val totalCapacity: Int,
)
