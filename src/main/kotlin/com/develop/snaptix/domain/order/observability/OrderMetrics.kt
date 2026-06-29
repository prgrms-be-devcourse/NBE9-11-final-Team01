package com.develop.snaptix.domain.order.observability

/**
 * Order 도메인 Micrometer 메트릭 이름 상수 — 단일 진실 소스 (상위 명세서 §9 7종).
 *
 * 모든 메트릭 이름을 이 오브젝트로 집중해 호출부 문자열 오타와 분산을 방지한다.
 * `/actuator/metrics` 및 `/actuator/prometheus` 에서 아래 7개가 노출되어야 한다.
 */
object OrderMetrics {
    // ── §9 필수 7종 ────────────────────────────────────────────────────────

    /**
     * XADD 적재 건수 (Counter).
     * 인게스트 성공마다 +1. Prometheus rate() 로 처리량(TPS) 계산.
     */
    const val QUEUE_SIZE = "ticketing.order.queue.size"

    /**
     * 백프레셔 429 반환 수 (Counter).
     * XLEN ≥ 정원+α 시 +1. BackpressureGuard 에서 등록.
     */
    const val BACKPRESSURE_COUNT = "ticketing.order.backpressure.count"

    /**
     * PEL 적체 수 (Gauge).
     * XAUTOCLAIM 주기마다 갱신. 증가 추세 → 유실/워커 지연 조기 신호.
     */
    const val PENDING_SIZE = "ticketing.stream.pending.size"

    /**
     * XAUTOCLAIM 회수·재처리 수 (Counter).
     * OrphanReclaimer 에서 등록.
     */
    const val CLAIM_REPROCESS_COUNT = "ticketing.order.claim.reprocess.count"

    /**
     * 트림 유실(XAUTOCLAIM deletedIds 비어있지 않음) 수 (Counter).
     * 0 초과 즉시 CRITICAL. OrphanReclaimer 에서 등록.
     */
    const val DELETED_COUNT = "ticketing.stream.deleted.count"

    /**
     * 재고 보상 +1 회수 수 (Counter).
     * CompensationService 보상 성공마다 +1.
     */
    const val COMPENSATE_COUNT = "ticketing.stock.compensate.count"

    /**
     * 정상 처리 완료(XACK) 수 (Counter).
     * OrderStreamConsumer ACK 성공마다 +1.
     */
    const val XACK_COUNT = "ticketing.order.xack.count"
}
