import http from 'k6/http'
import crypto from 'k6/crypto'
import { check, fail, sleep } from 'k6'

// ── 기본 설정 ─────────────────────────────────────────────────
export const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, '')

export const JSON_HEADERS = { 'Content-Type': 'application/json' }

// application-secret.yaml(profiles.include: secret으로 application.yaml보다 우선 적용됨)의
// payment.mock.webhook.secret 기본값과 동일. application.yaml 자체에 적힌 인라인 기본값
// (snaptix-local-mock-webhook-secret)은 이 profile 포함 관계 때문에 실제로는 적용되지 않는다 —
// 로컬 환경은 대부분 application-secret.example.yaml을 그대로 복사해 쓰므로 이 값이 실제 기본값이다.
// MOCK_PAYMENT_WEBHOOK_SECRET 환경변수를 서버에 별도로 주입했다면(운영 등) 그 값을 k6 실행 시에도
// 동일하게 --env MOCK_PAYMENT_WEBHOOK_SECRET=... 로 넘겨야 한다.
const MOCK_PAYMENT_WEBHOOK_SECRET =
    __ENV.MOCK_PAYMENT_WEBHOOK_SECRET || 'replace-this-with-mock-payment-webhook-secret'

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
// 409: ORDER_NOT_PAYABLE/ORDER_HOLD_EXPIRED — 부하 상황에서 홀드가 만료된 뒤 승인 시도가
// 들어오면 정상적으로 발생할 수 있는 응답이라 expected 처리한다. 403/404는 테스트 로직
// 버그(소유자 불일치, 잘못된 orderId)를 의미하므로 실패로 남겨둔다.
const APPROVE_EXPECTED = http.expectedStatuses(200, 409)
// webhook은 정상/중복 스킵 모두 200으로 응답한다(MockPaymentWebhookService.handle 참고).
// 401(서명 불일치)이 뜨면 MOCK_PAYMENT_WEBHOOK_SECRET이 서버 설정과 어긋난 것이므로 실패로 남긴다.
const WEBHOOK_EXPECTED = http.expectedStatuses(200)

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

// ── 결제 (Mock) ───────────────────────────────────────────────
// 실제 CONFIRMED 전이는 approve가 아니라 webhook 호출에서 일어난다
// (MockPaymentApproveService.approve는 소유자/상태/홀드윈도우 검증과 중복 클릭 방지만 수행).
// 그래서 CONFIRMED 경로를 검증하려면 두 호출을 순서대로 모두 해야 한다.

/**
 * 모의 결제 승인 요청 (POST /api/v1/payments/mock/approve)
 * READY_TO_PAY 상태의 주문에 대해 "결제하기"를 눌렀다고 가정하는 호출. 인증 필요(JWT 쿠키).
 *
 * @param {string} orderId
 * @returns {import('k6/http').RefinedResponse}
 */
export function approvePayment(orderId) {
    return http.post(
        `${BASE_URL}/api/v1/payments/mock/approve`,
        JSON.stringify({ orderId }),
        { headers: JSON_HEADERS, responseCallback: APPROVE_EXPECTED },
    )
}

/**
 * 모의 PG 결제 결과 Webhook 전송 (POST /api/v1/payments/mock/webhook)
 * 실제 PENDING_PAYMENT → CONFIRMED/CANCELLED 전이가 일어나는 지점.
 * MockPaymentWebhookSignatureVerifier와 동일한 방식(HMAC-SHA256, hex, "sha256=" 접두사)으로
 * 서명해 X-Mock-Signature 헤더에 담아 보낸다. 인증(JWT) 불필요 — 서명이 곧 인증.
 *
 * @param {string} orderId
 * @param {'SUCCESS'|'FAIL'} paymentStatus
 * @param {string|null} failReason - paymentStatus가 FAIL일 때만 사용 (선택)
 * @returns {import('k6/http').RefinedResponse}
 */
export function sendPaymentWebhook(orderId, paymentStatus = 'SUCCESS', failReason = null) {
    const body = { orderId, paymentStatus }
    if (failReason) {
        body.failReason = failReason
    }
    // 서명은 실제로 전송할 raw body 바이트 기준이어야 하므로, 직렬화한 문자열을 그대로 재사용한다.
    const rawBody = JSON.stringify(body)
    const signature = `sha256=${crypto.hmac('sha256', MOCK_PAYMENT_WEBHOOK_SECRET, rawBody, 'hex')}`

    return http.post(`${BASE_URL}/api/v1/payments/mock/webhook`, rawBody, {
        headers: { ...JSON_HEADERS, 'X-Mock-Signature': signature },
        responseCallback: WEBHOOK_EXPECTED,
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
 *
 * [1단계 긴급 수리] Last-Event-ID 헤더 전송 로직 제거.
 * 서버 코드(OrderSseController / InMemorySseConnectionManager / OrderSseAdapter)를
 * 확인한 결과, 이 헤더를 읽는 로직이 어디에도 없다 — 컨트롤러는 orderId 경로변수와
 * 인증된 userId만으로 connect()를 호출하며, 재연결 판정은 SseChannelKey(resource, id)
 * 동일 여부(= 동일 orderId)로만 이뤄진다. 즉 "이어받기"는 헤더가 아니라 같은 orderId로
 * 다시 구독하는 것 자체이며, 서버는 재연결 시마다 StateReconstructor로 DB 기준 현재
 * 상태를 재구성해 재전송한다. 이 재구성 이벤트를 실제로 수신해 검증하는 것은 k6 core
 * http로는 불가능해(스트리밍 미지원) 2단계 고도화(k6/x/sse)에서 다룬다.
 *
 * k6는 SSE 스트림을 지속 수신하지 못하므로 timeout 내 초기 응답만 검증한다.
 *
 * @param {string} orderId
 * @param {string} timeout
 * @returns {import('k6/http').RefinedResponse}
 */
export function connectSse(orderId, timeout = '3s') {
    return http.get(`${BASE_URL}/api/v1/orders/sse/${orderId}`, {
        headers: {
            Accept: 'text/event-stream',
            'Cache-Control': 'no-cache',
        },
        timeout,
        responseCallback: SSE_EXPECTED,
    })
}

/** 클라이언트 타임아웃을 나타내는 k6 에러 코드 (환경에 따라 1050 또는 1211 관측됨). */
const SSE_TIMEOUT_ERROR_CODES = [1050, 1211]

/**
 * SSE 초기 연결/재연결 결과 판정. (1단계 긴급 수리)
 *
 * 서버(application.yaml: realtime.sse.timeout=8m, heartbeat-interval=30s)는 SSE 연결을
 * 최대 8분 유지하며, READY_TO_PAY는 연결을 닫지 않는다(SseEvent.terminal=false).
 * 따라서 connectSse()의 짧은 timeout(기본 3s) 안에서는 응답이 "완료"되는 것이 아니라
 * "타임아웃"되는 것이 정상 동작이다 — 예전 코드처럼 status===200만 성공으로 보면
 * 이 체크는 사실상 항상 실패로 기록된다.
 *
 * 판정 기준:
 *  - status 200 + Content-Type: text/event-stream → 정상 (즉시 완료되는 드문 케이스 포함)
 *  - status 0 + 클라이언트 타임아웃(context deadline exceeded 등) → 정상
 *    (서버가 연결을 계속 열어 두고 있다는 뜻이므로 실패가 아니다)
 *  - 그 외(401/403/404/429/5xx 등 즉각적인 에러 응답) → 실패
 *
 * @param {import('k6/http').RefinedResponse} res
 * @returns {boolean}
 */
export function isSseHandshakeOk(res) {
    if (res.status === 200) {
        return (res.headers['Content-Type'] || '').includes('text/event-stream')
    }
    if (res.status === 0) {
        const message = (res.error || '').toLowerCase()
        return (
            SSE_TIMEOUT_ERROR_CODES.includes(res.error_code) ||
            message.includes('timeout') ||
            message.includes('deadline exceeded')
        )
    }
    return false
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
