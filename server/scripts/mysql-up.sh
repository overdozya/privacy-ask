#!/usr/bin/env bash
# 로컬 MySQL 8.4를 Docker로 띄운다(06 U1). 포트·DB 이름·계정은 server/.env의 DB_URL·DB_USER·DB_PASSWORD를 따른다.
# 값은 화면에 내지 않는다(비밀번호는 명령줄이 아니라 환경변수로 넘김). 이미 있으면 켜기만 한다. 데이터는 이름 붙은 볼륨에 남는다.
#   scripts/mysql-up.sh            띄우기(또는 켜기)
#   scripts/mysql-up.sh --reset    컨테이너와 볼륨을 지우고 빈 DB로 다시(로컬 DB는 다시 만들 수 있다, 06 2.1)
set -euo pipefail
cd "$(dirname "$0")/.."

NAME="${PRIVACYASK_MYSQL_CONTAINER:-privacyask-mysql}"
VOLUME="${PRIVACYASK_MYSQL_VOLUME:-privacyask-mysql-data}"
IMAGE="mysql:8.4"

env_get() { # .env에서 KEY의 값(첫 '=' 뒤 전부). 환경변수가 있으면 그쪽이 이긴다(Spring과 같게)
  local key="$1" v=""
  if [[ -n "${!key:-}" ]]; then printf '%s' "${!key}"; return; fi
  [[ -f .env ]] || return 0
  v="$(grep -E "^${key}=" .env | tail -1 | cut -d= -f2-)" || true
  printf '%s' "$v"
}

DB_URL="$(env_get DB_URL)"; DB_USER="$(env_get DB_USER)"; DB_PASSWORD="$(env_get DB_PASSWORD)"
if [[ -z "$DB_URL" || -z "$DB_USER" ]]; then echo "server/.env에 DB_URL·DB_USER가 없다" >&2; exit 2; fi
if [[ -z "$DB_PASSWORD" ]]; then echo "server/.env의 DB_PASSWORD가 비어 있다. 채운 뒤 다시 돌린다" >&2; exit 2; fi
if [[ ! "$DB_URL" =~ ^jdbc:mysql://(127\.0\.0\.1|localhost):([0-9]+)/([A-Za-z0-9_]+)$ ]]; then
  echo "DB_URL은 jdbc:mysql://127.0.0.1:<포트>/<DB> 꼴이어야 한다" >&2; exit 2
fi
PORT="${BASH_REMATCH[2]}"; DB="${BASH_REMATCH[3]}"

if [[ "${1:-}" == "--reset" ]]; then
  docker rm -f "$NAME" >/dev/null 2>&1 || true
  docker volume rm "$VOLUME" >/dev/null 2>&1 || true
  echo "지움: 컨테이너 $NAME, 볼륨 $VOLUME"
fi

if docker container inspect "$NAME" >/dev/null 2>&1; then
  docker start "$NAME" >/dev/null
  echo "켬: $NAME"
else
  if [[ "$DB_USER" == "root" ]]; then
    MYSQL_ROOT_PASSWORD="$DB_PASSWORD" docker run -d --name "$NAME" \
      -p "127.0.0.1:${PORT}:3306" -v "${VOLUME}:/var/lib/mysql" \
      -e MYSQL_ROOT_PASSWORD -e MYSQL_DATABASE="$DB" \
      "$IMAGE" --character-set-server=utf8mb4 --collation-server=utf8mb4_0900_ai_ci --default-time-zone=+00:00 >/dev/null
  else
    MYSQL_USER="$DB_USER" MYSQL_PASSWORD="$DB_PASSWORD" docker run -d --name "$NAME" \
      -p "127.0.0.1:${PORT}:3306" -v "${VOLUME}:/var/lib/mysql" \
      -e MYSQL_USER -e MYSQL_PASSWORD -e MYSQL_DATABASE="$DB" -e MYSQL_RANDOM_ROOT_PASSWORD=yes \
      "$IMAGE" --character-set-server=utf8mb4 --collation-server=utf8mb4_0900_ai_ci --default-time-zone=+00:00 >/dev/null
  fi
  echo "만듦: $NAME ($IMAGE, 볼륨 $VOLUME, 127.0.0.1 포트 .env대로)"
fi

printf '기다림'
for _ in $(seq 1 90); do
  if docker exec "$NAME" sh -c 'MYSQL_PWD="${MYSQL_PASSWORD:-$MYSQL_ROOT_PASSWORD}" mysqladmin ping -h127.0.0.1 -u"${MYSQL_USER:-root}" --silent' >/dev/null 2>&1; then
    echo " 준비됨"; exit 0
  fi
  printf '.'; sleep 2
done
echo " 시간 초과(docker logs $NAME 로 본다)" >&2; exit 1
