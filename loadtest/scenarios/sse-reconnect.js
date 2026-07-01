/**
 * sse-reconnect.js - SSE connection/reconnect durability test (Stage 1: emergency fix)
 *
 * [Fixed in Stage 1]
 *  1. Fixed the crash: sse-reconnect.js used to spread the whole shared thresholds
 *     object (...thresholds), which pulled in order-load.js-only metrics
 *     (oversell_errors, backpressure_hit) that are not declared here, causing k6 to
 *     fail at startup validation with "no metric name oversell_errors found".
 *     Now this scenario only defines its own sse_connect_fail / sse_reconnect_fail
 *     thresholds and no longer imports the shared thresholds module.
 *  2. Removed a check with no real effect: the reconnect call used to send a
 *     Last-Event-ID header. After reviewing the server code (OrderSseController /
 *     InMemorySseConnectionManager / OrderSseAdapter) that header is never read -
 *     "reconnect" simply means subscribing again with the same orderId; the server
 *     matches by SseChannelKey(resource, id), replaces the existing emitter, and
 *     re-sends the current state via StateReconstructor.
 *  3. Fixed a check that always failed in practice: the server keeps the SSE
 *     connection open for realtime.sse.timeout (8 minutes by default) and does not
 *     close it on READY_TO_PAY, so within a short client timeout (3s) the request is
 *     expected to time out rather than "complete" with 200. helpers.isSseHandshakeOk()
 *     now only treats immediate 4xx/5xx errors as failures, and treats a client-side
 *     timeout as the expected "connection still open" signal.
 *  4. (found during re-verification) Fixed mass 401s caused by cookie reset: k6
 *     resets the VU cookie jar after every iteration by default. This script only
 *     calls login() once per VU and relies on reusing that JWT cookie afterwards,
 *     so noCookiesReset:true is required (order-load.js already had it, this script
 *     was missing it). app.log confirmed AuthorizationDeniedException (anonymous
 *     user failing hasRole('USER')) on almost every order request after the first
 *     iteration per VU.
 *
 * k6 does not keep receiving an SSE stream, so instead of real event payloads this
 * script verifies:
 *  A. Initial SSE connect -> connection established without an immediate error
 *     (200, or a timeout while the connection is being held open)
 *  B. Reconnect after an intentional gap -> same condition
 *
 * Then polls order status until it reaches READY_TO_PAY.
 *
 * Env vars (loadtest/seed/.env): BASE_URL, EVENT_ID, REDIS_STOCK_KEY
 *
 * Run:
 *  k6 run --env-file loadtest/seed/.env loadtest/scenarios/sse-reconnect.js
 */

import { check, sleep } from 'k6'
import { Counter } from 'k6/metrics'
import {
    login,
    placeOrder,
    connectSse,
    isSseHandshakeOk,
    pollUntilTerminal,
    isTerminal,
} from '../utils/helpers.js'

const EVENT_ID = __ENV.EVENT_ID
const ZONE_DB_ID = parseInt(__ENV.REDIS_STOCK_KEY.split(':')[1], 10)

if (!EVENT_ID) throw new Error('EVENT_ID env var is not set.')
if (isNaN(ZONE_DB_ID)) throw new Error('Could not parse zoneId from REDIS_STOCK_KEY.')

const sseConnectFail = new Counter('sse_connect_fail')
const sseReconnectFail = new Counter('sse_reconnect_fail')

export const options = {
    scenarios: {
        sse_reconnect: {
            executor: 'constant-vus',
            vus: 10,
            duration: '60s',
        },
    },
    // order-load.js-only thresholds (oversell_errors, backpressure_hit) and the shared
    // http_req_failed/http_req_duration thresholds are intentionally not applied here.
    // SSE requests are designed to stay open for a long time, which violates those
    // thresholds' assumptions - see helpers.isSseHandshakeOk() for details.
    thresholds: {
        sse_connect_fail: ['count<5'],
        sse_reconnect_fail: ['count<5'],
    },
    // Required because this script logs in once per VU and reuses the cookie across
    // iterations; without this, k6's default cookie reset causes 401s from the second
    // iteration onward (confirmed via app.log: AuthorizationDeniedException).
    noCookiesReset: true,
}

const users = JSON.parse(open('../seed/users.json'))

let authenticated = false

export default function () {
    if (!authenticated) {
        const user = users[(__VU - 1) % users.length]
        login(user.email, user.password)
        authenticated = true
    }

    const orderRes = placeOrder(EVENT_ID, ZONE_DB_ID)

    if (orderRes.status === 429 || orderRes.status === 409) {
        sleep(2)
        return
    }

    if (!check(orderRes, { 'order 202': (r) => r.status === 202 })) {
        console.error('order failed: status=' + orderRes.status)
        sleep(2)
        return
    }

    const { orderId } = orderRes.json()

    const sseRes = connectSse(orderId)
    const sseOk = check(sseRes, {
        'sse initial connect established': (r) => isSseHandshakeOk(r),
    })
    if (!sseOk) {
        sseConnectFail.add(1)
        console.warn('sse connect failed: orderId=' + orderId + ' status=' + sseRes.status + ' error=' + sseRes.error)
    }

    sleep(1)

    const sseReconnectRes = connectSse(orderId)
    const reconnectOk = check(sseReconnectRes, {
        'sse reconnect established': (r) => isSseHandshakeOk(r),
    })
    if (!reconnectOk) {
        sseReconnectFail.add(1)
        console.warn('sse reconnect failed: orderId=' + orderId + ' status=' + sseReconnectRes.status + ' error=' + sseReconnectRes.error)
    }

    const finalStatus = pollUntilTerminal(orderId)

    check(null, {
        'order reached READY_TO_PAY': () => finalStatus === 'READY_TO_PAY',
    })

    if (finalStatus && !isTerminal(finalStatus)) {
        console.warn('unexpected final status: orderId=' + orderId + ' status=' + finalStatus)
    }

    sleep(2)
}
