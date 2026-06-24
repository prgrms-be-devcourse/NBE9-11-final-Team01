package com.develop.snaptix.domain.reservation.controller

import com.develop.snaptix.domain.auditlog.entity.AuditLogsTable
import com.develop.snaptix.domain.reservation.entity.ReservationStatus
import com.develop.snaptix.domain.reservation.reconcile.ReconcileFixtures
import com.develop.snaptix.domain.reservation.reconcile.ReconcileIntegrationSupport
import com.develop.snaptix.domain.user.entity.UserRole
import com.develop.snaptix.global.exception.ErrorCode
import com.develop.snaptix.global.redis.gateway.StockRedisGateway
import com.develop.snaptix.global.security.auth.AuthenticatedUser
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test

@SpringBootTest
@AutoConfigureMockMvc
class AdminReconcileControllerIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val stockRedisGateway: StockRedisGateway,
) : ReconcileIntegrationSupport() {
    @BeforeEach
    fun setUp() = ReconcileFixtures.cleanAll().let {}

    @Test
    fun `ADMIN 수동 정산 - 만료 PENDING이 RELEASED되고 재고 회수 + 감사 actorId 기록(end-to-end)`() {
        val adminId = ReconcileFixtures.insertUser()
        val event = ReconcileFixtures.insertEvent()
        val zone = ReconcileFixtures.insertZone(eventId = event.eventId, capacity = 100)
        val orderId =
            ReconcileFixtures.insertReservation(
                userId = adminId,
                eventId = event.eventId,
                zoneId = zone.zoneId,
                status = ReservationStatus.PENDING_PAYMENT,
                createdAt = Instant.now().minus(10, ChronoUnit.MINUTES),
            )
        // 워커가 차감해 claimed가 찬 상태 재현 (stock=0, claimed={orderId})
        stockRedisGateway.rebuild(zone.zoneId, 0, listOf(UUID.fromString(orderId)))

        mockMvc
            .post("/api/v1/admin/reconcile") {
                with(authentication(adminAuth(adminId)))
            }.andExpect {
                status { isOk() }
                jsonPath("$.released") { value(1) }
                jsonPath("$.compensated") { value(1) } // 실제 보상까지 검증
            }

        // end-to-end: DB RELEASED + Redis 재고 회수(게이트웨이로 읽기)
        assertThat(ReconcileFixtures.findStatus(orderId)).isEqualTo(ReservationStatus.RELEASED.name)
        assertThat(stockRedisGateway.get(zone.zoneId)).isEqualTo(1)
        // 감사: ADMIN_RECONCILE actorId
        val actorId =
            transaction {
                AuditLogsTable
                    .selectAll()
                    .where { AuditLogsTable.actionType eq "ADMIN_RECONCILE" }
                    .single()[AuditLogsTable.actorId]
            }
        assertThat(actorId).isEqualTo(adminId)
    }

    @Test
    fun `USER 권한은 수동 정산을 실행할 수 없다`() {
        val userId = ReconcileFixtures.insertUser()
        mockMvc
            .post("/api/v1/admin/reconcile") {
                with(authentication(userAuth(userId)))
            }.andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value(ErrorCode.ACCESS_DENIED.code) }
            }
        assertThat(ReconcileFixtures.countAudit("ADMIN_RECONCILE")).isEqualTo(0)
    }

    private fun adminAuth(id: Long) = UsernamePasswordAuthenticationToken(
        AuthenticatedUser(id, UserRole.ADMIN),
        null,
        listOf(SimpleGrantedAuthority("ROLE_ADMIN")),
    )

    private fun userAuth(id: Long) = UsernamePasswordAuthenticationToken(
        AuthenticatedUser(id, UserRole.USER),
        null,
        listOf(SimpleGrantedAuthority("ROLE_USER")),
    )
}
