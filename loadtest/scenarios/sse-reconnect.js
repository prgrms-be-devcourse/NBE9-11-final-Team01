/**
 * sse-reconnect.js — SSE 연결·재연결 내구성 테스트
 *
 * k6는 SSE 스트림을 지속 수신하지 않으므로, 실제 이벤트 메시지 대신
 * 아래 두 가지를 검증한다:
 *  A. SSE 엔드포인트 초기 연결 → HTTP 200 + Content-Type: text/event-stream
 *  B. 의도적 끊김 후 재연결 (Last-Event-ID 헤더 포함) → 동일 조건 확인
 *
 * 이후 상태 폴링으로 주문이 READY_TO_PAY에 도달하는지 검증.
 *
 * 환경변수 (loadtest/seed/.env):
 *  BASE_URL, EVENT_ID, REDIS_STOCK_KEY
 *
 * 실행:
 *  k6 run --env-file loadtest/seed/.env loadtest/scenarios/sse-reconnect.js
 */

import { check, sleep } from 'k6'
import { Counter } from 'k6/metrics'
import {
  login,
  placeOrder,
  connectSse,
  pollUntilTerminal,
  isTerminal,
} from '../utils/helpers.js'
import { thresholds } from '../thresholds.js'

// ── 환경변수 파싱 ─────────────────────────────────────────────
const EVENT_ID = __ENV.EVENT_ID
const ZONE_DB_ID = parseInt(__ENV.REDIS_STOCK_KEY.split(':')[1], 10)

if (!EVENT_ID) throw new Error('EVENT_ID 환경변수가 설정되지 않았습니다.')
if (isNaN(ZONE_DB_ID)) throw new Error('REDIS_STOCK_KEY에서 zoneId를 파싱할 수 없습니다.')

// ── 커스텀 메트릭 ─────────────────────────────────────────────
/** SSE 초기 연결 실패 건수 */
const sseConnectFail = new Counter('sse_connect_fail')
/** SSE 재연결 실패 건수 */
const sseReconnectFail = new Counter('sse_reconnect_fail')

// ── 시나리오 옵션 ─────────────────────────────────────────────
export const options = {
  scenarios: {
    sse_reconnect: {
      executor: 'constant-vus',
      vus: 10,
      duration: '60s',
    },
  },
  thresholds: {
    ...thresholds,
    // SSE 연결·재연결 실패는 총 5건 미만 허용
    sse_connect_fail: ['count<5'],
    sse_reconnect_fail: ['count<5'],
  },
}

// ── 사용자 풀 ─────────────────────────────────────────────────
const users = JSON.parse(open('../seed/users.json'))

let authenticated = false

// ── 메인 시나리오 ─────────────────────────────────────────────
export default function () {
  // 1. 로그인 (VU당 최초 1회)
  if (!authenticated) {
    const user = users[(__VU - 1) % users.length]
    login(user.email, user.password)
    authenticated = true
  }

  // 2. 주문 생성
  const orderRes = placeOrder(EVENT_ID, ZONE_DB_ID)

  // 재고 소진 또는 백프레셔 → 이 시나리오에서는 스킵
  if (orderRes.status === 429 || orderRes.status === 409) {
    sleep(2)
    return
  }

  if (!check(orderRes, { 'order 202': (r) => r.status === 202 })) {
    console.error(`주문 실패: status=${orderRes.status}`)
    sleep(2)
    return
  }

  const { orderId } = orderRes.json()

  // 3-A. 최초 SSE 연결
  const sseRes = connectSse(orderId)
  const sseOk = check(sseRes, {
    'sse initial connect 200': (r) => r.status === 200,
    'sse content-type event-stream': (r) =>
      (r.headers['Content-Type'] || '').includes('text/event-stream'),
  })
  if (!sseOk) {
    sseConnectFail.add(1)
    console.warn(`SSE 연결 실패: orderId=${orderId} status=${sseRes.status}`)
  }

  // 의도적 끊김 시뮬레이션 (클라이언트 네트워크 순단 모사)
  sleep(1)

  // 3-B. 재연결 (Last-Event-ID 헤더로 이어받기 시뮬레이션)
  // 실제 이벤트 ID를 파싱할 수 없으므로 orderId를 마지막 수신 ID로 대체 전달.
  const sseReconnectRes = connectSse(orderId, orderId)
  const reconnectOk = check(sseReconnectRes, {
    'sse reconnect 200': (r) => r.status === 200,
    'sse reconnect event-stream': (r) =>
      (r.headers['Content-Type'] || '').includes('text/event-stream'),
  })
  if (!reconnectOk) {
    sseReconnectFail.add(1)
    console.warn(`SSE 재연결 실패: orderId=${orderId} status=${sseReconnectRes.status}`)
  }

  // 4. 상태 폴링으로 READY_TO_PAY 도달 확인 (최대 30s)
  const finalStatus = pollUntilTerminal(orderId)

  check(null, {
    'order reached READY_TO_PAY': () => finalStatus === 'READY_TO_PAY',
  })

  if (finalStatus && !isTerminal(finalStatus)) {
    console.warn(`예상치 못한 최종 상태: orderId=${orderId} status=${finalStatus}`)
  }

  // 이터레이션 간 간격 (재고 소진 후 과잉 요청 방지)
  sleep(2)
}
