package com.develop.snaptix.loadtest

import com.develop.snaptix.domain.event.dto.EventBulkCreateRequest
import com.develop.snaptix.domain.event.dto.EventStatusUpdateRequest
import com.develop.snaptix.domain.event.dto.ZoneCreateRequest
import com.develop.snaptix.domain.event.entity.EventStatus
import com.develop.snaptix.domain.event.service.EventService
import com.develop.snaptix.domain.user.entity.UserRole
import com.develop.snaptix.domain.user.entity.UsersTable
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneOffset

private val logger = KotlinLogging.logger {}
private const val SEP = "════════════════════════════════════════════════"

/**
 * loadtest 프로파일 활성 시 앱 기동과 함께 아래를 자동 수행한다.
 *
 *  1. 어드민 계정 생성 (멱등)
 *  2. 테스트 유저 200명 생성 (멱등)
 *  3. 이벤트 + 구역 생성 → 상태 ON_SALE 전환 (매 기동마다 새로 생성)
 *  4. loadtest/seed/.env  에 EVENT_ID / ZONE_ID / REDIS_STOCK_KEY 기록
 *  5. loadtest/seed/users.json 에 유저 목록 기록
 *
 *  k6 실행:
 *    source loadtest/seed/.env && k6 run loadtest/main.js
 */
@Component
@Profile("loadtest")
class LoadTestDataInitializer(
    private val passwordEncoder: PasswordEncoder,
    private val eventService: EventService,
) : ApplicationRunner {
    companion object {
        private const val ADMIN_EMAIL = "admin@snaptix.kr"
        private const val ADMIN_PASSWORD = "Admin1234!"

        private const val USER_EMAIL_PREFIX = "load-user"
        private const val USER_EMAIL_DOMAIN = "test.com"
        private const val USER_PASSWORD = "Test1234!"
        private const val USER_COUNT = 200

        private const val EVENT_NAME = "Load Test Event"
        private const val ZONE_NAME = "A구역"
        private const val UNIT_PRICE = 10_000
        private const val TOTAL_CAPACITY = 100

        private const val SEED_ENV_PATH = "loadtest/seed/.env"
        private const val USERS_JSON_PATH = "loadtest/seed/users.json"
    }

    // ── 진입점 ──────────────────────────────────────────────────────────────────

    override fun run(args: ApplicationArguments) {
        logger.info { "[LOADTEST] 시드 초기화 시작" }

        seedAdmin()
        seedUsers()
        val result = seedEvent()

        writeSeedEnv(result)
        writeUsersJson()
        printSummary(result)
    }

    // ── STEP 1: 어드민 ──────────────────────────────────────────────────────────

    private fun seedAdmin() {
        val exists =
            transaction {
                UsersTable
                    .selectAll()
                    .where { UsersTable.email eq ADMIN_EMAIL }
                    .count() > 0
            }
        if (exists) {
            logger.info { "[LOADTEST] 어드민 이미 존재: $ADMIN_EMAIL" }
            return
        }
        val encodedAdminPw =
            requireNotNull(passwordEncoder.encode(ADMIN_PASSWORD)) {
                "PasswordEncoder returned null"
            }
        transaction {
            UsersTable.insert {
                it[email] = ADMIN_EMAIL
                it[password] = encodedAdminPw
                it[role] = UserRole.ADMIN.name
            }
        }
        logger.info { "[LOADTEST] 어드민 created: $ADMIN_EMAIL" }
    }

    // ── STEP 2: 테스트 유저 ─────────────────────────────────────────────────────

    private fun seedUsers() {
        val existingCount =
            transaction {
                UsersTable
                    .selectAll()
                    .where { UsersTable.email like "$USER_EMAIL_PREFIX-%@$USER_EMAIL_DOMAIN" }
                    .count()
            }

        if (existingCount >= USER_COUNT) {
            logger.info { "[LOADTEST] 테스트 유저 이미 존재 ($existingCount 명) — 스킵" }
            return
        }

        // BCrypt는 비용이 크므로 동일 패스워드는 1회만 해싱
        val encodedPw =
            requireNotNull(passwordEncoder.encode(USER_PASSWORD)) {
                "PasswordEncoder returned null"
            }
        val startIdx = existingCount + 1

        transaction {
            UsersTable.batchInsert(data = (startIdx..USER_COUNT.toLong()).toList(), ignore = true) { idx ->
                this[UsersTable.email] = "$USER_EMAIL_PREFIX-$idx@$USER_EMAIL_DOMAIN"
                this[UsersTable.password] = encodedPw
                this[UsersTable.role] = UserRole.USER.name
            }
        }
        logger.info { "[LOADTEST] 테스트 유저 생성: $USER_COUNT 명 (총 $USER_COUNT 명)" }
    }

    // ── STEP 3: 이벤트 생성 + ON_SALE ───────────────────────────────────────────

    private data class SeedResult(
        val eventId: String,
        val zoneId: String,
        val redisStockKey: String,
    )

    private fun seedEvent(): SeedResult {
        val now = OffsetDateTime.now(ZoneOffset.UTC)

        // 매 기동마다 새 이벤트 생성 — 이전 이벤트의 Redis 재고는 이미 소모됐을 수 있으므로
        val createResponse =
            eventService.createEventWithZones(
                EventBulkCreateRequest(
                    name = EVENT_NAME,
                    description = "k6 부하 테스트용 이벤트",
                    location = "SnapTix 테스트 홀",
                    startTime = now.plusYears(1),
                    endTime = now.plusYears(1).plusHours(3),
                    initialStatus = EventStatus.PENDING,
                    zones =
                        listOf(
                            ZoneCreateRequest(
                                name = ZONE_NAME,
                                unitPrice = UNIT_PRICE,
                                totalCapacity = TOTAL_CAPACITY,
                            ),
                        ),
                ),
            )

        eventService.updateEventStatus(
            createResponse.eventId,
            EventStatusUpdateRequest(EventStatus.ON_SALE),
        )

        val zone = createResponse.registeredZones[0]
        logger.info { "[LOADTEST] 이벤트 생성 완료 → ON_SALE" }
        logger.info { "[LOADTEST] EVENT_ID        = ${createResponse.eventId}" }
        logger.info { "[LOADTEST] ZONE_ID         = ${zone.zoneId}" }
        logger.info { "[LOADTEST] REDIS_STOCK_KEY = ${zone.redisStockKey}" }

        return SeedResult(createResponse.eventId, zone.zoneId, zone.redisStockKey)
    }

    // ── STEP 4: loadtest/seed/.env 저장 ─────────────────────────────────────────

    private fun writeSeedEnv(result: SeedResult) {
        val envFile = File(SEED_ENV_PATH).also { it.parentFile?.mkdirs() }

        // 기존 .env가 있으면 EVENT_*/ZONE_*/REDIS_* 줄만 교체
        val preserved =
            if (envFile.exists()) {
                envFile.readLines().filterNot { line ->
                    line.startsWith("EVENT_ID=") ||
                        line.startsWith("ZONE_ID=") ||
                        line.startsWith("REDIS_STOCK_KEY=")
                }
            } else {
                emptyList()
            }

        val lines =
            preserved +
                listOf(
                    "EVENT_ID=${result.eventId}",
                    "ZONE_ID=${result.zoneId}",
                    "REDIS_STOCK_KEY=${result.redisStockKey}",
                )
        envFile.writeText(lines.joinToString("\n", postfix = "\n"))
        logger.info { "[LOADTEST] $SEED_ENV_PATH 저장 완료 (절대경로: ${envFile.absolutePath})" }
    }

    // ── STEP 5: loadtest/seed/users.json 저장 ──────────────────────────────────

    private fun writeUsersJson() {
        val entries =
            (1..USER_COUNT).joinToString(",\n  ") { i ->
                """{"email":"$USER_EMAIL_PREFIX-$i@$USER_EMAIL_DOMAIN","password":"$USER_PASSWORD"}"""
            }
        File(USERS_JSON_PATH)
            .also { it.parentFile?.mkdirs() }
            .writeText("[\n  $entries\n]\n")
        logger.info { "[LOADTEST] $USERS_JSON_PATH 저장 완료 ($USER_COUNT 명)" }
    }

    // ── 완료 요약 ────────────────────────────────────────────────────────────────

    private fun printSummary(result: SeedResult) {
        logger.info { "[LOADTEST] $SEP" }
        logger.info { "[LOADTEST]  시드 완료 — seed.sh 없이 자동 생성됩니다" }
        logger.info { "[LOADTEST]" }
        logger.info { "[LOADTEST]  어드민: $ADMIN_EMAIL / $ADMIN_PASSWORD" }
        logger.info { "[LOADTEST]  유저  : $USER_EMAIL_PREFIX-1~$USER_COUNT@$USER_EMAIL_DOMAIN / $USER_PASSWORD" }
        logger.info { "[LOADTEST]" }
        logger.info { "[LOADTEST]  EVENT_ID        = ${result.eventId}" }
        logger.info { "[LOADTEST]  ZONE_ID         = ${result.zoneId}" }
        logger.info { "[LOADTEST]  REDIS_STOCK_KEY = ${result.redisStockKey}" }
        logger.info { "[LOADTEST]" }
        logger.info { "[LOADTEST]  k6 실행:" }
        logger.info { "[LOADTEST]    source loadtest/seed/.env" }
        logger.info { "[LOADTEST]    k6 run loadtest/main.js" }
        logger.info { "[LOADTEST] $SEP" }
    }
}
