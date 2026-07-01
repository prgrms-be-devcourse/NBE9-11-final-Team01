# SnapTix — 테스트 커버리지 현황

> 마지막 측정일: 2026-07-01  
> 도구: Kover (JaCoCo 기반이 아닌 Kotlin 전용 커버리지 측정)

---

## 전체 요약

| 지표 | 커버리지 | 적용 / 전체 |
|---|---|---|
| Class | 97.4% | 184 / 189 |
| Method | 94.3% | 846 / 897 |
| **Branch** | **79.7%** ⚠ | 596 / 748 |
| Line | 95.1% | 3,530 / 3,712 |
| Instruction | 94.1% | 19,432 / 20,656 |

브랜치 커버리지(79.7%)가 목표 기준(80%)에 근소하게 미달합니다. 나머지 지표는 모두 양호합니다. (이전 측정 대비 Branch 77.1% → 79.7%, Line 92.2% → 95.1% 로 전반 개선)

---

## 제외 대상 패키지

아래 패키지는 커버리지 측정 및 기준 검증에서 제외됩니다.

| 패키지 | 제외 이유 |
|---|---|
| `com.develop.snaptix.staff.*` | deprecated 모듈 — 제거 완료/예정, 기능상 미사용 |
| `*.loadtest.*` | 부하 테스트 전용 인프라 코드 |
| `*.config.*` | 설정 클래스 |
| `*.dto.*` | 데이터 전달 객체 |
| `*Application*` | 애플리케이션 진입점 |
| `*.exception.*` | 예외 정의 클래스 |

---

## 패키지별 커버리지

### 도메인 — auditlog

| 패키지 | Class | Method | Branch | Line |
|---|---|---|---|---|
| `domain.auditlog.entity` | 100% | 83.3% | — | 100% |
| `domain.auditlog.repository` | 100% | 100% | — | 100% |

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
| `domain.event.repository` | 93.8% | 92.5% | 81.8% | 95.3% |
| `domain.event.scheduler` | 100% | 100% | — | 100% |
| `domain.event.service` | 93.3% | 98.7% | 88.1% | 96.4% |

### 도메인 — order

| 패키지 | Class | Method | Branch | Line |
|---|---|---|---|---|
| `domain.order.api.controller` | 100% | 100% | 37.5% ⚠ | 100% |
| `domain.order.api.service` | 100% | 100% | 90.6% | 99.2% |
| `domain.order.observability` | 100% | 92.9% | 66.7% ⚠ | 94.3% |
| `domain.order.scheduler` | 100% | 100% | 100% | 100% |
| `domain.order.worker` | 100% | 92.6% | 81% | 88.2% |
| `domain.order.worker.expiry` | 100% | 66.7% ⚠ | 100% | 93.8% |
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
| `domain.reservation.scheduler` | 100% | 100% | — | 100% |
| `domain.reservation.service` | 100% | 100% | 100% | 100% |
| `domain.reservation.sse` | 100% | 100% | 100% | 100% |

### 도메인 — ticket

| 패키지 | Class | Method | Branch | Line |
|---|---|---|---|---|
| `domain.ticket.entity` | 50% | 91.7% | — | 84.6% |
| `domain.ticket.repository` | 100% | 78.9% | 100% | 82.8% |
| `domain.ticket.service` | 100% | 100% | 100% | 100% |

### 도메인 — user / zone

| 패키지 | Class | Method | Branch | Line |
|---|---|---|---|---|
| `domain.user.entity` | 100% | 100% | — | 100% |
| `domain.zone.entity` | 100% | 100% | — | 100% |
| `domain.zone.repository` | 100% | 88.9% | — | 100% |

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
| `global.realtime.observability` | 100% | 76.9% ⚠ | 100% | 82.9% |
| `global.realtime.port` | 100% | 100% | — | 100% |
| `global.realtime.subscribe` | 85.7% | 85.7% | 75% | 92.5% |
| `global.realtime.testing` | 50% ⚠ | 56.2% ⚠ | 65.2% ⚠ | 73.5% ⚠ |
| `global.redis.gateway` | 100% | 99.1% | 65.5% ⚠ | 97.4% |
| `global.redis.gateway.schema` | 100% | 100% | — | 100% |
| `global.redis.key` | 100% | 100% | — | 100% |
| `global.redis.resilience` | 100% | 100% | — | 100% |
| `global.redis.script` | 100% | 100% | — | 100% |
| `global.redis.support` | 100% | 83.3% | 100% | 78.6% ⚠ |
| `global.resilience` | 100% | 94.7% | 75% | 96.4% |
| `global.security.auth` | 100% | 100% | 62.5% ⚠ | 100% |
| `global.security.handler` | 100% | 100% | — | 100% |
| `global.security.jwt` | 100% | 92% | 84.6% | 98% |

---

## 최근 개선 (해소된 항목)

이전 측정에서 "즉시 보완 필요"였던 패키지가 이번에 모두 개선되었습니다.

| 패키지 | 이전 | 현재 | 관련 |
|---|---|---|---|
| `domain.ticket.repository` | Line 34.5% / Branch 33.3% | Line 82.8% / Branch 100% | #335 |
| `domain.reservation.scheduler` | Line 41.4% / Method 50% | Line 100% / Method 100% | #339 |
| `domain.zone.repository` | Line 58.3% / Branch 0% | Line 100% / Method 88.9% | #340 |
| `domain.order.worker.expiry` | Line 62.5% / Method 40% | Line 93.8% / Branch 100% | #341 |

---

## 보완 우선순위

### 개선 권장 (Branch 위주)

| 패키지 | 핵심 문제 | 권장 조치 |
|---|---|---|
| `domain.order.api.controller` | Branch 37.5% (Line은 100%) | 인증 실패·유효성 검증 실패 케이스 추가 |
| `global.security.auth` | Branch 62.5% | 인증 분기(성공/실패) 케이스 보완 |
| `global.alert.service` | Branch 63.9% | 알림 발송 실패 케이스 테스트 |
| `global.redis.gateway` | Branch 65.5% | 실패/fallback 분기 (일부 Elvis 분기는 도달 불가로 수용, #343) |
| `domain.order.observability` | Branch 66.7% | 메트릭 계측 분기 케이스 보완 |
| `global.aop.aspect` | Branch 68.8% | AOP 예외 경로 테스트 보완 |

### 낮은 우선순위 / 검토 대상

| 패키지 | 핵심 문제 | 비고 |
|---|---|---|
| `global.realtime.testing` | Line 73.5% / Method 56.2% | 테스트 지원용 코드 — 제외 대상 여부 검토 |
| `global.redis.support` | Line 78.6% | 유틸 분기 보완 |
| `domain.ticket.entity` | Class 50% (1/2) | 미사용 클래스 존재 여부 확인 |

---

## 커버리지 리포트 생성

```bash
# HTML 리포트 생성 (build/reports/kover/html/index.html)
./gradlew koverHtmlReport

# 커버리지 기준 검증 (현재 기준 70% 미달 시 빌드 실패 — 목표 80%로 상향 예정)
./gradlew koverVerify
```

> 현재 `koverVerify` 기준은 **라인 70%**(임시)입니다. 미달 패키지 보완 후 최종 목표인 **80%**로 상향할 예정입니다. (#327)
