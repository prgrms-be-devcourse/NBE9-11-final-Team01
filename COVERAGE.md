# SnapTix — 테스트 커버리지 현황

> 마지막 측정일: 2026-06-30  
> 도구: Kover (JaCoCo 기반이 아닌 Kotlin 전용 커버리지 측정)

---

## 전체 요약

| 지표 | 커버리지 | 적용 / 전체 |
|---|---|---|
| Class | 94.8% | 182 / 192 |
| Method | 91.8% | 831 / 905 |
| **Branch** | **77.1%** ⚠ | 578 / 750 |
| Line | 92.2% | 3,437 / 3,726 |
| Instruction | 91.4% | 19,005 / 20,782 |

브랜치 커버리지(77.1%)가 목표 기준(80%) 미달 상태입니다. 나머지 지표는 양호합니다.

---

## 제외 대상 패키지

아래 패키지는 커버리지 측정 및 기준 검증에서 제외됩니다.

| 패키지 | 제외 이유 |
|---|---|
| `com.develop.snaptix.staff.*` | deprecated 예정 모듈 — 현재 기능상 사용되지 않으며 향후 제거 예정 |
| `*.config.*` | 설정 클래스 |
| `*.dto.*` | 데이터 전달 객체 |
| `*Application*` | 애플리케이션 진입점 |
| `*.exception.*` | 예외 정의 클래스 |

---

## 패키지별 커버리지

### 도메인 — auth

| 패키지 | Class | Method | Branch | Line |
|---|---|---|---|---|
| `domain.auth.controller` | 100% | 100% | — | 100% |
| `domain.auth.repository` | 100% | 100% | — | 100% |
| `domain.auth.service` | 100% | 100% | 87.5% | 96.6% |

### 도메인 — event

| 패키지 | Class | Method | Branch | Line |
|---|---|---|---|---|
| `domain.event.controller` | 100% | 100% | — | 100% |
| `domain.event.entity` | 100% | 100% | — | 100% |
| `domain.event.repository` | 93.8% | 92.5% | 66.7% ⚠ | 94.4% |
| `domain.event.scheduler` | 100% | 100% | — | 100% |
| `domain.event.service` | 93.3% | 98.6% | 82.2% | 96.4% |

### 도메인 — order

| 패키지 | Class | Method | Branch | Line |
|---|---|---|---|---|
| `domain.order.api.controller` | 100% | 100% | 37.5% ⚠ | 100% |
| `domain.order.api.service` | 100% | 100% | 90.6% | 99.2% |
| `domain.order.observability` | 100% | 92.9% | 66.7% ⚠ | 94.3% |
| `domain.order.scheduler` | 100% | 100% | 100% | 100% |
| `domain.order.worker` | 100% | 92.6% | 81% | 88.2% |
| `domain.order.worker.expiry` | 50% ⚠ | 40% ⚠ | 75% | 62.5% ⚠ |
| `domain.order.worker.release` | 100% | 100% | 90.9% | 100% |

### 도메인 — payment

| 패키지 | Class | Method | Branch | Line |
|---|---|---|---|---|
| `domain.payment.controller` | 100% | 100% | — | 100% |
| `domain.payment.repository` | 100% | 100% | 75% | 100% |
| `domain.payment.service` | 100% | 96.3% | 93.2% | 95.9% |

### 도메인 — reservation

| 패키지 | Class | Method | Branch | Line |
|---|---|---|---|---|
| `domain.reservation.controller` | 100% | 100% | — | 100% |
| `domain.reservation.entity` | 100% | 100% | — | 100% |
| `domain.reservation.repository` | 100% | 90.5% | 78.6% | 96.1% |
| `domain.reservation.scheduler` | 100% | 50% ⚠ | — | 41.4% ⚠ |
| `domain.reservation.service` | 100% | 100% | 100% | 100% |
| `domain.reservation.sse` | 100% | 100% | 100% | 100% |

### 도메인 — ticket

| 패키지 | Class | Method | Branch | Line |
|---|---|---|---|---|
| `domain.ticket.entity` | 50% | 91.7% | — | 84.6% |
| `domain.ticket.repository` | 50% ⚠ | 36.8% ⚠ | 33.3% ⚠ | 34.5% ⚠ |
| `domain.ticket.service` | 100% | 100% | 100% | 100% |

### 도메인 — user / zone

| 패키지 | Class | Method | Branch | Line |
|---|---|---|---|---|
| `domain.user.entity` | 100% | 100% | — | 100% |
| `domain.zone.entity` | 100% | 100% | — | 100% |
| `domain.zone.repository` | 75% | 46.7% ⚠ | 0% ⚠ | 58.3% ⚠ |

### 글로벌

| 패키지 | Class | Method | Branch | Line |
|---|---|---|---|---|
| `global.alert.model` | 100% | 100% | — | 100% |
| `global.alert.service` | 100% | 95% | 63.9% ⚠ | 90.8% |
| `global.aop.aspect` | 100% | 95.5% | 68.8% ⚠ | 92.5% |
| `global.aop.type` | 100% | 100% | — | 100% |
| `global.filter` | 100% | 100% | 100% | 100% |
| `global.observability` | 100% | 100% | 100% | 100% |
| `global.realtime` | 100% | 87.5% | 93.9% | 87.5% |
| `global.realtime.observability` | 100% | 76.9% | 100% | 82.9% |
| `global.realtime.port` | 100% | 100% | — | 100% |
| `global.realtime.subscribe` | 85.7% | 85.7% | 75% | 92% |
| `global.realtime.testing` | 50% | 56.2% | 65.2% | 73.5% |
| `global.redis.gateway` | 100% | 98.2% | 64.5% ⚠ | 92.4% |
| `global.redis.gateway.schema` | 100% | 100% | — | 100% |
| `global.redis.key` | 100% | 100% | — | 100% |
| `global.redis.resilience` | 100% | 100% | — | 100% |
| `global.redis.script` | 100% | 100% | — | 100% |
| `global.redis.support` | 100% | 83.3% | 100% | 78.6% |
| `global.resilience` | 100% | 94.7% | 75% | 96.4% |
| `global.security.auth` | 100% | 100% | 62.5% | 100% |
| `global.security.handler` | 100% | 100% | — | 100% |
| `global.security.jwt` | 100% | 100% | 84.6% | 100% |

---

## 보완 우선순위

### 즉시 보완 필요

| 패키지 | 핵심 문제 | 권장 조치 |
|---|---|---|
| `domain.ticket.repository` | Line 34.5%, Branch 33.3% | Testcontainers 통합 테스트 추가 |
| `domain.reservation.scheduler` | Line 41.4%, Method 50% | `@Scheduled` 메서드 직접 호출 단위 테스트 |
| `domain.zone.repository` | Line 58.3%, Branch 0% | 쿼리 분기 조건별 통합 테스트 추가 |

### 개선 권장

| 패키지 | 핵심 문제 | 권장 조치 |
|---|---|---|
| `domain.order.worker.expiry` | Line 62.5%, Method 40% | 만료 처리 워커 시나리오 테스트 보완 |
| `domain.order.api.controller` | Branch 37.5% (Line은 100%) | 인증 실패, 유효성 검증 실패 케이스 추가 |
| `global.redis.gateway` | Branch 64.5% | Redis 명령 실패 / fallback 분기 테스트 |
| `global.alert.service` | Branch 63.9% | 알림 발송 실패 케이스 테스트 |
| `global.aop.aspect` | Branch 68.8% | AOP 예외 경로 테스트 보완 |

---

## 커버리지 리포트 생성

```bash
# HTML 리포트 생성 (build/reports/kover/html/index.html)
./gradlew koverHtmlReport

# 커버리지 기준 검증 (80% 미달 시 빌드 실패)
./gradlew koverVerify
```
