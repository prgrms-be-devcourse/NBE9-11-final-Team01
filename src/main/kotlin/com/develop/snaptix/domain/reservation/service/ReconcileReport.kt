package com.develop.snaptix.domain.reservation.service

/**
 * 만료 정산 결과. (작업 명세서 §5.1)
 *  - released    : RELEASED로 전이된 만료 예약 수 (affected=1)
 *  - compensated : claimed 가드 보상(+1)이 실제 수행된 수 (compensate == true)  // ← "Lua 반환 >= 0" 폐기
 */
data class ReconcileReport(
    val released: Int,
    val compensated: Int,
    val failed: Int = 0,
)
