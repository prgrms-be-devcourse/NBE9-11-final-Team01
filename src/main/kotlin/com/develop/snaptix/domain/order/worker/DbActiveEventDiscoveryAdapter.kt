package com.develop.snaptix.domain.order.worker

import com.develop.snaptix.domain.event.repository.EventRepository
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * [ActiveEventDiscoveryPort]의 프로덕션 구현체.
 *
 * DB의 events 테이블에서 status = ON_SALE 인 이벤트의 publicId 목록을 조회한다.
 *
 * 관련 이슈: #6 (ActiveEventDiscoveryPort DB 구현체)
 */
@Component
class DbActiveEventDiscoveryAdapter(
    private val eventRepository: EventRepository,
) : ActiveEventDiscoveryPort {
    override fun getActiveEvents(): List<UUID> = eventRepository.findActiveEventPublicIds()
}
