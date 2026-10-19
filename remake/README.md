# byh-remake (R1)

`BuildYourHealth/`(2024, JSP/Servlet + MySQL)에서 세 가지 흐름만 표준 엔터프라이즈 스택으로 다시 구현한 결과물입니다. 근거 요구사항은 `docs/si/02_요구사항정의서.md`, 시나리오는 `docs/si/03_테스트시나리오.md` 2-B절(TC-201..TC-213)입니다.

구현한 흐름은 상품 목록 페이징·검색, 로그인, 주문 생성·내역·상세입니다.

## 스택

|항목|선택|이유|
|---|---|---|
|Spring Boot 4.1.1|Web MVC + JDBC + Flyway|3.5 OSS 지원이 2026-06-30 종료되어 4.1 라인을 사용합니다.|
|MyBatis|`mybatis-spring-boot-starter` 4.1.0, XML 매퍼|다음 마일스톤(R2)의 인덱스 적용 전/후 실행계획 측정을 위해 SQL이 그대로 보여야 합니다.|
|Oracle Free 23|Testcontainers(테스트), Docker Compose(로컬 실행)|로컬·무료 CI 제약을 지키면서 실제 Oracle 문법(OFFSET/FETCH, IDENTITY)을 그대로 씁니다.|
|Java 17, Maven Wrapper|`remake/mvnw`|고정된 스택 요구사항입니다.|

R1 스키마(V1·V2)에는 기본 키 인덱스만 있었습니다. Oracle은 외래 키 컬럼에 인덱스를 자동으로 만들지 않습니다. R2에서 주문 60만 건으로 인덱스 후보 다섯 가지를 측정했고(`docs/db-tuning.md`), 그 결과로 V3에서 두 개를 추가했습니다.

|인덱스|대상 조회|
|---|---|
|`IX_ORDER_ITEMS_ORDER` `ORDER_ITEMS(ORDER_ID)`|주문 상세, 주문 내역의 주문상품 조인|
|`IX_ORDERS_USER_DATE` `ORDERS(USER_ID, ORDER_DATE, ORDER_ID)`|주문 내역 페이지, 회원별 주문 건수|

측정 도구(`remake/perf/`)는 기준선 재현을 위해 V2(R1 스키마)까지만 마이그레이션하고, 인덱스는 시나리오마다 직접 만듭니다.

## 실행

```bash
docker compose -f remake/compose.yaml up -d --build
# 기동 대기 후
curl "http://localhost:8081/api/products?page=1&size=5"
docker compose -f remake/compose.yaml down -v
```

데모 계정은 `demo` / `demo1234`입니다(시드 데이터). 8080 포트는 AS-IS 앱이 사용하므로 이 앱은 8081에서 뜹니다. DB 접속 정보는 Compose가 환경 변수로만 넘기고 기본값은 로컬 전용입니다.

## 테스트

```bash
cd remake
./mvnw verify          # Windows에서는 sh mvnw verify
```

Docker(Testcontainers)가 필요합니다. Oracle Free 컨테이너를 띄워 Flyway 마이그레이션 후 실제 HTTP(MockMvc)로 검증합니다. GitHub Actions는 `.github/workflows/remake-ci.yml`에서 같은 명령을 실행합니다.

## API

|메서드·경로|인증|설명|
|---|---|---|
|`POST /api/auth/login`|불필요|`{"id","password"}` → 200 `{"id","name"}`, 세션 ID 재발급. 인증 실패는 ID/비밀번호 구분 없이 401, 전역 포화·계정 잠금은 429와 `Retry-After`|
|`POST /api/auth/logout`|불필요|204, 세션 무효화|
|`GET /api/products`|불필요|`page`, `size`, `field`(name/manufacturer), `keyword` → `{items,page,size,total}`|
|`POST /api/orders`|필요|주문 생성(단일 트랜잭션, DB 현재가로 합계 계산, 클라이언트 가격은 무시) → 201 `{orderId,totalPrice}`|
|`GET /api/orders`|필요|본인 주문 내역(최신순, 라인 포함, 페이지당 SQL 2건)|
|`GET /api/orders/{orderId}`|필요|본인 주문 상세, 타인 주문은 404|

오류 본문은 항상 `{"code","message"}`이며 예외 클래스명·스택 트레이스·DB 오류 코드는 노출하지 않습니다. 주문 API의 인증 검사는 `LoginInterceptor` 한 곳에서 수행합니다.

### 로그인 보호 (V3)

BCrypt cost 10을 유지하고, 없는 ID도 dummy hash로 한 번 검증합니다.
5분 안에 인증 실패가 5회 완료되면 60초 잠그고 성공 시 실패 상태를 지웁니다.
잠금은 올바른 비밀번호 요청에도 적용되며, 잠금 중 요청으로 만료가 계속 연장되지는 않습니다.
기존/없는 ID 모두 같은 정책이며 상태는 Oracle에 보존합니다.
잠금이 만료된 뒤의 첫 실패는 새 5분 창을 시작합니다.

전역 동시 로그인 제한이 먼저 적용됩니다. 전역 포화는 `Retry-After: 1`,
계정 잠금은 남은 시간을 초 단위 올림한 양의 정수입니다. 둘 다 같은 일반 429 본문을 사용합니다.
전역 거절은 계정 실패 횟수에 포함하지 않습니다. 잠금 전에 입장한 동시 요청은 완료할 수 있습니다.
비밀번호 검증 중 DB 트랜잭션/커넥션을 유지하지 않습니다.
전역 세마포어는 단일 인스턴스 용량 보호이며 분산 전역 제한은 아닙니다.
설계·실측·한계는 `docs/load-test-v3.md`에 있습니다.
