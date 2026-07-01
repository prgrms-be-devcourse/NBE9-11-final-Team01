/**
 * order-load.js — 주문 인제스트 부하 테스트
 *
 * 시나리오: constant-arrival-rate (초당 고정 요청 수)
 *  0. setup: seed 유저 전원 회원가입 시도(이미 존재하면 409로 통과) → 로그인 401 방지
 *  1. VU 최초 실행 시 로그인 (JWT 쿠키 획득)
 *  2. POST /api/v1/orders → 202 Accepted
 *  3. GET /api/v1/orders/:orderId 폴링 → 터미널 상태 대기
 *  4. teardown: GET /api/v1/events/:eventId → currentStock 음수 시 oversell_errors 카운트
 *
 * 환경변수 (loadtest/seed/.env에서 자동 주입):
 *  BASE_URL         - 서버 주소 (기본: http://localhost:8080)
 *  EVENT_ID         - 이벤트 UUID
 *  REDIS_STOCK_KEY  - "ZONE:<internal_long_id>:stock" 형식. zoneId(Long) 파싱에 사용.
 *
 * 실행:
 *  k6 run --env-file loadtest/seed/.env loadtest/scenarios/order-load.js
 */

import { check, sleep } from 'k6'
import { Counter, Rate } from 'k6/metrics'
import {
    signUp,
    login,
    placeOrder,
    getOrderStatus,
    getEventDetail,
    isTerminal,
} from '../utils/helpers.js'
import { thresholds } from '../thresholds.js'

// ── 환경변수 파싱 (init scope) ────────────────────────────────
const EVENT_ID = __ENV.EVENT_ID
// REDIS_STOCK_KEY 형식: "ZONE:501:stock" → 내부 zoneId = 501
const ZONE_DB_ID = parseInt(__ENV.REDIS_STOCK_KEY.split(':')[1], 10)

if (!EVENT_ID) throw new Error('EVENT_ID 환경변수가 설정되지 않았습니다.')
if (isNaN(ZONE_DB_ID)) throw new Error('REDIS_STOCK_KEY에서 zoneId를 파싱할 수 없습니다.')

// ── 커스텀 메트릭 ─────────────────────────────────────────────
/** 재고 음수(오버셀) 건수. teardown에서 카운트. */
const oversellErrors = new Counter('oversell_errors')
/** 429 백프레셔 비율. 분모는 전체 주문 시도 횟수. */
const backpressureHit = new Rate('backpressure_hit')

// ── 시나리오 옵션 ─────────────────────────────────────────────
export const options = {
    scenarios: {
        order_ingest: {
            executor: 'constant-arrival-rate',
            /** 초당 주문 요청 수. 재고(TOTAL_CAPACITY=100)를 고려해 설정. */
            rate: 20,
            timeUnit: '1s',
            duration: '30s',
            preAllocatedVUs: 50,
            maxVUs: 200,
        },
    },
    // k6는 기본적으로 매 iteration 종료 시 쿠키 저장소를 초기화한다.
    // VU당 1회만 로그인하고 이후 iteration에서 그 쿠키(JWT)를 재사용하는
    // 이 스크립트의 구조상, 이 옵션이 없으면 두 번째 iteration부터
    // accessToken 쿠키가 사라져 인증 없이 요청이 나가고 401을 받게 된다.
    noCookiesReset: true,
    thresholds,
}

// ── 사용자 풀 (init scope) ────────────────────────────────────
const users = JSON.parse(open('../seed/users.json'))

// ── VU별 1회 로그인 플래그 ───────────────────────────────────
// k6 VU는 독립 JS 런타임을 가지므로 모듈 레벨 변수는 VU별로 격리됨.
let authenticated = false

// ── setup: seed 유저 사전 프로비저닝 ──────────────────────────
// users.json에 정의된 계정이 실제 DB에 없거나(별도 시딩 누락) 비밀번호가
// 어긋나 있으면 login()이 항상 401을 반환한다. 여기서 회원가입을 먼저
// 시도해 계정 존재를 보장한다(이미 가입돼 있으면 signUp이 409를 정상 처리).
export function setup() {
    users.forEach((user) => signUp(user.email, user.password))
}

// ── 메인 시나리오 ─────────────────────────────────────────────
export default function () {
    // 1. 로그인 (VU당 최초 1회)
    if (!authenticated) {
        const user = users[(__VU - 1) % users.length]
        login(user.email, user.password)
        authenticated = true
    }

    // 2. 주문 요청
    const orderRes = placeOrder(EVENT_ID, ZONE_DB_ID)

    // 401: 인증 쿠키 유실 (noCookiesReset 미설정 시 재발할 수 있음)
    if (orderRes.status === 401) {
        console.error(`인증 실패(401): accessToken 쿠키가 요청에 포함되지 않았습니다.`)
        authenticated = false // 다음 iteration에서 재로그인 시도
        return
    }

    // 429: 백프레셔 (정상 동작 — Redis 스트림 포화 또는 재고 소진)
    if (orderRes.status === 429) {
        backpressureHit.add(1)
        check(orderRes, { '429 backpressure (expected)': () => true })
        sleep(1)
        return
    }

    // 409: 멱등 충돌 (동일 사용자 중복 주문)
    if (orderRes.status === 409) {
        backpressureHit.add(0)
        check(orderRes, { '409 idempotent conflict (expected)': () => true })
        return
    }

    backpressureHit.add(0)

    if (!check(orderRes, { 'order 202 accepted': (r) => r.status === 202 })) {
        console.error(`주문 실패: status=${orderRes.status} body=${orderRes.body}`)
        return
    }

    const { orderId } = orderRes.json()

    // 3. 상태 폴링 (Retry-After: 2 헤더에 맞춰 2초 간격, 최대 30초)
    let finalStatus = null
    for (let i = 0; i < 15; i++) {
        sleep(2)
        const statusRes = getOrderStatus(orderId)
        if (!check(statusRes, { 'status poll 200': (r) => r.status === 200 })) break
        const { status } = statusRes.json()
        if (isTerminal(status)) {
            finalStatus = status
            break
        }
    }

    check(null, {
        'terminal status reached': () => finalStatus !== null,
        'order not failed/expired': () =>
            finalStatus === 'READY_TO_PAY' || finalStatus === 'CONFIRMED',
    })
}

// ── Teardown: 오버셀 검증 ─────────────────────────────────────
export function teardown() {
    const res = getEventDetail(EVENT_ID)
    if (!check(res, { 'teardown event detail 200': (r) => r.status === 200 })) return

    const { zones } = res.json()
    zones.forEach((zone) => {
        if (zone.currentStock < 0) {
            oversellErrors.add(1)
            console.error(
                `[OVERSELL] zoneId=${zone.zoneId} name=${zone.name} stock=${zone.currentStock}`,
            )
        } else {
            console.log(
                `[OK] zoneId=${zone.zoneId} name=${zone.name} stock=${zone.currentStock}/${zone.totalCapacity}`,
            )
        }
    })
}
