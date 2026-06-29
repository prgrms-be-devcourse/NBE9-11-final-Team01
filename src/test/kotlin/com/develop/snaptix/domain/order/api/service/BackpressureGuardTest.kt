package com.develop.snaptix.domain.order.api.service

import com.develop.snaptix.domain.order.observability.OrderMetrics
import com.develop.snaptix.global.exception.BusinessException
import com.develop.snaptix.global.exception.ErrorCode
import com.develop.snaptix.global.redis.gateway.OrderStreamGateway
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.BDDMockito.given
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import java.util.UUID

class BackpressureGuardTest {
    @Mock
    private lateinit var orderStreamGateway: OrderStreamGateway

    @Mock
    private lateinit var meterRegistry: MeterRegistry

    @Mock
    private lateinit var counter: Counter

    @InjectMocks
    private lateinit var backpressureGuard: BackpressureGuard

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        // 메트릭 카운터 Mocking 세팅
        given(meterRegistry.counter(OrderMetrics.BACKPRESSURE_COUNT)).willReturn(counter)
    }

    @Test
    @DisplayName("현재 큐 길이가 허용 임계치 미만이면 예외 없이 통과한다")
    fun `passes when queue length is below threshold`() {
        // given
        val eventPublicId = UUID.randomUUID()
        val totalCapacity = 1000
        val threshold = (totalCapacity * 1.2).toLong() // 1200L

        given(orderStreamGateway.length(eventPublicId)).willReturn(threshold - 1)

        // when & then (예외가 발생하지 않으면 성공)
        backpressureGuard.check(eventPublicId, totalCapacity)
    }

    @Test
    @DisplayName("현재 큐 길이가 허용 임계치 이상이면 429 예외가 발생하고 관측 메트릭이 증가한다")
    fun `throws 429 exception and increments metric when queue length exceeds threshold`() {
        // given
        val eventPublicId = UUID.randomUUID()
        val totalCapacity = 1000
        val threshold = (totalCapacity * 1.2).toLong() // 1200L

        given(orderStreamGateway.length(eventPublicId)).willReturn(threshold)

        // when
        val exception =
            assertThrows<BusinessException> {
                backpressureGuard.check(eventPublicId, totalCapacity)
            }

        // then
        assertThat(exception.errorCode).isEqualTo(ErrorCode.QUEUE_CAPACITY_EXCEEDED)
        verify(counter).increment() // 메트릭 증가 호출 여부 검증
    }
}
