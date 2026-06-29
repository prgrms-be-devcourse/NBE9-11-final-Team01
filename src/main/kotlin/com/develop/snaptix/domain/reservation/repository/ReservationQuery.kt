package com.develop.snaptix.domain.reservation.repository

import com.develop.snaptix.domain.reservation.entity.ReservationStatus
import java.time.Instant

/**
 * SSE 어댑터가 의존하는 최소 예약 조회 포트(데이터 접근 계약).
 * reservation 도메인 본개발 시 실제 리포지토리가 이 포트를 구현(또는 대체)한다.
 */
fun interface ReservationQuery {
    fun findByOrderId(orderId: String): ReservationView?
}

/** 소유권·재구성 판정에 필요한 예약 최소 읽기 모델. */
data class ReservationView(
    val userId: Long,
    val status: ReservationStatus,
    val createdAt: Instant,
)
