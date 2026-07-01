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
 * 호출자가 응답을 받아 accessToken 쿠키 값을 꺼낼 수 있도록 응답 객체를 반환한다
 * (2단계 — authCookieHeader() 참고).
 *
 * @param {string} email
 * @param {string} password
 * @returns {import('k6/http').RefinedResponse}
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

    return res
}

/**
 * login() 응답에서 accessToken 쿠키 값을 꺼내 `Cookie:` 헤더 문자열로 만든다. (2단계)
 *
 * sse.open()은 xk6-sse 소스(state.CookieJar)상 VU 공유 쿠키 저장소를 자동으로 쓰는 것으로
 * 보이지만, 실측 결과 일부 요청에서 쿠키가 실리지 않아 401(익명 취급)이 간헐적으로
 * 관측됐다. 원인 규명과 별개로, SSE 요청만큼은 쿠키를 헤더로 명시해 확실하게 인증되도록
 * 우회한다.
 *
 * @param {import('k6/http').RefinedResponse} loginRes - login()의 반환값
 * @returns {string} 예: "accessToken=eyJhbGciOi..."
 */
export function authCookieHeader(loginRes) {
    const token = loginRes.cookies && loginRes.cookies.accessToken && loginRes.cookies.accessToken[0]
        ? loginRes.cookies.accessToken[0].value
        : null

    if (!token) {
        fail('login 응답에서 accessToken 쿠키를 찾을 수 없습니다.')
    }

    return `accessToken=${token}`
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
 * SSE 엔드포인트 URL 생성 (GET /api/v1/orders/sse/:orderId)
 *
 * [2단계 고도화] k6 core http는 응답 body를 끝까지 읽어야 반환되는 구조라 지속되는
 * SSE 스트림을 검증할 수 없었다(1단계는 짧은 timeout을 "정상 신호"로 재해석하는
 * 우회책을 썼다). 2단계부터는 k6/x/sse(sse.open())로 실제 이벤트 스트림을 수신한다.
 * xk6-sse는 k6 v1.2.0+ 자동 확장 해석 대상이라 별도 커스텀 바이너리 빌드가
 * 필요 없다 — `import sse from 'k6/x/sse'` 후 `k6 run`만 하면 된다.
 * sse.open()이 내부적으로 VU의 공유 쿠키 저장소(state.CookieJar)를 쓰기는 하지만,
 * 실측 결과 일부 호출에서 그 쿠키가 실리지 않는 현상이 확인되어(authCookieHeader() 주석
 * 참고) 호출부에서는 매번 `Cookie` 헤더를 명시적으로 넘긴다.
 *
 * @param {string} orderId
 * @returns {string}
 */
export function sseUrl(orderId) {
    return `${BASE_URL}/api/v1/orders/sse/${orderId}`
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
