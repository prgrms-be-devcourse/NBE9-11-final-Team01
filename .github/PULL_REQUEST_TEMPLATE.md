## 📌 관련 이슈
- Resolves: #이슈번호

## 📝 변경 사항 및 이유
<!-- 무엇을, 왜 변경했는지 간략히 설명해주세요 -->
-
-

## 🧪 테스트 방법
<!-- 리뷰어가 직접 검증할 수 있도록 재현 방법을 작성해주세요 -->
1. 로컬에서 `docker-compose up -d` 실행
2. Postman Collection의 해당 API 실행
3. (스크린샷 또는 GIF 첨부 권장)

## ✅ PR 생성자 체크리스트
- [ ] 본인 코드 셀프 리뷰 완료 (오타, 불필요한 로그·주석 제거)
- [ ] PR 크기 300~400줄 이내 유지
- [ ] `./gradlew ktlintCheck detekt` 및 `./gradlew test` 실행 완료
- [ ] 로컬 실행 및 관련 API 테스트 완료
- [ ] CI (빌드 / 테스트 / 정적 분석) 통과 확인

## 🔍 티켓팅 도메인 체크리스트
- [ ] N+1 쿼리 발생 여부 확인 (`JOIN FETCH` / `@EntityGraph` 적용)
- [ ] 트랜잭션(`@Transactional`) 범위 적절히 설정
- [ ] Controller에서 Entity 직접 반환 없이 DTO 변환
- [ ] 예외 처리를 `BusinessException` + `ErrorCode` Enum으로 일관 처리
- [ ] 좌석 예약/차감 로직에 비관적 락 또는 낙관적 락 적용
- [ ] 중복 예약 방지 로직 고려 (Unique Constraint / Redis 분산 락 등)
- [ ] 상태 값에 매직 넘버 없이 `Enum` 사용
- [ ] `!!` 연산자 미사용 (`?:` / `?.let` 활용)

## 💬 리뷰어에게 남기는 말
<!-- 중점적으로 봐줬으면 하는 부분, 고민했던 부분 등을 자유롭게 남겨주세요 -->
