#!/usr/bin/env bash
# 적재 파일 4개(레포 밖 data/out/mysql)를 순서대로 넣는다(06 2.5). 서버를 한 번 띄워 Flyway V1~V7이 들어간 DB에 돌린다.
# 파일마다 한 트랜잭션이고, 끝의 확인 SELECT가 테이블별 actual/expected/ok를 낸다. 오류가 나면 그 자리에서 멈춘다.
set -euo pipefail
cd "$(dirname "$0")/.."
NAME="${PRIVACYASK_MYSQL_CONTAINER:-privacyask-mysql}"
DIR="${PRIVACYASK_DATA_DIR:-../data}/out/mysql"
[[ -f "$DIR/manifest.json" ]] || { echo "적재 파일이 없다: $DIR" >&2; exit 2; }
for f in 10_kb 20_packages 30_ledger 40_case_catalog; do
  echo "== $f.sql"
  t0=$(date +%s)
  docker exec -i "$NAME" sh -c 'MYSQL_PWD="${MYSQL_PASSWORD:-$MYSQL_ROOT_PASSWORD}" mysql -u"${MYSQL_USER:-root}" --default-character-set=utf8mb4 --table "$MYSQL_DATABASE"' < "$DIR/$f.sql"
  echo "   ($(( $(date +%s) - t0 ))초)"
done
