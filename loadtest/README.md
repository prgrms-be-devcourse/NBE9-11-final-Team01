# SnapTix k6 부하 테스트

선착순 티켓팅 시나리오를 k6로 부하 테스트합니다. `loadtest` 프로파일로 앱을 기동하면 시드 데이터가 자동 생성되며, 별도 shell 스크립트 없이 바로 k6를 실행할 수 있습니다.

> 이 문서는 `loadtest/` 디렉터리 안에 위치합니다. 코드 품질 도구(ktlint/detekt/Kover) 등 프로젝트 전반의 내용은 루트 [`README.md`](../README.md)를 참고하세요.

---

## 전제 조건

| 도구 | 용도 | 설치 |
|---|---|---|
| Docker / Docker Compose | 부하 테스트용 MySQL·Redis 기동 | [docker.com](https://www.docker.com/) |
| k6 | ≥ 0.50.0 — 부하 테스트 실행 | [k6.io/docs/get-started/installation](https://k6.io/docs/get-started/installation/) |

k6 설치:

```bash
# macOS
brew install k6

# Linux
sudo apt install k6

# Windows
choco install k6
```

앱을 `loadtest` 프로파일로 기동하려면 `application-secret.yml`에 JWT 시크릿이 설정되어 있어야 합니다.

```yaml
# application-secret.yml
jwt:
  secret: <32자 이상의 시크릿 키>
```

---

## 실행 절차

프로젝트 루트에서 진행합니다.

### 1단계 — 컨테이너 기동

`docker-compose.loadtest.yml`은 dev 환경(`docker-compose.yml`)과 포트 충돌 없이 병렬로 기동됩니다 (MySQL: 3307, Redis: 6380).

```bash
docker compose -f docker-compose.loadtest.yml up -d
```

### 2단계 — 앱 기동 (`loadtest` 프로파일)

```bash
SPRING_PROFILES_ACTIVE=loadtest ./gradlew bootRun
```

앱 기동 시 `LoadTestDataInitializer`가 자동으로 아래를 수행합니다 (모두 멱등).

| 작업 | 내용 |
|---|---|
| 어드민 생성 | `admin@snaptix.kr / Admin1234!` |
| 테스트 유저 생성 | `load-user-1~200@test.com / Test1234!` 200명 |
| 이벤트 생성 | `Load Test Event` — 구역 1개, 재고 100석 |
| 상태 전환 | `PENDING` → `ON_SALE` |
| 파일 출력 | `loadtest/seed/.env`, `loadtest/seed/users.json` |

기동 완료 로그에서 시드 결과를 확인할 수 있습니다.

```
[LOADTEST]  EVENT_ID        = <uuid>
[LOADTEST]  ZONE_ID         = <uuid>
[LOADTEST]  REDIS_STOCK_KEY = ZONE:<id>:stock
```

생성된 `loadtest/seed/.env` 예시:

```env
BASE_URL=http://localhost:8080
EVENT_ID=f47ac10b-58cc-4372-a567-0e02b2c3d479
ZONE_ID=550e8400-e29b-41d4-a716-446655440000   # 공개 UUID (참고용)
REDIS_STOCK_KEY=ZONE:501:stock                  # 내부 ID 파싱에 사용
```

> k6 스크립트는 `REDIS_STOCK_KEY`에서 내부 `zoneId(Long)`를 파싱해 주문 요청에 사용합니다.
> (`OrderRequest.zoneId`는 Long 타입이며 공개 UUID와 다릅니다.)

### 3단계 — 시나리오 실행

k6에는 `--env-file` 플래그가 없으므로 `loadtest/run.sh` 래퍼를 사용합니다. 래퍼가 `.env`를 source한 뒤 k6를 호출합니다.

#### order-load.js — 주문 인제스트 부하 테스트

초당 20건 고정 요청(`constant-arrival-rate`), 30초 지속.
주문 생성 → 상태 폴링 → teardown 오버셀 검증.

```bash
./loadtest/run.sh order-load
```

#### sse-reconnect.js — SSE 연결·재연결 내구성 테스트

10 VU, 60초 지속.
SSE 초기 연결 → 의도적 끊김 → 재연결(Last-Event-ID) → 상태 폴링.

```bash
./loadtest/run.sh sse-reconnect
```

직접 실행이 필요한 경우 (Linux/macOS):

```bash
set -a && source loadtest/seed/.env && set +a
k6 run loadtest/scenarios/order-load.js
```

---

## 생성 파일 (커밋 금지)

앱 기동 후 아래 파일이 자동 생성됩니다.

| 파일 | 내용 |
|---|---|
| `loadtest/seed/.env` | `EVENT_ID`, `ZONE_ID`, `REDIS_STOCK_KEY` |
| `loadtest/seed/users.json` | k6에서 사용할 유저 목록 (이메일 / 패스워드) |

`loadtest/seed/.gitignore`에 `.env`와 `users.json`이 포함되어 있는지 확인하세요. 템플릿은 `loadtest/seed/.env.example`을 참고합니다.

```bash
cp loadtest/seed/.env.example loadtest/seed/.env
```

---

## 환경변수 참조

| 변수 | 필수 | 설명 |
|------|------|------|
| `BASE_URL` | ✓ | 서버 주소 (기본값: `http://localhost:8080`) |
| `EVENT_ID` | ✓ | 시드된 이벤트 UUID |
| `REDIS_STOCK_KEY` | ✓ | `ZONE:<id>:stock` 형식. 내부 zoneId 파싱에 사용. |

`.env.example`에는 `LoadTestDataInitializer` 시딩 옵션(`USER_COUNT`, `USER_PASSWORD`, `TOTAL_CAPACITY` 등)도 정의되어 있으며, `EVENT_ID` / `ZONE_ID` / `REDIS_STOCK_KEY`는 2단계 앱 기동 후 자동으로 채워집니다.

---

## 커스텀 메트릭

| 메트릭 | 종류 | 설명 |
|--------|------|------|
| `oversell_errors` | Counter | 재고 음수(오버셀) 건수. 0이어야 정상. |
| `backpressure_hit` | Rate | 429 응답 비율. 20% 초과 시 경보. |
| `sse_connect_fail` | Counter | SSE 초기 연결 실패 건수. |
| `sse_reconnect_fail` | Counter | SSE 재연결 실패 건수. |

---

## 임계값 (Thresholds)

`loadtest/thresholds.js`에 공통 정의:

| 임계값 | 기준 |
|--------|------|
| `http_req_failed` | `rate < 1%` |
| `http_req_duration` | `p(95) < 300ms`, `p(99) < 600ms` |
| `oversell_errors` | `count < 1` |
| `backpressure_hit` | `rate < 20%` |

임계값을 초과하면 k6가 exit code 99로 종료됩니다.

---

## 결과 해석

### 정상 응답 코드

| 코드 | 의미 |
|------|------|
| `202` | 주문 수락 (비동기 처리 시작) |
| `429` | 백프레셔 — Redis 스트림 포화 또는 재고 소진. 일정 비율 이내는 정상. |
| `409` | 멱등 충돌 — 동일 사용자 중복 주문. 정상 동작. |

### 주문 상태 흐름

```
PENDING → READY_TO_PAY → (결제 완료 시) CONFIRMED
        ↘ FAILED / EXPIRED
```

### 오버셀 발생 시

teardown 로그에서 확인합니다.

```
[OVERSELL] zoneId=... name=A구역 stock=-1
```

`oversell_errors` 카운터가 1 이상이면 임계값 실패로 처리됩니다.

---

## 파일 구조

```
loadtest/
├── scenarios/
│   ├── order-load.js       # 주문 인제스트 부하 테스트
│   └── sse-reconnect.js    # SSE 연결·재연결 내구성 테스트
├── utils/
│   └── helpers.js          # 공통 API 호출 함수
├── thresholds.js           # 공유 임계값 상수
├── run.sh                  # .env 로드 후 k6 실행 래퍼
├── README.md               # (이 파일)
└── seed/
    ├── .env.example        # 환경변수 템플릿
    ├── .env                # LoadTestDataInitializer 자동 생성
    └── users.json          # LoadTestDataInitializer 자동 생성
```
