package com.develop.snaptix.domain.ticket.repository

import com.develop.snaptix.domain.ticket.entity.TicketStatus
import com.develop.snaptix.domain.ticket.entity.TicketsTable
import com.develop.snaptix.support.IntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.util.UUID

/**
 * [TicketRepository] 통합 테스트.
 *
 * ## 전략
 * - Testcontainers(MySQL)로 실제 DB 제약 조건까지 검증한다.
 * - [TicketRepository.insert]는 테스트 픽스처 전용이므로 별도 검증하지 않는다.
 * - 픽스처는 [TicketFixtures] 전용 헬퍼를 사용한다 ([ReconcileFixtures] 미사용).
 *
 * ## 커버하는 계약
 * ### issue()
 * 1. ISSUED 상태, UUID ticketCode, issuedAt 설정 검증
 * 2. 반환값이 삽입된 ticketCode와 동일함
 * 3. 중복 reservationId(UNIQUE 제약) → 예외 발생
 *
 * ### findTicketCodeByOrderId() (PR-13)
 * 4. 발권 완료된 orderId 조회 시 ticketCode 반환
 * 5. 발권 전 orderId 조회 시 null 반환
 * 6. 존재하지 않는 orderId 조회 시 null 반환
 *
 * ### findByTicketCode()
 * 7. 존재하는 ticketCode 조회 시 TicketRecord 반환 (status, reservationId, issuedAt 포함)
 * 8. 존재하지 않는 ticketCode 조회 시 null 반환
 *
 * ### findById()
 * 9. 존재하는 id 조회 시 TicketRecord 반환 (id, ticketCode, status 포함)
 * 10. 존재하지 않는 id 조회 시 null 반환
 */
@SpringBootTest
@DisplayName("TicketRepository 통합 테스트")
class TicketRepositoryTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var sut: TicketRepository

    private var reservationId: Long = -1L

    @BeforeEach
    fun setUpFixture() {
        reservationId = TicketFixtures.insertReservationAndGetId()
    }

    // ════════════════════════════════════════════════════════════════════════════
    // issue() — 정상 발권
    // ════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("issue() — 정상 발권 (happy path)")
    inner class HappyPath {
        @Test
        @DisplayName("issue()는 삽입된 ticketCode를 반환한다")
        fun `returns the inserted ticketCode`() {
            val ticketCode = sut.issue(reservationId)

            assertThat(ticketCode).isNotBlank()
            assertThat(UUID.fromString(ticketCode)).isNotNull() // UUID 형식 검증
        }

        @Test
        @DisplayName("tickets 행의 status는 ISSUED이다")
        fun `inserted ticket has ISSUED status`() {
            val ticketCode = sut.issue(reservationId)

            assertThat(findStatusByTicketCode(ticketCode)).isEqualTo(TicketStatus.ISSUED.name)
        }

        @Test
        @DisplayName("tickets 행의 issuedAt이 설정된다")
        fun `inserted ticket has issuedAt set`() {
            val ticketCode = sut.issue(reservationId)

            assertThat(findIssuedAtByTicketCode(ticketCode)).isNotNull()
        }

        @Test
        @DisplayName("tickets 행의 reservationId가 올바르게 저장된다")
        fun `inserted ticket has correct reservationId`() {
            val ticketCode = sut.issue(reservationId)

            assertThat(findReservationIdByTicketCode(ticketCode)).isEqualTo(reservationId)
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // issue() — DB 제약 조건
    // ════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("issue() — DB 제약 조건")
    inner class Constraints {
        @Test
        @DisplayName("동일 reservationId로 두 번 발권하면 UNIQUE 제약 위반이 발생한다")
        fun `duplicate reservationId throws unique constraint violation`() {
            sut.issue(reservationId) // 첫 번째 발권 성공

            assertThatThrownBy { sut.issue(reservationId) }
                .isInstanceOf(Exception::class.java)
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // findTicketCodeByOrderId() — orderId → ticketCode 조회 (PR-13)
    // ════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("findTicketCodeByOrderId() — orderId → ticketCode 조회")
    inner class FindTicketCodeByOrderId {
        @Test
        @DisplayName("발권 완료된 orderId 조회 시 ticketCode를 반환한다")
        fun `returns ticketCode after ticket is issued`() {
            val fixture = TicketFixtures.insertReservationAndGetFixture()
            val issuedCode = sut.issue(fixture.reservationId)

            val result = sut.findTicketCodeByOrderId(fixture.orderId)

            assertThat(result).isEqualTo(issuedCode)
        }

        @Test
        @DisplayName("발권 전 orderId 조회 시 null을 반환한다")
        fun `returns null when ticket not yet issued`() {
            val fixture = TicketFixtures.insertReservationAndGetFixture()
            // issue() 미호출 — tickets 행 없음

            val result = sut.findTicketCodeByOrderId(fixture.orderId)

            assertThat(result).isNull()
        }

        @Test
        @DisplayName("존재하지 않는 orderId 조회 시 null을 반환한다")
        fun `returns null when orderId does not exist`() {
            val result = sut.findTicketCodeByOrderId("non-existent-order-id")

            assertThat(result).isNull()
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // findByTicketCode() — ticketCode → TicketRecord 조회
    // ════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("findByTicketCode() — ticketCode → TicketRecord 조회")
    inner class FindByTicketCode {
        @Test
        @DisplayName("존재하는 ticketCode 조회 시 TicketRecord를 반환한다")
        fun `returns TicketRecord when ticketCode exists`() {
            val ticketCode = sut.issue(reservationId)

            val result = sut.findByTicketCode(ticketCode)

            assertThat(result).isNotNull()
            assertThat(result!!.ticketCode).isEqualTo(ticketCode)
            assertThat(result.status).isEqualTo(TicketStatus.ISSUED.name)
            assertThat(result.reservationId).isEqualTo(reservationId)
            assertThat(result.issuedAt).isNotNull()
            assertThat(result.usedAt).isNull()
        }

        @Test
        @DisplayName("존재하지 않는 ticketCode 조회 시 null을 반환한다")
        fun `returns null when ticketCode does not exist`() {
            val result = sut.findByTicketCode("non-existent-ticket-code")

            assertThat(result).isNull()
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // findById() — id → TicketRecord 조회
    // ════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("findById() — id → TicketRecord 조회")
    inner class FindById {
        @Test
        @DisplayName("존재하는 id 조회 시 TicketRecord를 반환한다")
        fun `returns TicketRecord when id exists`() {
            val ticketCode = sut.issue(reservationId)
            val ticketId = findIdByTicketCode(ticketCode)!!

            val result = sut.findById(ticketId)

            assertThat(result).isNotNull()
            assertThat(result!!.id).isEqualTo(ticketId)
            assertThat(result.ticketCode).isEqualTo(ticketCode)
            assertThat(result.status).isEqualTo(TicketStatus.ISSUED.name)
            assertThat(result.reservationId).isEqualTo(reservationId)
        }

        @Test
        @DisplayName("존재하지 않는 id 조회 시 null을 반환한다")
        fun `returns null when id does not exist`() {
            val result = sut.findById(Long.MAX_VALUE)

            assertThat(result).isNull()
        }
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private fun findStatusByTicketCode(ticketCode: String): String? = transaction {
        TicketsTable
            .selectAll()
            .where { TicketsTable.ticketCode eq ticketCode }
            .singleOrNull()
            ?.get(TicketsTable.status)
    }

    private fun findIssuedAtByTicketCode(ticketCode: String) = transaction {
        TicketsTable
            .selectAll()
            .where { TicketsTable.ticketCode eq ticketCode }
            .singleOrNull()
            ?.get(TicketsTable.issuedAt)
    }

    private fun findReservationIdByTicketCode(ticketCode: String): Long? = transaction {
        TicketsTable
            .selectAll()
            .where { TicketsTable.ticketCode eq ticketCode }
            .singleOrNull()
            ?.get(TicketsTable.reservationId)
    }

    private fun findIdByTicketCode(ticketCode: String): Long? = transaction {
        TicketsTable
            .selectAll()
            .where { TicketsTable.ticketCode eq ticketCode }
            .singleOrNull()
            ?.get(TicketsTable.id)
    }
}
