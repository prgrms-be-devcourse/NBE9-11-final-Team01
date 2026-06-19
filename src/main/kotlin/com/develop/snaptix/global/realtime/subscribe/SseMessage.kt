package com.develop.snaptix.global.realtime.subscribe

/**
 * SSE Pub/Sub 발행 메시지(wire 포맷). 「SSE 발행 계약 명세서 §2」 단일 진실 소스.
 *
 * - [name]     : canonical 이벤트명 (READY_TO_PAY 등)
 * - [data]     : 클라이언트로 전달할 본문(없으면 null)
 * - [terminal] : 발행자가 설정. true면 구독 측이 전송 후 연결 종료(complete)
 */
data class SseMessage(
    val name: String,
    val data: Any?,
    val terminal: Boolean,
)
