package com.develop.snaptix.domain.order.api.service

import com.develop.snaptix.domain.order.api.dto.OrderRequest
import com.develop.snaptix.global.exception.BusinessException
import com.develop.snaptix.global.exception.ErrorCode
import com.develop.snaptix.global.redis.gateway.EventCacheRedisGateway
import com.develop.snaptix.global.redis.gateway.IdempotencyRedisGateway
import com.develop.snaptix.global.redis.gateway.OrderStreamGateway
import com.develop.snaptix.global.redis.gateway.OwnershipRedisGateway
import com.develop.snaptix.global.redis.gateway.RateLimitRedisGateway
import com.develop.snaptix.global.redis.gateway.RateLimitResult
import com.develop.snaptix.global.redis.gateway.StockRedisGateway
import com.develop.snaptix.global.redis.gateway.schema.EventInfo
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

@DisplayName("OrderIngestService 단위 테스트")
class OrderIngestServiceTest {
    // ── 의존성 mock ─────────────────────────────────────────────────────────────
    private val orderRateLimiter = mockk<RateLimitRedisGateway>()
    private val eventCacheGateway = mockk<EventCacheRedisGateway>()
    private val stockRedisGateway = mockk<StockRedisGateway>()
    private val backpressureGuard = mockk<BackpressureGuard>()
    private val idempotencyGateway = mockk<IdempotencyRedisGateway>()
    private val orderStreamGateway = mockk<OrderStreamGateway>()
    private val ownershipRedisGateway = mockk<OwnershipRedisGateway>()

    private lateinit var sut: OrderIngestService

    // ── 공통 픽스처 ─────────────────────────────────────────────────────────────
    private val userId = 1L
    private val ip = "192.168.0.1"
    private val eventPublicId: UUID = UUID.randomUUID()
    private val zoneId = 10L
    private val request = OrderRequest(eventId = eventPublicId.toString(), zoneId = zoneId)

    // EventInfo.status 만 사용하므로 relaxed mock으로 생성 후 status만 명시 스텁
    private val onSaleEventInfo = mockk<EventInfo>(relaxed = true)

    @BeforeEach
    fun setUp() {
        sut =
            OrderIngestService(
                orderRateLimiter = orderRateLimiter,
                eventCacheGateway = eventCacheGateway,
                stockRedisGateway = stockRedisGateway,
                backpressureGuard = backpressureGuard,
                idempotencyGateway = idempotencyGateway,
                orderStreamGateway = orderStreamGateway,
                ownershipRedisGateway = ownershipRedisGateway,
            )

        // 정상 흐름 기본 스텁 — 각 @Nested 에서 필요한 단계만 오버라이드
        every { onSaleEventInfo.status } returns "ON_SALE"
        every { orderRateLimiter.hit(any(), any(), any()) } returns RateLimitResult(allowed = true, retryAfter = null)
        every { eventCacheGateway.get(any()) } returns onSaleEventInfo
        every { stockRedisGateway.get(zoneId) } returns 100
        every { backpressureGuard.check(any(), any()) } just runs
        every { idempotencyGateway.tryAcquire(any(), any(), any()) } returns true
        every { ownershipRedisGateway.set(any(), any()) } just runs
        every { orderStreamGateway.add(any(), any()) } returns "1700000000000-0"
        every { idempotencyGateway.compareAndDelete(any(), any(), any()) } returns true
        every { ownershipRedisGateway.delete(any()) } just runs
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 정상 흐름
    // ════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("정상 흐름")
    inner class HappyPath {
        @Test
        @DisplayName("모든 단계 통과 시 orderId·sseUrl·statusUrl 을 담은 응답을 반환한다")
        fun `returns response with orderId sseUrl statusUrl when all steps pass`() {
            val response = sut.ingest(userId, request, ip)

            assertThat(response.orderId).isNotBlank()
            assertThat(response.sseUrl).contains(response.orderId)
            assertThat(response.statusUrl).contains(response.orderId)
        }

        @Test
        @DisplayName("XADD payload 에 orderId·userId·eventId·zoneId 가 모두 포함된다")
        fun `XADD payload contains orderId userId eventId zoneId`() {
            val payloadSlot = slot<Map<String, String>>()
            every { orderStreamGateway.add(any(), capture(payloadSlot)) } returns "msg-id"

            val response = sut.ingest(userId, request, ip)
            val payload = payloadSlot.captured

            assertThat(payload).containsKey("orderId")
            assertThat(payload["orderId"]).isEqualTo(response.orderId)
            assertThat(payload["userId"]).isEqualTo(userId.toString())
            assertThat(payload["eventId"]).isEqualTo(eventPublicId.toString())
            assertThat(payload["zoneId"]).isEqualTo(zoneId.toString())
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 1단계: Rate Limiting
    // ════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("1단계 — Rate Limiting")
    inner class RateLimiting {
        @Test
        @DisplayName("초당 한도 초과 시 RATE_LIMIT_EXCEEDED 를 던진다")
        fun `throws RATE_LIMIT_EXCEEDED when per-second limit is exceeded`() {
            every { orderRateLimiter.hit(ip, 5, 20) } returns
                RateLimitResult(allowed = false, retryAfter = Duration.ofSeconds(1))

            assertThatThrownBy { sut.ingest(userId, request, ip) }
                .isInstanceOf(BusinessException::class.java)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.RATE_LIMIT_EXCEEDED)
        }

        @Test
        @DisplayName("분당 한도 초과 시 RATE_LIMIT_EXCEEDED 를 던진다")
        fun `throws RATE_LIMIT_EXCEEDED when per-minute limit is exceeded`() {
            every { orderRateLimiter.hit(ip, 5, 20) } returns
                RateLimitResult(allowed = false, retryAfter = Duration.ofMinutes(1))

            assertThatThrownBy { sut.ingest(userId, request, ip) }
                .isInstanceOf(BusinessException::class.java)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.RATE_LIMIT_EXCEEDED)
        }

        @Test
        @DisplayName("Rate Limit 차단 시 이후 단계(이벤트 조회·멱등 등)를 호출하지 않는다")
        fun `does not proceed past rate limiting when blocked`() {
            every { orderRateLimiter.hit(any(), any(), any()) } returns
                RateLimitResult(allowed = false, retryAfter = Duration.ofSeconds(1))

            runCatching { sut.ingest(userId, request, ip) }

            verify(exactly = 0) { eventCacheGateway.get(any()) }
            verify(exactly = 0) { idempotencyGateway.tryAcquire(any(), any(), any()) }
            verify(exactly = 0) { orderStreamGateway.add(any(), any()) }
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 2단계: 이벤트 상태 검사
    // ════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("2단계 — 이벤트 상태 검사")
    inner class EventStatusCheck {
        @Test
        @DisplayName("이벤트 캐시 미스 시 RECONCILE_FAILED 를 던진다")
        fun `throws RECONCILE_FAILED when event cache is missing`() {
            every { eventCacheGateway.get(any()) } returns null

            assertThatThrownBy { sut.ingest(userId, request, ip) }
                .isInstanceOf(BusinessException::class.java)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.RECONCILE_FAILED)
        }

        @Test
        @DisplayName("이벤트 상태가 ON_SALE 이 아니면 INVALID_REQUEST_PARAMETER 를 던진다")
        fun `throws INVALID_REQUEST_PARAMETER when event status is not ON_SALE`() {
            val closedEventInfo = mockk<EventInfo>(relaxed = true)
            every { closedEventInfo.status } returns "CLOSED"
            every { eventCacheGateway.get(any()) } returns closedEventInfo

            assertThatThrownBy { sut.ingest(userId, request, ip) }
                .isInstanceOf(BusinessException::class.java)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_REQUEST_PARAMETER)
        }

        @Test
        @DisplayName("PENDING 상태 이벤트도 ON_SALE 이 아니므로 INVALID_REQUEST_PARAMETER 를 던진다")
        fun `throws INVALID_REQUEST_PARAMETER when event status is PENDING`() {
            val pendingEventInfo = mockk<EventInfo>(relaxed = true)
            every { pendingEventInfo.status } returns "PENDING"
            every { eventCacheGateway.get(any()) } returns pendingEventInfo

            assertThatThrownBy { sut.ingest(userId, request, ip) }
                .isInstanceOf(BusinessException::class.java)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_REQUEST_PARAMETER)
        }

        @Test
        @DisplayName("재고 캐시 미스 시 RECONCILE_FAILED 를 던진다")
        fun `throws RECONCILE_FAILED when stock cache is missing`() {
            every { stockRedisGateway.get(zoneId) } returns null

            assertThatThrownBy { sut.ingest(userId, request, ip) }
                .isInstanceOf(BusinessException::class.java)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.RECONCILE_FAILED)
        }

        @Test
        @DisplayName("이벤트 상태 오류 시 백프레셔·멱등 단계를 호출하지 않는다")
        fun `does not proceed to backpressure or idempotency when event is not on sale`() {
            val notOnSale = mockk<EventInfo>(relaxed = true)
            every { notOnSale.status } returns "SOLD_OUT"
            every { eventCacheGateway.get(any()) } returns notOnSale

            runCatching { sut.ingest(userId, request, ip) }

            verify(exactly = 0) { backpressureGuard.check(any(), any()) }
            verify(exactly = 0) { idempotencyGateway.tryAcquire(any(), any(), any()) }
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 3단계: 백프레셔
    // ════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("3단계 — 백프레셔")
    inner class BackpressureCheck {
        @Test
        @DisplayName("큐 포화 시 QUEUE_CAPACITY_EXCEEDED 를 던진다")
        fun `throws QUEUE_CAPACITY_EXCEEDED when queue is saturated`() {
            every { backpressureGuard.check(any(), any()) } throws
                BusinessException(ErrorCode.QUEUE_CAPACITY_EXCEEDED)

            assertThatThrownBy { sut.ingest(userId, request, ip) }
                .isInstanceOf(BusinessException::class.java)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.QUEUE_CAPACITY_EXCEEDED)
        }

        @Test
        @DisplayName("백프레셔 차단 시 멱등 키 선점을 시도하지 않는다")
        fun `does not attempt idempotency acquire when backpressure blocks`() {
            every { backpressureGuard.check(any(), any()) } throws
                BusinessException(ErrorCode.QUEUE_CAPACITY_EXCEEDED)

            runCatching { sut.ingest(userId, request, ip) }

            verify(exactly = 0) { idempotencyGateway.tryAcquire(any(), any(), any()) }
            verify(exactly = 0) { orderStreamGateway.add(any(), any()) }
        }

        @Test
        @DisplayName("RATE_LIMIT_EXCEEDED 와 QUEUE_CAPACITY_EXCEEDED 는 서로 다른 에러 코드를 반환한다")
        fun `RATE_LIMIT_EXCEEDED and QUEUE_CAPACITY_EXCEEDED are distinct error codes`() {
            // Rate Limit → RL_001
            every { orderRateLimiter.hit(any(), any(), any()) } returns
                RateLimitResult(allowed = false, retryAfter = Duration.ofSeconds(1))
            val rateLimitException =
                runCatching { sut.ingest(userId, request, ip) }
                    .exceptionOrNull() as BusinessException

            // 정상 Rate Limit 스텁 복구 후 백프레셔 케이스
            every { orderRateLimiter.hit(any(), any(), any()) } returns
                RateLimitResult(allowed = true, retryAfter = null)
            every { backpressureGuard.check(any(), any()) } throws BusinessException(ErrorCode.QUEUE_CAPACITY_EXCEEDED)
            val backpressureException =
                runCatching { sut.ingest(userId, request, ip) }
                    .exceptionOrNull() as BusinessException

            assertThat(rateLimitException.errorCode).isNotEqualTo(backpressureException.errorCode)
            assertThat(rateLimitException.errorCode).isEqualTo(ErrorCode.RATE_LIMIT_EXCEEDED)
            assertThat(backpressureException.errorCode).isEqualTo(ErrorCode.QUEUE_CAPACITY_EXCEEDED)
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 4단계: 멱등성 검사
    // ════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("4단계 — 멱등성 검사")
    inner class IdempotencyCheck {
        @Test
        @DisplayName("동일 이벤트에 이미 진행 중인 주문이 있으면 DUPLICATE_ORDER 를 던진다")
        fun `throws DUPLICATE_ORDER when order is already in progress for same event`() {
            every { idempotencyGateway.tryAcquire(userId, eventPublicId, any()) } returns false

            assertThatThrownBy { sut.ingest(userId, request, ip) }
                .isInstanceOf(BusinessException::class.java)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DUPLICATE_ORDER)
        }

        @Test
        @DisplayName("멱등 충돌 시 소유권 등록과 XADD 를 수행하지 않는다")
        fun `does not register ownership or XADD when idempotency fails`() {
            every { idempotencyGateway.tryAcquire(any(), any(), any()) } returns false

            runCatching { sut.ingest(userId, request, ip) }

            verify(exactly = 0) { ownershipRedisGateway.set(any(), any()) }
            verify(exactly = 0) { orderStreamGateway.add(any(), any()) }
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 실패 보상: XADD 실패 시 롤백
    // ════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("실패 보상 — XADD 실패 시 롤백")
    inner class CompensationOnXaddFailure {
        @BeforeEach
        fun stubXaddFailure() {
            every { orderStreamGateway.add(any(), any()) } throws RuntimeException("Redis connection error")
        }

        @Test
        @DisplayName("XADD 실패 시 INTERNAL_SERVER_ERROR 를 던진다")
        fun `throws INTERNAL_SERVER_ERROR when XADD fails`() {
            assertThatThrownBy { sut.ingest(userId, request, ip) }
                .isInstanceOf(BusinessException::class.java)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INTERNAL_SERVER_ERROR)
        }

        @Test
        @DisplayName("XADD 실패 시 멱등 키를 compare-and-delete 로 롤백한다")
        fun `calls compareAndDelete to roll back idempotency key when XADD fails`() {
            runCatching { sut.ingest(userId, request, ip) }

            verify(exactly = 1) { idempotencyGateway.compareAndDelete(userId, eventPublicId, any()) }
        }

        @Test
        @DisplayName("XADD 실패 시 소유권 키를 삭제한다")
        fun `deletes ownership key when XADD fails`() {
            runCatching { sut.ingest(userId, request, ip) }

            verify(exactly = 1) { ownershipRedisGateway.delete(any()) }
        }

        @Test
        @DisplayName("XADD 실패 시 멱등 키와 소유권 키가 모두 롤백된다 (잔존 0)")
        fun `both idempotency and ownership keys are rolled back when XADD fails`() {
            runCatching { sut.ingest(userId, request, ip) }

            verify(exactly = 1) { idempotencyGateway.compareAndDelete(any(), any(), any()) }
            verify(exactly = 1) { ownershipRedisGateway.delete(any()) }
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 단계 순서 보장
    // ════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("단계 순서 보장")
    inner class StepOrdering {
        @Test
        @DisplayName("백프레셔 검사(3단계)가 멱등 키 선점(4단계)보다 먼저 수행된다")
        fun `backpressure check occurs before idempotency acquire`() {
            sut.ingest(userId, request, ip)

            verifyOrder {
                backpressureGuard.check(any(), any())
                idempotencyGateway.tryAcquire(any(), any(), any())
            }
        }

        @Test
        @DisplayName("Rate Limit → 이벤트 조회 → 백프레셔 → 멱등 순서로 수행된다")
        fun `steps execute in correct order rate-limit event backpressure idempotency`() {
            sut.ingest(userId, request, ip)

            verifyOrder {
                orderRateLimiter.hit(any(), any(), any())
                eventCacheGateway.get(any())
                backpressureGuard.check(any(), any())
                idempotencyGateway.tryAcquire(any(), any(), any())
            }
        }

        @Test
        @DisplayName("소유권 등록(5단계)이 XADD(6단계)보다 먼저 수행된다")
        fun `ownership registration occurs before XADD`() {
            sut.ingest(userId, request, ip)

            verifyOrder {
                ownershipRedisGateway.set(any(), any())
                orderStreamGateway.add(any(), any())
            }
        }
    }
}
