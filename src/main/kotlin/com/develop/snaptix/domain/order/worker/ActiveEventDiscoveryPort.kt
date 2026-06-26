package com.develop.snaptix.domain.order.worker

import java.util.UUID

interface ActiveEventDiscoveryPort {
    /**
     * 현재 판매 중(ON_SALE)인 이벤트들의 ID 목록을 반환합니다.
     */
    fun getActiveEvents(): List<UUID>
}
