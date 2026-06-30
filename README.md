# SnapTix

공정하고 안전한 선착순 티켓팅 플랫폼

---

## 코드 품질 도구

이 프로젝트는 **ktlint**, **detekt**, **Kover**를 사용하여 코드 스타일, 정적 분석, 테스트 커버리지를 관리합니다.

---

### ktlint

Kotlin 공식 코딩 컨벤션 기반의 코드 스타일 검사 도구입니다.

#### 명령어

```bash
# 스타일 위반 검사
./gradlew ktlintCheck
```

```bash
# 자동 수정 (수정 가능한 항목에 한해)
./gradlew ktlintFormat
```

#### 주요 규칙

- 들여쓰기: 스페이스 4칸
- 클래스 본문 시작/끝 빈 줄 금지
- 불필요한 세미콜론 금지
- import 와일드카드 금지

### detekt

Kotlin 정적 분석 도구로 복잡도, 잠재적 버그, 코드 스타일 등을 검사합니다.

#### 명령어

```bash
# 전체 소스 분석
./gradlew detekt
```

#### 설정 파일

규칙 설정은 `config/detekt/detekt.yml`에서 관리합니다.

주요 설정값:

| 규칙 | 기준값 |
|---|---|
| 함수 최대 줄 수 | 60줄 |
| 클래스 최대 줄 수 | 600줄 |
| 파일당 최대 함수 수 | 20개 |
| 최대 줄 길이 | 120자 |
| 최대 파라미터 수 (함수) | 6개 |
| 최대 파라미터 수 (생성자) | 7개 |
| 최대 중첩 깊이 | 4단계 |
| 최대 return 문 수 | 2개 |

#### 리포트

분석 결과는 `build/reports/detekt/` 에 생성됩니다.

| 파일 | 형식 |
|---|---|
| `detekt.html` | 브라우저에서 확인 가능한 HTML 리포트 |
| `detekt.xml` | CI/CD 연동용 Checkstyle XML |
| `detekt.sarif` | GitHub Code Scanning 연동용 SARIF |

#### 규칙 예외 처리

특정 줄에서 규칙을 무시해야 할 경우:

```kotlin
@Suppress("LongParameterList")
fun someFunction(a: String, b: String, c: String, d: String, e: String, f: String, g: String) { }
```

---

### Kover (테스트 커버리지)

JetBrains 공식 Kotlin 커버리지 도구입니다. Kotlin의 data class, lambda, inline function 등에서 발생하는 JaCoCo 집계 오차를 방지하기 위해 사용합니다.

#### 명령어

```bash
# HTML 리포트 생성
./gradlew koverHtmlReport
```

```bash
# 커버리지 기준 검증 (80% 미달 시 빌드 실패)
./gradlew koverVerify
```

#### 리포트

리포트는 `build/reports/kover/html/` 에 생성됩니다. `index.html`을 브라우저에서 열면 패키지별 상세 커버리지를 확인할 수 있습니다.

#### 커버리지 기준 및 제외 대상

최소 커버리지 기준은 **라인 80%** 이며, 아래 패키지는 측정에서 제외됩니다.

| 제외 패턴 | 이유 |
|---|---|
| `*.config.*` | 설정 클래스 |
| `*.dto.*` | 데이터 전달 객체 |
| `*Application*` | 애플리케이션 진입점 |
| `*.exception.*` | 예외 정의 클래스 |
| `com.develop.snaptix.staff.*` | deprecated 예정 모듈 |

> 패키지별 커버리지 현황은 [COVERAGE.md](./COVERAGE.md)를 참고하세요.

---

## 부하 테스트 (k6)

선착순 티켓팅 시나리오를 k6로 부하 테스트합니다. `loadtest` 프로파일로 앱을 기동하면 **시드 데이터가 자동으로 생성**되며, 별도 shell 스크립트 없이 k6를 실행할 수 있습니다.

---

### 사전 조건

| 도구 | 설치 |
|---|---|
| Docker | [docker.com](https://www.docker.com/) |
| k6 | [k6.io/docs/get-started/installation](https://k6.io/docs/get-started/installation/) |

`application-secret.yml`에 JWT 시크릿이 설정되어 있어야 합니다.

```yaml
# application-secret.yml
jwt:
  secret: <32자 이상의 시크릿 키>
```

---

### 실행 방법

#### 1단계 — 컨테이너 기동

```bash
docker compose -f docker-compose.loadtest.yml up -d
```

#### 2단계 — 앱 기동 (`loadtest` 프로파일)

```bash
SPRING_PROFILES_ACTIVE=loadtest ./gradlew bootRun
```

앱 기동 시 `LoadTestDataInitializer`가 자동으로 아래를 수행합니다.

| 작업 | 내용 |
|---|---|
| 어드민 생성 | `admin@snaptix.kr / Admin1234!` (멱등) |
| 테스트 유저 생성 | `load-user-1~200@test.com / Test1234!` 200명 (멱등) |
| 이벤트 생성 | `Load Test Event` — 구역 1개, 재고 100석 |
| 상태 전환 | `PENDING` → `ON_SALE` |
| 파일 출력 | `loadtest/seed/.env`, `loadtest/seed/users.json` |

기동 완료 로그에서 시드 결과를 확인할 수 있습니다.

```
[LOADTEST]  EVENT_ID        = <uuid>
[LOADTEST]  ZONE_ID         = <uuid>
[LOADTEST]  REDIS_STOCK_KEY = ZONE:<id>:stock
```

#### 3단계 — k6 실행

```bash
source loadtest/seed/.env
k6 run loadtest/main.js
```

---

### 생성 파일

앱 기동 후 아래 파일이 자동 생성됩니다. **커밋하지 마세요.**

| 파일 | 내용 |
|---|---|
| `loadtest/seed/.env` | `EVENT_ID`, `ZONE_ID`, `REDIS_STOCK_KEY` |
| `loadtest/seed/users.json` | k6에서 사용할 유저 목록 (이메일 / 패스워드) |

> `loadtest/seed/.gitignore`에 `.env`와 `users.json`이 포함되어 있는지 확인하세요.

### CI/CD 연동

PR 생성 시 GitHub Actions에서 ktlint, detekt, Kover가 자동으로 실행됩니다.
세 검사 중 하나라도 실패하면 PR 머지가 차단됩니다.

로컬에서 PR 전에 미리 확인하려면:

```bash
./gradlew ktlintCheck detekt koverVerify
```
