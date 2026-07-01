#!/usr/bin/env bash
# loadtest/run.sh — .env를 로드해 k6를 실행하는 래퍼 스크립트
#
# 사용법:
#   ./loadtest/run.sh order-load      # 주문 인제스트 시나리오
#   ./loadtest/run.sh sse-reconnect   # SSE 재연결 시나리오
#
# 프로젝트 루트에서 실행하세요.

set -euo pipefail

SCENARIO="${1:-order-load}"
ENV_FILE="loadtest/seed/.env"
SCRIPT="loadtest/scenarios/${SCENARIO}.js"

# .env 파일 존재 확인
if [[ ! -f "$ENV_FILE" ]]; then
  echo "[오류] $ENV_FILE 파일이 없습니다."
  echo "       SPRING_PROFILES_ACTIVE=loadtest ./gradlew bootRun 을 먼저 실행해 시드 데이터를 생성하세요."
  exit 1
fi

# 시나리오 파일 존재 확인
if [[ ! -f "$SCRIPT" ]]; then
  echo "[오류] 시나리오 파일을 찾을 수 없습니다: $SCRIPT"
  echo "       사용 가능한 시나리오: order-load, sse-reconnect"
  exit 1
fi

# .env 로드 (주석·빈 줄 제외)
set -a
# shellcheck source=/dev/null
source "$ENV_FILE"
set +a

echo "[k6] 시나리오: $SCRIPT"
echo "[k6] BASE_URL=$BASE_URL | EVENT_ID=$EVENT_ID | REDIS_STOCK_KEY=$REDIS_STOCK_KEY"
echo "--------------------------------------"

k6 run "$SCRIPT"
