import http from 'k6/http'
import { check, fail, sleep } from 'k6'

// ── 기본 설정 ─────────────────────────────────────────────────
export const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, '')

export const JSON_HEADERS = { 'Content-Type': 'application/json' }

// ── 응답 판정 (이슈: order-load 임계값/응답 판정 기준 재설계) ──────
// k6는 기본적으로 상태코드 4xx/5xx를 모두 http_req_failed=true 로 집계한다.
// 이 도메인에서는 429(백프레셔)·409(멱등 충돌)가 "정상적으로 예상되는" 응답이므로,
// responseCallback으로 엔드포인트별 기대 상태코드를 명시해 실제 장애(5xx, 예상 밖 4xx)만
// http_req_failed에 잡히도록 한다. checks_total의 개별 assertion(429/409 체크 등)은
// 이 설정과 별개로 그대로 유지된다.
const SIGNUP_EXPECTED = http.expectedStatuses(201, 409)
const LOGIN_EXPECTED = http.expectedStatuses(200)
const ORDER_EXPECTED = http.expectedStatuses(202, 409, 429)
const STATUS_EXPECTED = http.expectedStatuses(200)
const EVENT_DETAIL_EXPECTED = http.expectedStatuses(200)
const SSE_EXPECTED = http.expectedStatuses(200)

// ── 인증 ──────────────────────────────────────────────────────

/**
 * 회원가입: setup()에서 부하테스트 시작 전 seed 유저 존재를 보장하기 위해 사용.
 * 이미 가입된 계정(409)은 정상으로 간주하고 통과시킨다.
 *
 * @param {string} email
 * @param {string} password
 */
export function signUp(email, password) {
    const res = http.post(
        `${BASE_URL}/api/v1/auth/signup`,
        JSON.stringify({ email, password }),
        { headers: JSON_HEADERS, responseCallback: SIGNUP_EXPECTED },
    )

    if (res.status !== 201 && res.status !== 409) {
        fail(`signup failed [${email}] status=${res.status} body=${res.body}`)
    }
}

/**
 * 로그인: JWT를 Set-Cookie로 수신해 VU 쿠키 저장소에 자동 저장.
 * k6는 VU별로 독립적인 쿠키 저장소를 유지하지만, 기본값(noCookiesReset: false)에서는
 * 매 iteration 종료 시 쿠키가 초기화된다. VU당 1회만 로그인하는 시나리오라면
 * 반드시 options.noCookiesReset = true 를 함께 설정해야 발급된 쿠키가
 * 이후 iteration에서도 유지된다.
 *
 * @param {string} email
 * @param {string} password
 */
export function login(email, password) {
    const res = http.post(
        `${BASE_URL}/api/v1/auth/login`,
        JSON.stringify({ email, password }),
        { headers: JSON_HEADERS, responseCallback: LOGIN_EXPECTED },
    )

    if (!check(res, { 'login 200': (r) => r.status === 200 })) {
        fail(`login failed [${email}] status=${res.status} body=${res.body}`)
    }
}

// ── 주문 ──────────────────────────────────────────────────────

/**
 * 주문 요청 (POST /api/v1/orders)
 *
 * 202(수락)·429(백프레셔)·409(멱등 충돌) 모두 이 도메인에서는 정상 응답이므로
 * responseCallback으로 http_req_failed 집계에서 제외한다. 401/5xx 등 예상 밖 상태는
 * 여전히 http_req_failed=true 로 잡혀 실제 장애를 가린다.
 *
 * @param {string} eventId  - UUID 문자열 (EVENT_ID 환경변수)
 * @param {number} zoneDbId - 내부 Long ID. REDIS_STOCK_KEY("ZONE:<id>:stock")에서 파싱.
 * @returns {import('k6/http').RefinedResponse}
 */
export function placeOrder(eventId, zoneDbId) {
    return http.post(
        `${BASE_URL}/api/v1/orders`,
        JSON.stringify({ eventId, zoneId: zoneDbId }),
        { headers: JSON_HEADERS, responseCallback: ORDER_EXPECTED },
    )
}

/**
 * 주문 상태 조회 (GET /api/v1/orders/:orderId)
 * PENDING이면 서버가 Retry-After: 2 를 반환하므로 폴링 간격은 2초 권장.
 *
 * @param {string} orderId
 * @returns {import('k6/http').RefinedResponse}
 */
export function getOrderStatus(orderId) {
    return http.get(`${BASE_URL}/api/v1/orders/${orderId}`, {
        headers: JSON_HEADERS,
        responseCallback: STATUS_EXPECTED,
    })
}

// ── 이벤트 ────────────────────────────────────────────────────

/**
 * 이벤트 상세 조회 (GET /api/v1/events/:eventId)
 * teardown 오버셀 검증에 사용. 인증 불필요.
 *
 * @param {string} eventId
 * @returns {import('k6/http').RefinedResponse}
 */
export function getEventDetail(eventId) {
    return http.get(`${BASE_URL}/api/v1/events/${eventId}`, {
        responseCallback: EVENT_DETAIL_EXPECTED,
    })
}

// ── SSE ───────────────────────────────────────────────────────

/**
 * SSE 엔드포인트 연결 시도 (GET /api/v1/orders/sse/:orderId)
 * k6는 SSE 스트림을 지속 수신하지 못하므로 timeout 내 초기 응답만 검증.
 *
 * @param {string} orderId
 * @param {string|null} lastEventId - 재연결 시 Last-Event-ID 헤더 값
 * @param {string} timeout
 * @returns {import('k6/http').RefinedResponse}
 */
export function connectSse(orderId, lastEventId = null, timeout = '3s') {
    const headers = {
        Accept: 'text/event-stream',
        'Cache-Control': 'no-cache',
    }
    if (lastEventId) {
        headers['Last-Event-ID'] = lastEventId
    }
    return http.get(`${BASE_URL}/api/v1/orders/sse/${orderId}`, {
        headers,
        timeout,
        responseCallback: SSE_EXPECTED,
    })
}

// ── 유틸 ──────────────────────────────────────────────────────

/**
 * 터미널 상태 여부 판별
 * READY_TO_PAY / CONFIRMED / FAILED / EXPIRED → true
 * PENDING → false
 *
 * @param {string} status
 * @returns {boolean}
 */
export function isTerminal(status) {
    return ['READY_TO_PAY', 'CONFIRMED', 'FAILED', 'EXPIRED'].includes(status)
}

/**
 * 주문 상태 폴링: 터미널 상태에 도달하거나 maxAttempts 초과 시 반환.
 * 서버의 Retry-After: 2 에 맞춰 기본 간격 2초.
 *
 * @param {string}  orderId
 * @param {number}  maxAttempts - 최대 시도 횟수 (기본 15회 × 2s = 30s)
 * @param {number}  intervalSec - 폴링 간격(초)
 * @returns {string|null} 터미널 status 문자열, 또는 null(타임아웃)
 */
export function pollUntilTerminal(orderId, maxAttempts = 15, intervalSec = 2) {
    for (let i = 0; i < maxAttempts; i++) {
        sleep(intervalSec)
        const res = getOrderStatus(orderId)
        if (!check(res, { 'poll status 200': (r) => r.status === 200 })) return null
        const { status } = res.json()
        if (isTerminal(status)) return status
    }
    return null
}
