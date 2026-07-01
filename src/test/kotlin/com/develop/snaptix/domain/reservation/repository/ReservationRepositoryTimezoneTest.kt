package com.develop.snaptix.domain.reservation.repository

import com.develop.snaptix.domain.event.entity.EventStatus
import com.develop.snaptix.domain.event.entity.EventsTable
import com.develop.snaptix.domain.user.entity.UserRole
import com.develop.snaptix.domain.user.entity.UsersTable
import com.develop.snaptix.domain.zone.entity.ZonesTable
import com.develop.snaptix.support.IntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * `reservations.created_at`이 DB 서버와 애플리케이션 사이의 시간대 불일치 없이
 * "지금"으로 기록되는지 검증하는 회귀 테스트. (이슈 #359 하위 이슈 — HoldExpiryWorker 조기 만료 버그)
 *
 * ## 배경
 * `created_at`은 [ReservationsTable]에서 `DEFAULT CURRENT_TIMESTAMP`(DB 서버 자체 시계)로
 * 채워진다. order-load 부하테스트(2026-07-01)에서 `application.yaml`/`application-loadtest.yaml`의
 * JDBC URL에 `serverTimezone=Asia/Seoul`이 고정돼 있었는데, 실제 MySQL 컨테이너는 TZ 미설정으로
 * UTC로 동작해 이 값이 9시간 어긋나게 해석됐다. 그 결과 `HoldExpiryWorker`가 생성된 지 1분도
 * 안 된 `PENDING_PAYMENT` 예약을 5분 홀드 만료 대상으로 오판해 재고를 즉시 반환하는 버그가 있었다.
 *
 * ## 이 테스트가 잡아내는 것
 * 예약 생성 직후 [ReservationRepository.findByOrderId]로 읽은 `createdAt`이 애플리케이션
 * 시계(`Instant.now()`) 기준 오차 허용 범위(초 단위) 안에 있는지 확인한다. 시간대 불일치가
 * 재발하면 이 값이 몇 시간 단위로 어긋나 테스트가 실패한다.
 *
 * ## 참고
 * [IntegrationTestSupport]는 Testcontainers `MySQLContainer`의 URL을
 * `@DynamicPropertySource`로 주입해 `spring.datasource.url`을 완전히 덮어쓰므로, 이 테스트는
 * `application.yaml`/`application-loadtest.yaml`에 적힌 JDBC URL 문자열 자체를 실행하지는
 * 않는다. 실제로 이 테스트를 처음 작성했을 때 `application.yaml`만 고치고 돌렸는데도 그대로
 * 재현됐다 — Testcontainers가 만드는 URL도 `connectionTimeZone`을 지정하지 않으면 기본값인
 * `LOCAL`(서버가 JDBC 클라이언트 JVM과 같은 시간대라고 가정)이 적용되어 동일한 9시간 드리프트가
 * 발생했기 때문이다. 그래서 [IntegrationTestSupport]의 `MySQLContainer`에도
 * `withUrlParam("connectionTimeZone", "SERVER")`를 추가해, 운영/부하테스트 설정과 테스트 환경이
 * 동일한 방식(서버에 직접 물어봄)으로 시간대를 해석하도록 맞췄다. 두 곳 다 고쳐야 이 테스트가
 * 통과한다.
 */
@SpringBootTest
class ReservationRepositoryTimezoneTest(
    @Autowired private val reservationRepository: ReservationRepository,
) : IntegrationTestSupport() {
    @Test
    fun `예약 생성 직후 created_at은 애플리케이션 시계 기준 현재 시각과 근접해야 한다`() {
        val userId = insertUser()
        val eventId = insertEvent()
        val zoneId = insertZone(eventId = eventId, capacity = 10)
        val orderId = UUID.randomUUID().toString()

        val before = Instant.now()
        reservationRepository.insertPending(
            orderId = orderId,
            userId = userId,
            internalEventId = eventId,
            zoneId = zoneId,
        )
        val after = Instant.now()

        val persisted = reservationRepository.findByOrderId(orderId)
        assertThat(persisted).isNotNull

        val createdAt = persisted!!.createdAt
        val drift = Duration.between(before, createdAt).abs()

        // 시간대 불일치 회귀(수 시간 단위 오차)를 잡아내되, DB 왕복·시계 정밀도 차이는 허용한다.
        assertThat(createdAt)
            .withFailMessage(
                "created_at이 애플리케이션 시계와 %s 만큼 어긋났습니다 (before=%s, createdAt=%s, after=%s). " +
                    "JDBC datasource URL의 serverTimezone 설정이 DB 서버의 실제 세션 시간대와 " +
                    "일치하는지 확인하세요.",
                drift,
                before,
                createdAt,
                after,
            ).isBetween(before.minus(TOLERANCE), after.plus(TOLERANCE))
    }

    private fun insertUser(): Long = transaction {
        UsersTable.insert {
            it[UsersTable.email] = "tz-regression-${UUID.randomUUID()}@test.com"
            it[UsersTable.password] = "encoded-password"
            it[UsersTable.role] = UserRole.USER.name
        }[UsersTable.id]
    }

    private fun insertEvent(status: EventStatus = EventStatus.ON_SALE): Long = transaction {
        val now = Instant.now()
        EventsTable.insert {
            it[EventsTable.publicId] = UUID.randomUUID().toString()
            it[EventsTable.name] = "TZ Regression Test Event"
            it[EventsTable.location] = "Test Hall"
            it[EventsTable.startTime] = now
            it[EventsTable.endTime] = now.plusSeconds(EVENT_DURATION_SECONDS)
            it[EventsTable.status] = status.name
        }[EventsTable.id]
    }

    private fun insertZone(
        eventId: Long,
        capacity: Int,
    ): Long = transaction {
        ZonesTable.insert {
            it[ZonesTable.publicId] = UUID.randomUUID().toString()
            it[ZonesTable.eventId] = eventId
            it[ZonesTable.name] = "TZ-A"
            it[ZonesTable.unitPrice] = 10_000
            it[ZonesTable.totalCapacity] = capacity
        }[ZonesTable.id]
    }

    companion object {
        private const val EVENT_DURATION_SECONDS = 10_800L
        private val TOLERANCE: Duration = Duration.ofSeconds(10)
    }
}
