package com.develop.snaptix.domain.order.worker.port

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * [CompensationPort] no-op 스텁 — local 프로파일 전용.
 *
 * #7(CompensationService) 구현 전 로컬 개발·테스트 환경에서 빈 충돌 없이 동작하도록 한다.
 * @Profile("local")로 가드해 prod/dev 프로파일에서 이 빈이 등록되지 않게 한다.
 *
 * ⚠️ 실제 보상 로직 없음: 로컬 환경에서 INSERT 실패 시 Redis 재고가 복구되지 않는다.
 *    수동 Redis 정리 또는 drift-reconciliation으로 보정해야 한다.
 */
@Component
@Profile("local")
class NoOpCompensationAdapter : CompensationPort {
    private val log = KotlinLogging.logger {}

    override fun compensateIfLeaked(
        orderId: UUID,
        zoneId: Long,
    ) {
        log.warn {
            "[NO_OP_COMPENSATION] 보상 스텁 호출(#7 구현 전) — orderId=$orderId, zoneId=$zoneId. " +
                "Redis 재고가 복구되지 않았습니다."
        }
    }
}
