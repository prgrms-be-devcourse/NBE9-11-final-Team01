package com.develop.snaptix.domain.reservation.repository

import com.develop.snaptix.domain.reservation.entity.ReservationStatus
import com.develop.snaptix.support.IntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.Instant
import java.util.UUID

@SpringBootTest
@DisplayName("OrderReservationRepository (주문 6a 파이프라인 영속성) 테스트")
class OrderReservationRepositoryTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var sut: ReservationRepository

    private var testUserId: Long = 0L
    private var testEventId: Long = 0L
    private var testZoneId: Long = 0L

    @BeforeEach
    fun setUp() {
        testUserId = OrderRepositoryFixtures.insertOrderTestUser()
        testEventId = OrderRepositoryFixtures.insertOrderTestEvent()
        testZoneId = OrderRepositoryFixtures.insertOrderTestZone(testEventId)
    }

    @Nested
    @DisplayName("findInternalEventId()")
    inner class FindInternalEventIdTest {
        @Test
        @DisplayName("존재하는 zoneId를 조회하면 올바른 내부 eventId(Long)를 반환한다")
        fun `returns internal eventId when zoneId exists`() {
            val result = sut.findInternalEventId(testZoneId)

            assertThat(result).isNotNull
            assertThat(result).isEqualTo(testEventId)
        }

        @Test
        @DisplayName("존재하지 않는 zoneId를 조회하면 null을 반환한다")
        fun `returns null when zoneId does not exist`() {
            val nonExistentZoneId = 999999L

            val result = sut.findInternalEventId(nonExistentZoneId)

            assertThat(result).isNull()
        }
    }

    @Nested
    @DisplayName("existsActiveForUserAndEvent() - 1인 1매 검증")
    inner class ExistsActiveForUserAndEventTest {
        @Test
        @DisplayName("유효 점유(PENDING_PAYMENT) 상태의 예약이 있으면 true를 반환한다")
        fun `returns true if PENDING_PAYMENT reservation exists`() {
            OrderRepositoryFixtures.insertOrderTestReservation(
                orderId = UUID.randomUUID().toString(),
                userId = testUserId,
                eventId = testEventId,
                zoneId = testZoneId,
                status = ReservationStatus.PENDING_PAYMENT,
            )

            val isDuplicate = sut.existsActiveForUserAndEvent(testUserId, testEventId)

            assertThat(isDuplicate).isTrue()
        }

        @Test
        @DisplayName("유효 점유(CONFIRMED) 상태의 예약이 있으면 true를 반환한다")
        fun `returns true if CONFIRMED reservation exists`() {
            OrderRepositoryFixtures.insertOrderTestReservation(
                orderId = UUID.randomUUID().toString(),
                userId = testUserId,
                eventId = testEventId,
                zoneId = testZoneId,
                status = ReservationStatus.CONFIRMED,
            )

            val isDuplicate = sut.existsActiveForUserAndEvent(testUserId, testEventId)

            assertThat(isDuplicate).isTrue()
        }

        @Test
        @DisplayName("취소된(CANCELLED, RELEASED) 예약만 존재하면 false를 반환한다")
        fun `returns false if only inactive reservations exist`() {
            OrderRepositoryFixtures.insertOrderTestReservation(
                orderId = UUID.randomUUID().toString(),
                userId = testUserId,
                eventId = testEventId,
                zoneId = testZoneId,
                status = ReservationStatus.CANCELLED,
            )
            OrderRepositoryFixtures.insertOrderTestReservation(
                orderId = UUID.randomUUID().toString(),
                userId = testUserId,
                eventId = testEventId,
                zoneId = testZoneId,
                status = ReservationStatus.RELEASED,
            )

            val isDuplicate = sut.existsActiveForUserAndEvent(testUserId, testEventId)

            assertThat(isDuplicate).isFalse()
        }
    }

    @Nested
    @DisplayName("insertPending() - PENDING_PAYMENT 삽입")
    inner class InsertPendingTest {
        @Test
        @DisplayName("정상적인 파라미터가 주어지면 DB에 데이터가 삽입된다")
        fun `inserts new reservation successfully`() {
            val orderId = UUID.randomUUID().toString()

            sut.insertPending(
                orderId = orderId,
                userId = testUserId,
                internalEventId = testEventId,
                zoneId = testZoneId,
            )

            val insertedReservation = sut.findByOrderId(orderId)
            assertThat(insertedReservation).isNotNull
            assertThat(insertedReservation?.userId).isEqualTo(testUserId)
            assertThat(insertedReservation?.status).isEqualTo(ReservationStatus.PENDING_PAYMENT)
        }

        @Test
        @DisplayName("동일한 orderId로 삽입을 시도하면 UNIQUE 제약조건(ExposedSQLException)이 발생한다")
        fun `throws Exception on duplicate orderId`() {
            val orderId = UUID.randomUUID().toString()

            // 첫 번째 삽입 성공
            sut.insertPending(orderId, testUserId, testEventId, testZoneId)

            // 동일한 orderId로 두 번째 삽입 시 예외 발생
            assertThatThrownBy {
                sut.insertPending(orderId, testUserId, testEventId, testZoneId)
            }.isInstanceOf(ExposedSQLException::class.java)
        }
    }

    @Nested
    @DisplayName("findIdempotencyContextByOrderId() - 멱등 키 컨텍스트 조회")
    inner class FindIdempotencyContextByOrderIdTest {
        @Test
        @DisplayName("존재하는 orderId로 조회하면 userId와 internalEventId를 반환한다")
        fun `returns userId and internalEventId when orderId exists`() {
            val orderId = UUID.randomUUID().toString()
            OrderRepositoryFixtures.insertOrderTestReservation(
                orderId = orderId,
                userId = testUserId,
                eventId = testEventId,
                zoneId = testZoneId,
                status = ReservationStatus.PENDING_PAYMENT,
            )

            val context = sut.findIdempotencyContextByOrderId(orderId)

            assertThat(context).isNotNull
            assertThat(context!!.userId).isEqualTo(testUserId)
            assertThat(context.internalEventId).isEqualTo(testEventId)
        }

        @Test
        @DisplayName("존재하지 않는 orderId로 조회하면 null을 반환한다")
        fun `returns null when orderId does not exist`() {
            val nonExistentOrderId = UUID.randomUUID().toString()

            val context = sut.findIdempotencyContextByOrderId(nonExistentOrderId)

            assertThat(context).isNull()
        }

        @Test
        @DisplayName("여러 예약이 있어도 지정한 orderId의 컨텍스트만 반환한다")
        fun `returns context only for the specified orderId`() {
            val targetOrderId = UUID.randomUUID().toString()
            val otherOrderId = UUID.randomUUID().toString()
            val otherUserId = OrderRepositoryFixtures.insertOrderTestUser()
            val otherEventId = OrderRepositoryFixtures.insertOrderTestEvent()
            val otherZoneId = OrderRepositoryFixtures.insertOrderTestZone(otherEventId)

            OrderRepositoryFixtures.insertOrderTestReservation(
                orderId = targetOrderId,
                userId = testUserId,
                eventId = testEventId,
                zoneId = testZoneId,
                status = ReservationStatus.PENDING_PAYMENT,
            )
            OrderRepositoryFixtures.insertOrderTestReservation(
                orderId = otherOrderId,
                userId = otherUserId,
                eventId = otherEventId,
                zoneId = otherZoneId,
                status = ReservationStatus.PENDING_PAYMENT,
            )

            val context = sut.findIdempotencyContextByOrderId(targetOrderId)

            assertThat(context!!.userId).isEqualTo(testUserId)
            assertThat(context.internalEventId).isEqualTo(testEventId)
        }

        @Test
        @DisplayName("CONFIRMED 상태의 예약도 orderId로 조회할 수 있다")
        fun `returns context regardless of reservation status`() {
            val orderId = UUID.randomUUID().toString()
            OrderRepositoryFixtures.insertOrderTestReservation(
                orderId = orderId,
                userId = testUserId,
                eventId = testEventId,
                zoneId = testZoneId,
                status = ReservationStatus.CONFIRMED,
            )

            val context = sut.findIdempotencyContextByOrderId(orderId)

            assertThat(context).isNotNull
            assertThat(context!!.userId).isEqualTo(testUserId)
            assertThat(context.internalEventId).isEqualTo(testEventId)
        }
    }

    @Nested
    @DisplayName("findExpiredPendingPaged() - 만료 PENDING 배치 조회")
    inner class FindExpiredPendingPagedTest {
        private val holdTimeout = java.time.Duration.ofMinutes(5)

        @Test
        @DisplayName("cutoff 이전에 생성된 PENDING_PAYMENT 예약을 반환한다")
        fun `returns PENDING_PAYMENT reservation created before cutoff`() {
            val orderId = UUID.randomUUID().toString()
            val expiredAt = Instant.now().minus(holdTimeout).minusSeconds(1)
            OrderRepositoryFixtures.insertOrderTestReservation(
                orderId = orderId,
                userId = testUserId,
                eventId = testEventId,
                zoneId = testZoneId,
                status = ReservationStatus.PENDING_PAYMENT,
                createdAt = expiredAt,
            )

            val result = sut.findExpiredPendingPaged(Instant.now().minus(holdTimeout), 100)

            assertThat(result).hasSize(1)
            assertThat(result.first().orderId).isEqualTo(orderId)
        }

        @Test
        @DisplayName("cutoff 이후에 생성된 PENDING_PAYMENT 예약은 반환하지 않는다 (아직 유효)")
        fun `does not return PENDING_PAYMENT reservation created after cutoff`() {
            OrderRepositoryFixtures.insertOrderTestReservation(
                orderId = UUID.randomUUID().toString(),
                userId = testUserId,
                eventId = testEventId,
                zoneId = testZoneId,
                status = ReservationStatus.PENDING_PAYMENT,
                createdAt = Instant.now(), // 방금 생성 → 아직 만료 아님
            )

            val result = sut.findExpiredPendingPaged(Instant.now().minus(holdTimeout), 100)

            assertThat(result).isEmpty()
        }

        @Test
        @DisplayName("PENDING_PAYMENT가 아닌 상태(CONFIRMED, RELEASED, CANCELLED)는 반환하지 않는다")
        fun `does not return non-PENDING_PAYMENT reservations even if expired`() {
            val expiredAt = Instant.now().minus(holdTimeout).minusSeconds(10)
            listOf(
                ReservationStatus.CONFIRMED,
                ReservationStatus.RELEASED,
                ReservationStatus.CANCELLED,
            ).forEach { status ->
                OrderRepositoryFixtures.insertOrderTestReservation(
                    orderId = UUID.randomUUID().toString(),
                    userId = testUserId,
                    eventId = testEventId,
                    zoneId = testZoneId,
                    status = status,
                    createdAt = expiredAt,
                )
            }

            val result = sut.findExpiredPendingPaged(Instant.now().minus(holdTimeout), 100)

            assertThat(result).isEmpty()
        }

        @Test
        @DisplayName("만료 건수가 limit를 초과하면 limit 개수만 반환한다")
        fun `returns only up to limit when expired count exceeds limit`() {
            val expiredAt = Instant.now().minus(holdTimeout).minusSeconds(10)
            repeat(5) {
                OrderRepositoryFixtures.insertOrderTestReservation(
                    orderId = UUID.randomUUID().toString(),
                    userId = testUserId,
                    eventId = testEventId,
                    zoneId = testZoneId,
                    status = ReservationStatus.PENDING_PAYMENT,
                    createdAt = expiredAt,
                )
            }

            val result = sut.findExpiredPendingPaged(Instant.now().minus(holdTimeout), 3)

            assertThat(result).hasSize(3)
        }

        @Test
        @DisplayName("결과에 id, orderId, zoneId가 올바르게 포함된다")
        fun `result contains correct id, orderId, and zoneId fields`() {
            val orderId = UUID.randomUUID().toString()
            val reservationId =
                OrderRepositoryFixtures.insertOrderTestReservation(
                    orderId = orderId,
                    userId = testUserId,
                    eventId = testEventId,
                    zoneId = testZoneId,
                    status = ReservationStatus.PENDING_PAYMENT,
                    createdAt = Instant.now().minus(holdTimeout).minusSeconds(1),
                )

            val result = sut.findExpiredPendingPaged(Instant.now().minus(holdTimeout), 100)

            assertThat(result).hasSize(1)
            with(result.first()) {
                assertThat(id).isEqualTo(reservationId)
                assertThat(this.orderId).isEqualTo(orderId)
                assertThat(zoneId).isEqualTo(testZoneId)
            }
        }
    }

    @Nested
    @DisplayName("releaseIfPending() - 조건부 RELEASED 전이")
    inner class ReleaseIfPendingTest {
        @Test
        @DisplayName("PENDING_PAYMENT 예약을 RELEASED로 전이하고 affected=1을 반환한다")
        fun `transitions PENDING_PAYMENT to RELEASED and returns 1`() {
            val orderId = UUID.randomUUID().toString()
            val id =
                OrderRepositoryFixtures.insertOrderTestReservation(
                    orderId = orderId,
                    userId = testUserId,
                    eventId = testEventId,
                    zoneId = testZoneId,
                    status = ReservationStatus.PENDING_PAYMENT,
                )

            val affected = sut.releaseIfPending(id)

            assertThat(affected).isEqualTo(1)
            assertThat(sut.findByOrderId(orderId)?.status).isEqualTo(ReservationStatus.RELEASED)
        }

        @Test
        @DisplayName("이미 CONFIRMED인 예약은 변경하지 않고 affected=0을 반환한다 (결제 성공 경합)")
        fun `does not change CONFIRMED reservation and returns 0`() {
            val orderId = UUID.randomUUID().toString()
            val id =
                OrderRepositoryFixtures.insertOrderTestReservation(
                    orderId = orderId,
                    userId = testUserId,
                    eventId = testEventId,
                    zoneId = testZoneId,
                    status = ReservationStatus.CONFIRMED,
                )

            val affected = sut.releaseIfPending(id)

            assertThat(affected).isEqualTo(0)
            assertThat(sut.findByOrderId(orderId)?.status).isEqualTo(ReservationStatus.CONFIRMED)
        }

        @Test
        @DisplayName("이미 RELEASED인 예약에 재호출하면 affected=0을 반환한다 (멱등)")
        fun `returns 0 on second call for already RELEASED reservation (idempotent)`() {
            val id =
                OrderRepositoryFixtures.insertOrderTestReservation(
                    orderId = UUID.randomUUID().toString(),
                    userId = testUserId,
                    eventId = testEventId,
                    zoneId = testZoneId,
                    status = ReservationStatus.RELEASED,
                )

            val affected = sut.releaseIfPending(id)

            assertThat(affected).isEqualTo(0)
        }

        @Test
        @DisplayName("첫 번째 호출은 affected=1, 두 번째 호출은 affected=0 (이중 릴리즈 방지)")
        fun `first call returns 1, second call returns 0`() {
            val id =
                OrderRepositoryFixtures.insertOrderTestReservation(
                    orderId = UUID.randomUUID().toString(),
                    userId = testUserId,
                    eventId = testEventId,
                    zoneId = testZoneId,
                    status = ReservationStatus.PENDING_PAYMENT,
                )

            val first = sut.releaseIfPending(id)
            val second = sut.releaseIfPending(id)

            assertThat(first).isEqualTo(1)
            assertThat(second).isEqualTo(0)
        }
    }
}
