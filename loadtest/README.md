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

## 왜 매 실행 전에 초기화하는가

`order-load.js`와 `sse-reconnect.js`는 같은 유저 풀(`load-user-1~200`)과 같은 `EVENT_ID`/`ZONE_ID`를 공유합니다. 앱을 재기동하지 않고 시나리오를 연달아 실행하면 다음 문제가 생깁니다.

- **재고 소진**: `order-load.js`는 재고 100석짜리 zone에 최대 600건을 요청합니다. 한 번만 실행해도 재고가 대부분 소진되어, 재기동 없이 바로 다음 테스트를 돌리면 대부분 `429`(백프레셔/재고소진)로 처리되고 정상 주문 접수 경로가 검증되지 않습니다.
- **멱등 충돌**: 서버의 멱등 키는 `userId + eventId` 단위(1인 1이벤트 1매)입니다. 같은 유저가 같은 이벤트에 이미 주문을 넣었다면 재시도는 `409`로 처리됩니다. `order-load`(유저 1~200)와 `sse-reconnect`(유저 1~10)는 유저 풀이 겹치므로, 재기동 없이 `order-load` 다음에 `sse-reconnect`를 돌리면 대부분 `409`를 받아 스킵되고 **SSE 검증 자체가 사실상 이뤄지지 않은 채로 "통과"** 처리될 수 있습니다.

이벤트를 생성하는 HTTP API는 따로 없고(`EventController`는 조회 전용), 앱 기동 시 `LoadTestDataInitializer`만이 새 이벤트·구역·재고를 시딩합니다. 그래서 "항상 깨끗한 상태에서 시작"을 보장하는 방법은 **Redis를 비우고 앱을 재기동**하는 것뿐입니다. `run.sh`는 이 과정을 기본 동작(`--reset`)으로 자동화합니다.

---

## 실행 절차

프로젝트 루트에서 진행합니다.

### 1단계 — 컨테이너 기동 (최초 1회)

`docker-compose.loadtest.yml`은 dev 환경(`docker-compose.yml`)과 포트 충돌 없이 병렬로 기동됩니다 (MySQL: 3307, Redis: 6380).

```bash
docker compose -f docker-compose.loadtest.yml up -d
```

컨테이너는 세션 동안 계속 띄워두면 됩니다. `run.sh`가 매 실행마다 Redis 내용만 `FLUSHALL`로 비우고, 앱을 재기동해 새 이벤트/재고를 시딩합니다.

### 2단계 — 시나리오 실행

```bash
./loadtest/run.sh order-load
./loadtest/run.sh sse-reconnect
```

인자를 생략하면 `order-load`가 기본값입니다. 기본 동작(`--reset`)은 다음을 순서대로 수행합니다.

1. 이전에 `run.sh`가 띄워둔 앱(또는 `:8080`을 점유 중인 다른 프로세스)을 종료
2. Redis 컨테이너에 `FLUSHALL` 실행 (재고/멱등/holds/rate-limit 등 모든 키 초기화)
3. `SPRING_PROFILES_ACTIVE=loadtest ./gradlew bootRun`을 백그라운드로 재기동
4. 로그에서 `[LOADTEST] 시드 완료` 마커를 확인할 때까지 대기 (새 `EVENT_ID`/`ZONE_ID`가 `loadtest/seed/.env`에 반영됨)
5. `/actuator/health`가 `UP`이 될 때까지 짧게 대기
6. `loadtest/seed/.env`를 로드해 k6 실행

앱은 k6 실행이 끝나도 백그라운드에서 계속 떠 있습니다(로그 확인, 수동 API 호출 등에 사용 가능). 필요할 때 종료하세요.

```bash
./loadtest/run.sh stop
```

### 옵션

| 옵션 | 설명 |
|---|---|
| `--reset` (기본값) | Redis FLUSHALL + 앱 재기동 후 실행. 이전 테스트 상태와 무관하게 항상 깨끗하게 시작. |
| `--no-reset` | 초기화를 건너뛰고 기존 `loadtest/seed/.env`를 그대로 사용. 앱을 별도 터미널에서 직접 기동해 관리하던 기존 방식과 호환됩니다. 재고/멱등 상태가 이전 실행에서 이어진다는 점을 감안하고 사용하세요. |

```bash
# 기존 방식: 앱을 별도 터미널에서 직접 띄우고, run.sh는 초기화 없이 실행
SPRING_PROFILES_ACTIVE=loadtest ./gradlew bootRun
./loadtest/run.sh order-load --no-reset
```

### 직접 실행이 필요한 경우 (Linux/macOS)

앱이 이미 떠 있고 `.env`가 최신 상태라고 확신할 때만 사용하세요(=초기화를 직접 책임지는 경우).

```bash
set -a && source loadtest/seed/.env && set +a
k6 run loadtest/scenarios/order-load.js
```

---

## 생성/실행 산출물 (커밋 금지)

| 파일 | 내용 |
|---|---|
| `loadtest/seed/.env` | `EVENT_ID`, `ZONE_ID`, `REDIS_STOCK_KEY` — 앱 기동 시 자동 생성/갱신 |
| `loadtest/seed/users.json` | k6에서 사용할 유저 목록 (이메일 / 패스워드) |
| `loadtest/results/.run/app.log` | `run.sh`가 재기동한 앱의 stdout/stderr 로그 |
| `loadtest/results/.run/app.pid` | `run.sh`가 재기동한 앱의 PID (`stop` 서브커맨드가 사용) |

`loadtest/seed/.env`, `users.json`, `loadtest/results/`는 루트 `.gitignore`에 이미 포함되어 있습니다. 템플릿은 `loadtest/seed/.env.example`을 참고합니다.

`--reset`(기본값)으로 실행하면 `loadtest/seed/.env`가 없을 때 `run.sh`가 `.env.example`을 복사해 자동으로 만들어줍니다 — 최초 실행이라도 별도로 `cp`할 필요는 없습니다. `--no-reset`으로 직접 앱을 관리하는 경우에는 최초 1회 아래처럼 직접 생성해두세요.

```bash
cp loadtest/seed/.env.example loadtest/seed/.env
```

---

## 주기적 정리 (선택)

`--reset`은 Redis만 비우고 MySQL은 건드리지 않습니다. 이벤트/구역이 매번 새 UUID로 생성되므로 이전 데이터와 충돌하지는 않지만, 반복 실행할수록 loadtest MySQL에 이벤트·주문 데이터가 계속 쌓입니다. 기능적으로 문제는 아니지만, 필요하면 컨테이너를 통째로 재생성해 완전히 비우세요.

```bash
docker compose -f docker-compose.loadtest.yml down
docker compose -f docker-compose.loadtest.yml up -d
```

---

## 환경변수 참조

| 변수 | 필수 | 설명 |
|------|------|------|
| `BASE_URL` | ✓ | 서버 주소 (기본값: `http://localhost:8080`) |
| `EVENT_ID` | ✓ | 시드된 이벤트 UUID |
| `REDIS_STOCK_KEY` | ✓ | `ZONE:<id>:stock` 형식. 내부 zoneId 파싱에 사용. |
| `REDIS_CONTAINER` | - | `run.sh`가 FLUSHALL을 실행할 Redis 컨테이너명 (기본값: `snaptix-redis-loadtest`) |
| `APP_PORT` | - | `run.sh`가 헬스체크·포트 정리에 사용할 앱 포트 (기본값: `8080`) |
| `SEED_TIMEOUT_SEC` | - | 시드 완료 대기 타임아웃(초). 콜드 빌드 시 늘려서 사용 (기본값: `180`) |

`.env.example`에는 `LoadTestDataInitializer` 시딩 옵션(`USER_COUNT`, `USER_PASSWORD`, `TOTAL_CAPACITY` 등)도 정의되어 있으며, `EVENT_ID` / `ZONE_ID` / `REDIS_STOCK_KEY`는 `run.sh` 실행(재기동) 후 자동으로 채워집니다.

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
├── run.sh                  # Redis FLUSHALL + 앱 재기동 후 k6 실행하는 래퍼
├── README.md               # (이 파일)
├── seed/
│   ├── .env.example        # 환경변수 템플릿
│   ├── .env                # LoadTestDataInitializer 자동 생성/갱신
│   └── users.json          # LoadTestDataInitializer 자동 생성
└── results/
    └── .run/
        ├── app.log          # run.sh가 재기동한 앱 로그
        └── app.pid          # run.sh가 재기동한 앱 PID
```
