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
}
