package com.develop.snaptix.domain.order.worker.adapter

import com.develop.snaptix.global.redis.key.RedisKeyFactory
import com.develop.snaptix.support.IntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.util.UUID

@SpringBootTest
@DisplayName("OrderCompensationAdapter (주문 실패 시 Redis 재고 보상 어댑터) 통합 테스트")
class OrderCompensationAdapterTest
    @Autowired
    constructor(
        private val sut: OrderCompensationAdapter,
        private val keys: RedisKeyFactory,
    ) : IntegrationTestSupport() {
        private val zoneId = 777L
        private lateinit var stockKey: String
        private lateinit var claimedKey: String

        @BeforeEach
        fun setUp() {
            stockKey = keys.stock(zoneId)
            claimedKey = keys.claimed(zoneId)

            // 각 테스트 격리를 위해 Redis 키 초기화
            redisTemplate.delete(listOf(stockKey, claimedKey))
        }

        @Nested
        @DisplayName("compensateIfLeaked() 호출 시")
        inner class CompensateIfLeakedTest {
            @Test
            @DisplayName("claimed 목록에 orderId가 존재하면, 재고를 1 증가시키고 claimed에서 제거한다 (정상 보상)")
            fun `restores stock and removes orderId when claimed exists`() {
                // given
                val orderId = UUID.randomUUID()
                val initialStock = 10

                redisTemplate.opsForValue().set(stockKey, initialStock.toString())
                redisTemplate.opsForSet().add(claimedKey, orderId.toString())

                // when
                sut.compensateIfLeaked(orderId, zoneId)

                // then
                val actualStock = redisTemplate.opsForValue().get(stockKey)?.toInt()
                val isMember = redisTemplate.opsForSet().isMember(claimedKey, orderId.toString())

                assertThat(actualStock).isEqualTo(initialStock + 1) // 재고 원상복구 (+1)
                assertThat(isMember).isFalse() // claimed 목록에서 제거 완료
            }

            @Test
            @DisplayName("claimed 목록에 orderId가 없다면, 재고를 변경하지 않고 복구를 무시한다 (이중 보상 방어)")
            fun `does nothing when orderId does not exist in claimed set`() {
                // given
                val orderId = UUID.randomUUID()
                val initialStock = 5

                redisTemplate.opsForValue().set(stockKey, initialStock.toString())
                // claimed 키에는 아무것도 넣지 않음 (멤버십 가드 작동 조건)

                // when
                sut.compensateIfLeaked(orderId, zoneId)

                // then
                val actualStock = redisTemplate.opsForValue().get(stockKey)?.toInt()
                val isMember = redisTemplate.opsForSet().isMember(claimedKey, orderId.toString())

                assertThat(actualStock).isEqualTo(initialStock) // 재고에 영향이 없어야 함 (오버셀/자원 중복 생성 차단)
                assertThat(isMember).isFalse()
            }

            @Test
            @DisplayName("동일한 orderId로 연이어 두 번 호출되어도, 첫 번째만 보상하고 두 번째는 무시된다 (통일 보상 멱등성)")
            fun `is idempotent when called multiple times with same orderId`() {
                // given
                val orderId = UUID.randomUUID()
                val initialStock = 20

                redisTemplate.opsForValue().set(stockKey, initialStock.toString())
                redisTemplate.opsForSet().add(claimedKey, orderId.toString())

                // when: 연속 2회 보상 요청
                sut.compensateIfLeaked(orderId, zoneId)
                sut.compensateIfLeaked(orderId, zoneId)

                // then: 2번 불렸어도 실제 재고 복구 및 자원 차감 취소는 단 1회만 일어나야 함
                val actualStock = redisTemplate.opsForValue().get(stockKey)?.toInt()
                val isMember = redisTemplate.opsForSet().isMember(claimedKey, orderId.toString())

                assertThat(actualStock).isEqualTo(initialStock + 1) // +2가 아닌 단 한번의 +1만 발생함
                assertThat(isMember).isFalse()
            }
        }
    }
