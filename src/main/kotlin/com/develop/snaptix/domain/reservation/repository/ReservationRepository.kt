package com.develop.snaptix.domain.reservation.repository

import com.develop.snaptix.domain.reservation.entity.ReservationStatus
import com.develop.snaptix.domain.reservation.entity.ReservationsTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.stereotype.Repository

/**
 * Exposed 기반 [ReservationQuery] 구현.
 * orderId(UNIQUE)로 예약 단건을 읽어 소유권·재구성에 필요한 [ReservationView]로 매핑한다.
 *
 * NOTE: `transaction {}`은 Exposed Database 연결이 구성돼 있다고 가정한다(스프링 Exposed 스타터).
 *       reservation 도메인 본개발 시 트랜잭션 경계/조회 관용구를 도메인 컨벤션에 맞춰 통합한다.
 */
@Repository
class ReservationRepository : ReservationQuery {
    override fun findByOrderId(orderId: String): ReservationView? =
        transaction {
            ReservationsTable
                .selectAll()
                .where { ReservationsTable.orderId eq orderId }
                .limit(1)
                .map { row ->
                    ReservationView(
                        userId = row[ReservationsTable.userId],
                        status = ReservationStatus.valueOf(row[ReservationsTable.status]),
                        createdAt = row[ReservationsTable.createdAt],
                    )
                }.singleOrNull()
        }
}
