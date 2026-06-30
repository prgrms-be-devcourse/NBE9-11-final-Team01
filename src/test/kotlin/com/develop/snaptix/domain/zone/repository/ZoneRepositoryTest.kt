package com.develop.snaptix.domain.zone.repository

import com.develop.snaptix.domain.event.entity.EventStatus
import com.develop.snaptix.domain.event.entity.EventsTable
import com.develop.snaptix.support.IntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@SpringBootTest
@DisplayName("ZoneRepository (구역 영속성) 테스트")
class ZoneRepositoryTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var sut: ZoneRepository

    private var testEventId: Long = 0L

    @BeforeEach
    fun setUp() {
        testEventId = insertTestEvent()
    }

    /** FK(ZonesTable → EventsTable) 충족을 위해 테스트 이벤트를 직접 삽입하고 내부 id를 반환한다. */
    private fun insertTestEvent(): Long = transaction {
        EventsTable.insert {
            it[publicId] = UUID.randomUUID().toString()
            it[name] = "테스트 이벤트"
            it[location] = "서울"
            it[startTime] = Instant.now().plus(1, ChronoUnit.DAYS)
            it[endTime] = Instant.now().plus(2, ChronoUnit.DAYS)
            it[status] = EventStatus.ON_SALE.name
        }[EventsTable.id]
    }

    private fun zoneCommand(
        name: String = "A구역",
        unitPrice: Int = 10_000,
        totalCapacity: Int = 100,
        publicId: String = UUID.randomUUID().toString(),
    ): ZoneCreateCommand = ZoneCreateCommand(
        publicId = publicId,
        name = name,
        unitPrice = unitPrice,
        totalCapacity = totalCapacity,
    )

    @Nested
    @DisplayName("insertZones() - 구역 Bulk 삽입")
    inner class InsertZonesTest {
        @Test
        @DisplayName("단일 구역을 삽입하면 입력값과 동일한 결과(생성 id 포함)를 반환한다")
        fun `inserts a single zone and returns result with generated id`() {
            val command = zoneCommand(name = "A구역", unitPrice = 10_000, totalCapacity = 100)

            val results = transaction { sut.insertZones(testEventId, listOf(command)) }

            assertThat(results).hasSize(1)
            with(results.first()) {
                assertThat(id).isPositive()
                assertThat(publicId).isEqualTo(command.publicId)
                assertThat(name).isEqualTo("A구역")
                assertThat(unitPrice).isEqualTo(10_000)
                assertThat(totalCapacity).isEqualTo(100)
            }
        }

        @Test
        @DisplayName("여러 구역을 삽입하면 모든 구역이 입력 순서대로 반환된다")
        fun `inserts multiple zones preserving input order`() {
            val commands =
                listOf(
                    zoneCommand(name = "A구역", unitPrice = 30_000, totalCapacity = 50),
                    zoneCommand(name = "B구역", unitPrice = 20_000, totalCapacity = 100),
                    zoneCommand(name = "C구역", unitPrice = 10_000, totalCapacity = 200),
                )

            val results = transaction { sut.insertZones(testEventId, commands) }

            assertThat(results).hasSize(3)
            assertThat(results.map { it.name }).containsExactly("A구역", "B구역", "C구역")
            assertThat(results.map { it.publicId })
                .containsExactlyElementsOf(commands.map { it.publicId })
            assertThat(results).allSatisfy { assertThat(it.id).isPositive() }
        }

        @Test
        @DisplayName("삽입된 구역의 id는 findIdsByEventId() 로 다시 조회된다")
        fun `inserted zone ids are retrievable by event id`() {
            val commands = listOf(zoneCommand(name = "A구역"), zoneCommand(name = "B구역"))

            val inserted = transaction { sut.insertZones(testEventId, commands) }
            val foundIds = transaction { sut.findIdsByEventId(testEventId) }

            assertThat(foundIds).containsExactlyInAnyOrderElementsOf(inserted.map { it.id })
        }
    }

    @Nested
    @DisplayName("findIdsByEventId() - 이벤트별 구역 id 조회")
    inner class FindIdsByEventIdTest {
        @Test
        @DisplayName("해당 이벤트의 구역이 존재하면 모든 구역 id를 반환한다")
        fun `returns all zone ids for the given event`() {
            val inserted =
                transaction {
                    sut.insertZones(
                        testEventId,
                        listOf(zoneCommand(name = "A구역"), zoneCommand(name = "B구역")),
                    )
                }

            val ids = transaction { sut.findIdsByEventId(testEventId) }

            assertThat(ids).hasSize(2)
            assertThat(ids).containsExactlyInAnyOrderElementsOf(inserted.map { it.id })
        }

        @Test
        @DisplayName("구역이 없는 이벤트를 조회하면 빈 리스트를 반환한다")
        fun `returns empty list when event has no zones`() {
            val emptyEventId = insertTestEvent()

            val ids = transaction { sut.findIdsByEventId(emptyEventId) }

            assertThat(ids).isEmpty()
        }

        @Test
        @DisplayName("존재하지 않는 이벤트를 조회하면 빈 리스트를 반환한다")
        fun `returns empty list when event does not exist`() {
            val nonExistentEventId = 999_999L

            val ids = transaction { sut.findIdsByEventId(nonExistentEventId) }

            assertThat(ids).isEmpty()
        }

        @Test
        @DisplayName("여러 이벤트가 있어도 지정한 이벤트의 구역 id만 반환한다")
        fun `returns ids only for the specified event`() {
            val otherEventId = insertTestEvent()
            val target =
                transaction { sut.insertZones(testEventId, listOf(zoneCommand(name = "A구역"))) }
            transaction {
                sut.insertZones(otherEventId, listOf(zoneCommand(name = "B구역"), zoneCommand(name = "C구역")))
            }

            val ids = transaction { sut.findIdsByEventId(testEventId) }

            assertThat(ids).containsExactlyInAnyOrderElementsOf(target.map { it.id })
        }
    }
}
