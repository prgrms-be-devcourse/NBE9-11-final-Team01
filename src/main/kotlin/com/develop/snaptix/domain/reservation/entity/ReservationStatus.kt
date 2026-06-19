package com.develop.snaptix.domain.reservation.entity

/**
 * 예약 상태. (reservations.status varchar 매핑)
 *
 * "유효 점유" = PENDING_PAYMENT · CONFIRMED (v3.1). CANCELLED · RELEASED 는 점유 제외.
 *
 * NOTE: reservation 도메인 본개발 시 이미 정의돼 있으면 그것을 사용하고 본 파일은 제거한다.
 */
enum class ReservationStatus {
    PENDING_PAYMENT,
    CONFIRMED,
    CANCELLED,
    RELEASED,
}
