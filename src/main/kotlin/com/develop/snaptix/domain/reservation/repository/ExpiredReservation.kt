package com.develop.snaptix.domain.reservation.repository

/**
 * 만료 정산 대상 예약 최소 읽기 모델. (작업 명세서 §5.5)
 * `findExpiredPending`이 반환하며 보상 Lua(zoneId, orderId)와 조건부 UPDATE(id)에 사용.
 */
data class ExpiredReservation(
    val id: Long,
    val orderId: String,
    val zoneId: Long,
)
