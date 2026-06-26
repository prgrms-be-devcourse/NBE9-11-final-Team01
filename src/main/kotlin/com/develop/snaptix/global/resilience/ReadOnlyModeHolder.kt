package com.develop.snaptix.global.resilience

import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 재구축 중 Read-Only 상태 홀더. (작업 명세서 v2.1 §7 · Story 13.2)
 *
 * Rebuild 동안 ON. 인게스트 컨트롤러가 이 플래그로 신규 주문 503 거절(추후 #8/#9에서 결선).
 * 본 작업(#7)은 홀더 제공까지만 한다.
 *
 * NOTE(범위 결정): 본 플래그는 **인스턴스 로컬 [AtomicBoolean]** 이다. 팀 합의상 MVP 는
 * **단일 인스턴스 전제**로 구현하므로 이 방식으로 충분하다(한 프로세스 = 앱 전체).
 * 무중단(롤링) 배포 등으로 멀티 인스턴스가 상시화되면, 락 미보유 인스턴스가 재구축 중 차감을
 * 계속해 오버셀이 날 수 있으므로 그때 Redis 키 기반 **전역 read-only** 플래그로 승격한다(#8 범위).
 */
@Component
class ReadOnlyModeHolder {
    private val readOnly = AtomicBoolean(false)

    fun enable() {
        readOnly.set(true)
    }

    fun disable() {
        readOnly.set(false)
    }

    fun isReadOnly(): Boolean = readOnly.get()
}
