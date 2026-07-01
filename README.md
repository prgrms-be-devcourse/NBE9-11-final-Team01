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

### CI/CD 연동

PR 생성 시 GitHub Actions에서 ktlint, detekt, Kover가 자동으로 실행됩니다.
세 검사 중 하나라도 실패하면 PR 머지가 차단됩니다.

로컬에서 PR 전에 미리 확인하려면:

```bash
./gradlew ktlintCheck detekt koverVerify
```

---

## 부하 테스트 (k6)

선착순 티켓팅 시나리오를 k6로 부하 테스트합니다. `loadtest` 프로파일로 앱을 기동하면 시드 데이터(어드민 · 테스트 유저 · 이벤트 · 재고)가 자동 생성되며, 별도 DB 시딩 스크립트 없이 바로 k6를 실행할 수 있습니다.

사전 조건, 시나리오별 실행 방법, 환경변수·메트릭·임계값 전체 가이드는 [`loadtest/README.md`](./loadtest/README.md)를 참고하세요.

빠른 시작 (프로젝트 루트에서):

```bash
# 1. 부하 테스트 전용 컨테이너 기동 (MySQL:3307, Redis:6380)
docker compose -f docker-compose.loadtest.yml up -d

# 2. loadtest 프로파일로 앱 기동 → 시드 데이터 자동 생성
#    (application-secret.yml에 jwt.secret 설정 필요)
SPRING_PROFILES_ACTIVE=loadtest ./gradlew bootRun

# 3. (다른 터미널에서) 시나리오 실행
./loadtest/run.sh order-load
```

생성되는 `loadtest/seed/.env`, `loadtest/seed/users.json`은 커밋하지 않습니다.
