# SnapTix

공정하고 안전한 선착순 티켓팅 플랫폼

---

## 코드 품질 도구

이 프로젝트는 **ktlint**와 **detekt**를 사용하여 코드 스타일과 정적 분석을 관리합니다.

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

#### IntelliJ 연동

ktlint 규칙을 IDE에 자동 적용하려면 아래 명령어로 IntelliJ 설정 파일을 생성합니다.

```bash
./gradlew ktlintApplyToIdea
```

---

### detekt

Kotlin 정적 분석 도구로 복잡도, 잠재적 버그, 코드 스타일 등을 검사합니다.

#### 명령어

```bash
# 전체 소스 분석
./gradlew detekt

# main 소스만 분석
./gradlew detektMain

# test 소스만 분석
./gradlew detektTest
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

### CI/CD 연동

PR 생성 시 GitHub Actions에서 ktlint와 detekt가 자동으로 실행됩니다.
두 검사 중 하나라도 실패하면 PR 머지가 차단됩니다.

로컬에서 PR 전에 미리 확인하려면:

```bash
./gradlew ktlintCheck detekt
```