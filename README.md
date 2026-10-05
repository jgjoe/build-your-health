# Build Your Health

**JSP/Servlet로 만든 건강 관리 웹 애플리케이션(2024)과, 그 핵심 흐름을 Spring Boot·MyBatis·Oracle로 다시 구축하고 DB 인덱스를 실측한 리메이크(2026)**

[![Remake](https://img.shields.io/badge/remake-Spring%20Boot%204.1%20%2B%20MyBatis-6DB33F?logo=springboot&logoColor=white)](#2026-리메이크)
[![DB](https://img.shields.io/badge/Oracle-index%20tuning-F80000?logo=oracle&logoColor=white)](#인덱스-실측-주문-60만-건)
[![Original](https://img.shields.io/badge/2024-JSP%20%2F%20Servlet%20%2B%20MySQL-007396?logo=openjdk&logoColor=white)](#원본-2024)

물 섭취량과 수면 시간을 기록하고, 건강 상품을 주문하고, 게시판에서 정보를 나누는 웹 애플리케이션입니다.
2024년에 JSP/Servlet·MySQL로 화면·서버·DB·관리자 기능까지 혼자 만들었습니다(업무 도메인 8개, JSP 54개).
2026년에는 원본을 현행 분석해 요구사항을 정의하고, 상품 조회·로그인·주문 흐름을 표준 엔터프라이즈 스택으로 다시 구축한 뒤 주문 60만 건 데이터에서 인덱스를 측정해 스키마에 반영했습니다.

---

## 2026 리메이크

현행 분석서·요구사항정의서·테스트 시나리오(`docs/si/`)를 먼저 작성하고, 원본의 데이터 접근·트랜잭션·조회 구조를 다시 만들었습니다.
원본은 `BuildYourHealth/`에 그대로 두고 리메이크는 `remake/`에 있습니다.

| 항목 | 원본 (2024) | 리메이크 (`remake/`, 흐름 3개) |
|---|---|---|
| 스택 | JSP/Servlet, JDBC, MySQL | Spring Boot 4.1, MyBatis XML 매퍼, Oracle Database Free |
| DB 접속 정보 | 16개 파일에 하드코딩 | 환경 변수 한 곳. 소스에 접속 문자열이 없는지 테스트로 검사 |
| 데이터 접근 | DAO, JSP 안의 JDBC, JSTL SQL 태그 세 가지 공존 | MyBatis 매퍼 하나, 값은 바인드 변수로만 전달 |
| 커넥션 | 풀 없음, 일부 화면은 커넥션을 닫지 않음 | 커넥션 풀(HikariCP) |
| 주문 저장 | 쓰기마다 자동 커밋. 완료 화면을 새로고침하면 0원 주문이 다시 등록됨 | 주문·주문상품을 한 트랜잭션으로 저장, 합계는 DB 현재가로 계산 |
| 목록 조회 | 상품·주문 목록 페이징 없음, 주문 내역은 주문 수만큼 추가 쿼리(N+1) | SQL 페이징(`OFFSET … FETCH`), 주문 내역은 페이지당 SQL 2건 |
| 검증 | 수동 확인 | 테스트 54건(실제 Oracle 컨테이너 통합 테스트 포함), GitHub Actions |
| 성능 | 측정하지 않음 | 주문 60만 건에서 인덱스 후보 5가지를 실측하고 2개를 스키마에 반영 |

### 인덱스 실측 (주문 60만 건)

원본에서 옮겨 온 스키마는 기본 키만 인덱스로 갖고 있었습니다. 앱의 매퍼 SQL 그대로 인덱스 후보 5가지를 비교했고,
주문 내역을 가장 적게 읽은 조합(`ORDER_ITEMS(ORDER_ID)`, `ORDERS(USER_ID, ORDER_DATE, ORDER_ID)`)을 Flyway V3로 반영했습니다.

| 조회 | 인덱스 없음 → 적용 후 (응답시간 중앙값) | 읽은 블록 (buffer gets) |
|---|---|---|
| 주문 상세 | 20.1ms → 0.70ms | 6,285 → 14 |
| 주문 내역 첫 페이지 (주문 150건 회원) | 85.0ms → 0.97ms | 14,363 → 67 |

- 인덱스 없이 전체 스캔하던 10곳 중 9곳이 인덱스 범위 스캔으로 바뀌었습니다.
- 같은 세 컬럼이라도 순서를 `(ORDER_DATE, ORDER_ID, USER_ID)`로 뒤집으면 주문 내역 조회에 인덱스가 쓰이지 않았습니다(67 → 8,120 블록). 등치 조건 컬럼을 앞에 두는 이유를 실측으로 확인한 결과입니다.

측정 조건: Docker의 Oracle Free(VM 4 CPU), 회원 22,021·주문 600,000·주문상품 1,499,546행(고정 시드), 케이스당 워밍업 5회 후 30회 측정을 2회 반복, 웜 캐시, 1회차 클라이언트 중앙값.
설계·전체 결과·실행계획 원문은 [`docs/db-tuning.md`](docs/db-tuning.md)에 있습니다.

## 부하 측정 (k6)
상품 조회·주문 내역·로그인·주문 생성을 섞은 부하를 단계적으로 올려 160 TPS까지 오류 0%(p95 76ms 이내)를 확인했고, 320 TPS에서 한계에 닿았습니다. 원인은 DB가 아니라 로그인 비밀번호 검증(BCrypt)의 앱 CPU 포화였습니다(앱 2 CPU·2GiB, 단계별 120초 2회 측정).
설계·결과·원자료는 [`docs/load-test.md`](docs/load-test.md)에 있습니다.

## 원본 (2024)

### 주요 기능

**사용자**

| 도메인 | 기능 |
|---|---|
| 건강 기록 | 하루 물 섭취량·수면 시간 입력, 목표 달성률 시각화 |
| 상품 | 건강 상품 목록·상세 조회 |
| 장바구니 | 담기·수량 변경·삭제 |
| 주문 | 배송 정보 입력, 주문 확인, 주문 내역, 주문 취소 |
| 리뷰 | 상품 후기 작성·수정·삭제 |
| 게시판 | 건강 정보 글쓰기·조회 |
| 회원 | 가입·로그인·정보 수정·탈퇴 |

**관리자**

상품 등록·수정·삭제, 추천 콘텐츠 등록·수정·삭제 관리

### 설계 판단

**요청 흐름을 프레임워크 없이 직접 연결했다** — Spring 없이 JSP와 Servlet만으로 요청을 받아 DB에 닿고 화면으로 돌아가는 경로를 화면마다 연결했습니다.
게시판은 `mvc/model/BoardDAO`·`BoardDTO`로, 상품 조회 일부는 `dao/ProductRepository`·`dto/Product`로 분리해 화면이 값만 받게 했습니다.

**도메인별로 경로를 나눴다** — `member` · `product` · `cart` · `order` · `review` · `board` · `content` · `user`를 각각의 디렉토리로 분리해,
기능이 하나 늘어도 다른 도메인 화면을 건드리지 않게 했습니다.

**요청 처리 시간을 로그로 남겼다** — 서블릿 필터(`filter/LogFilter`, `LogFileFilter`)에서 모든 요청의 접속 IP·URL·처리 소요 시간(ms)을 남겨,
느린 화면을 감이 아니라 로그로 찾게 했습니다.

## 기술 스택

| 영역 | 원본 (2024) | 리메이크 (2026) |
|---|---|---|
| 언어·프레임워크 | Java, JSP, Servlet, Servlet Filter | Java 17, Spring Boot 4.1 |
| 데이터 접근 | JDBC, DAO/DTO | MyBatis(XML 매퍼), HikariCP, Flyway |
| DB | MySQL 8.0 | Oracle Database Free |
| 서버·실행 | Apache Tomcat 10.1 | Docker Compose |
| 테스트·CI | - | JUnit, Testcontainers, GitHub Actions |

## 프로젝트 구조

```text
BuildYourHealth/   2024 원본 (JSP/Servlet + MySQL)
asis-runtime/      원본을 수정 없이 띄우는 Docker 실행 환경
remake/            2026 리메이크 (Spring Boot + MyBatis + Oracle), 측정 도구 perf/
docs/si/           현행분석서 · 요구사항정의서 · 테스트 시나리오
docs/db-tuning.md  인덱스·실행계획 측정 설계와 결과
docs/load-test.md  k6 부하·처리 한계 측정 설계와 결과
```

## 실행

리메이크 (Docker 필요):

```bash
docker compose -f remake/compose.yaml up -d --build
curl "http://localhost:8081/api/products?page=1&size=5"
```

테스트는 `cd remake && ./mvnw verify`로 실행합니다. 자세한 API와 데모 계정은 [`remake/README.md`](remake/README.md)에 있습니다.

원본 (Docker 필요):

```bash
docker compose -f asis-runtime/compose.yaml up -d --build
```

<http://localhost:8080/BuildYourHealth/user/welcome.jsp>로 접속합니다. 실행 환경 구성은 [`asis-runtime/README.md`](asis-runtime/README.md)에 있습니다.

## 만든 사람

**Jigwan Joe** — Backend

- GitHub: [@jgjoe](https://github.com/jgjoe)
- Email: jigwan.joe@gmail.com
