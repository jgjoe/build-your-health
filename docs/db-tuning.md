# DB 인덱스·실행계획 측정 (R2)

근거 요구사항: `docs/si/02_요구사항정의서.md` RQ-F-011, RQ-N-005~007. 시나리오: `docs/si/03_테스트시나리오.md` TC-214.

이 문서의 1절(측정 설계)은 데이터 생성과 측정 전에 확정했다. 결과는 2절 이후에 측정 조건과 함께 기록하며, **목표값은 미리 정하지 않는다**(요구사항정의서 5절).

## 1. 측정 설계 (측정 전 확정, 2026-10-02)

### 1.1 대상 쿼리

애플리케이션이 실제로 실행하는 매퍼 SQL을 그대로 측정한다. 측정 도구는 SQL을 복사하지 않고 `OrderMapper.xml`에서 MyBatis로 읽어 같은 바인드 변수로 실행한다.

| ID | 매퍼 구문 | 화면 흐름 | 인덱스 없이 예상되는 문제 |
|---|---|---|---|
| Q1 | `findOrdersByUserId` (offset 0, size 10) | 주문 내역 첫 페이지 | `ORDERS` 전체 스캔 후 회원 주문 전부 정렬, 주문상품을 `ORDER_ITEMS` 전체 스캔으로 조인 |
| Q2 | `countOrdersByUserId` | 주문 내역 전체 건수 | `ORDERS` 전체 스캔 |
| Q3 | `findOrderByIdAndUserId` | 주문 상세 | `ORDERS`는 PK로 찾지만 주문상품은 `ORDER_ITEMS` 전체 스캔(FK 컬럼 인덱스 없음) |

상품 검색(`PRODUCT_NAME LIKE '%…%'`)은 앞쪽 와일드카드라 B-tree 인덱스로 개선할 수 없는 형태이고, 상품 수도 적어 측정 대상에서 뺐다.

### 1.2 데이터 분포

주문이 일부 회원에게 몰리는 분포를 일부러 만든다. 같은 쿼리라도 회원별 선택도가 크게 달라야 컬럼 순서 비교가 의미를 가진다.

| 등급 | 회원 수 | 회원당 주문 | 주문 합계 | 대표 회원(측정용) | 대표 회원 선택도 |
|---|---:|---:|---:|---|---:|
| heavy | 20 | 10,000 | 200,000 | `u000001` | 1.67% |
| mid | 2,000 | 150 | 300,000 | `u000021` | 0.025% |
| light | 20,000 | 5 | 100,000 | `u002021` | 0.0008% |
| 합계 | 22,020 | | **600,000** | | |

- 상품 2,000개(`X00001`~), 정가 5,000~100,000원, 할인가는 정가의 70~100%(10원 단위).
- 주문당 주문상품 1~4개(균등), 수량 1~3, 단가 = 상품 할인가, 주문 합계 = Σ(수량×단가). 주문상품은 약 150만 행으로 예상한다.
- 주문 시각: 2023-10-01 00:00:00 ~ 2026-10-01 00:00:00(KST) 균등, 초 단위. 배송일은 주문일 + 2~5일.
- 실제 서비스처럼 **주문 시각 순으로 적재**한다. 그래서 `ORDER_ID`는 시간 순으로 증가하고, `USER_ID` 기준으로는 행이 테이블 전체에 흩어진다.
- 시드 `20261002` 고정. 생성기는 `java.util.SplittableRandom`만 쓰므로 같은 시드는 같은 데이터를 만든다. 적재 결과는 ID에 의존하지 않는 체크섬(행 수 + 내용 해시 합)으로 비교한다(TC-214).
- Flyway 시드 행(`demo`, `P001`)은 그대로 두며, 행 수에 포함된다.

### 1.3 비교할 인덱스 (시나리오)

데이터는 한 번 적재하고 시나리오마다 인덱스만 바꾼다. `ORDERS` 인덱스 변형은 한 번에 하나만 존재한다.

| 시나리오 | 인덱스 (PK 외) | 확인하려는 것 |
|---|---|---|
| S0 | 없음 | 기준선 (R1 스키마 그대로) |
| S1 | `ORDER_ITEMS(ORDER_ID)` | FK 컬럼 인덱스만으로 Q1·Q3의 조인이 어떻게 바뀌는가 |
| S2a | S1 + `ORDERS(USER_ID)` | 단일 컬럼: 범위 접근은 되지만 회원 주문 전체를 정렬해야 한다 |
| S2b | S1 + `ORDERS(USER_ID, ORDER_DATE, ORDER_ID)` | 등치 조건 컬럼을 앞에: 정렬 없이 10건에서 멈출 수 있는가 |
| S2c | S1 + `ORDERS(ORDER_DATE, ORDER_ID, USER_ID)` | S2b와 같은 컬럼, 순서만 반대: 선택도에 따라 결과가 어떻게 갈리는가 |

S2b와 S2c가 RQ-N-007의 컬럼 순서 비교다. S2c에서 옵티마이저가 인덱스를 쓰는지 여부도 그대로 기록한다(힌트로 강제하지 않는다).

### 1.4 측정 방법

| 항목 | 조건 |
|---|---|
| DB | `gvenzl/oracle-free:23-slim-faststart`(Oracle Database Free 23, RAM 2GB·사용자 데이터 12GB 제한), 측정 전용 컨테이너 |
| 클라이언트 | Java 17 + ojdbc11, 호스트에서 JDBC로 접속, `PreparedStatement` + 바인드 변수, fetch size 100 |
| 측정 단위 | (시나리오, 쿼리, 회원 등급) 조합 1개 = 1케이스. Q1·Q2는 등급 3개, Q3는 mid 대표 회원의 최신 주문 1건 → 시나리오당 7케이스, 총 35케이스 |
| 반복 | 케이스마다 워밍업 5회(버림) 후 측정 30회. 전체 측정을 2회 실행해 회차 간 중앙값 차이를 함께 적는다 |
| 응답시간 | 클라이언트 측 `System.nanoTime()`, 실행부터 마지막 행 fetch까지. 중앙값·p95(nearest-rank)·최소·최대 |
| DB 측 지표 | `V$SQL` 측정 전후 차이: 실행당 buffer gets(논리 읽기), 실행당 DB elapsed, 실행당 처리 행 수 |
| 실행계획 | 측정한 커서 자체를 `DBMS_XPLAN.DISPLAY_CURSOR(sql_id, child, 'TYPICAL +PEEKED_BINDS')`로 수집하고, 별도로 `STATISTICS_LEVEL=ALL` 세션에서 1회 실행해 `'ALLSTATS LAST +PEEKED_BINDS'`(실제 행 수·버퍼) 수집. 두 계획의 plan hash value가 같은지 확인 |
| 커서 분리 | SQL 앞에 `/* R2 <시나리오> <쿼리> <등급> */` 주석을 붙여 케이스마다 별도 커서로 만든다. 한 등급의 바인드 피킹 결과가 다른 등급 측정에 재사용되지 않게 하기 위함이다. 케이스당 자식 커서 수도 기록한다 |
| 통계 | 적재 직후 `DBMS_STATS` 수집. 모든 컬럼 히스토그램 없음(SIZE 1), 단 `ORDERS.USER_ID`만 SIZE 254. 분포가 치우친 컬럼에 히스토그램을 두는 것은 일반적인 운영 설정이고, 옵티마이저가 등급별 선택도를 알게 된다. 인덱스 통계는 `CREATE INDEX` 시 수집된 값을 쓴다 |
| 캐시 | 웜 캐시만 측정한다(데이터가 버퍼 캐시에 들어가는 크기). 버퍼 캐시 비우기는 SYS 권한이 필요하고 콜드 측정은 범위 밖 |
| 원자료 | 실행별 측정값(raw), 케이스별 요약, 실행계획 텍스트, 환경 정보를 `remake/perf/results/<실행 ID>/`에 남긴다 |

응답시간에는 Windows 호스트 → Docker(WSL2) 포트 포워딩 왕복이 포함된다. 이 때문에 DB 측 elapsed와 buffer gets를 함께 기록하며, 하드웨어에 덜 민감한 buffer gets를 1차 비교 지표로 본다.

### 1.5 판정 방법 (결과를 읽는 규칙)

- "Full Table Scan → Index Range Scan 전환"은 실행계획의 해당 테이블 접근 연산이 `TABLE ACCESS FULL`에서 `INDEX RANGE SCAN`(또는 `INDEX UNIQUE SCAN`)으로 바뀐 경우만 센다.
- 응답시간 개선은 같은 케이스의 S0 대비 중앙값으로 비교한다. 회차 간 차이가 개선 폭보다 크면 개선이라고 쓰지 않는다.
- 나쁜 결과(인덱스를 만들었는데 쓰이지 않거나 느려진 경우)도 그대로 적는다.

## 2. 측정 환경 (2026-10-02)

| 항목 | 값 |
|---|---|
| 호스트 | Windows 11, AMD Ryzen 7 5700X3D(8코어 16스레드), RAM 31.9GB |
| Docker | Docker Desktop 29.8.1, VM 4 CPU · 메모리 약 9.7GB |
| DB 이미지 | `gvenzl/oracle-free:23-slim-faststart` (`sha256:f5ff1903…7028093`). DB가 보고하는 제품명은 `Oracle AI Database 26ai Free 23.26.3.0.0` |
| DB 설정 | `optimizer_features_enable=23.1.0`, `statistics_level=TYPICAL`, 버퍼 캐시 989,855,744 bytes, `pga_aggregate_target=512MB` |
| 클라이언트 | Java 17.0.12, Oracle JDBC 23.26.3.0.0, fetch size 100 |
| 데이터 | 회원 22,021 · 상품 2,001 · 주문 600,000 · 주문상품 1,499,546행(Flyway 시드 1행씩 포함), 시드 20261002 |
| 대표 회원 | heavy `u000001`(주문 10,000) · mid `u000021`(150) · light `u002021`(5). Q3 대상 주문 599870 |
| 반복 | 케이스당 워밍업 5회 + 측정 30회, 전체 2회차(`r2-run1` 11:58, `r2-run2` 11:59) |
| 원자료 | `remake/perf/results/r2-run1/`, `remake/perf/results/r2-run2/` (raw.csv, summary.csv, plans/, environment.txt) |

재현성 확인(TC-214): 같은 시드로 컨테이너를 지우고 다시 적재했을 때 행 수와 내용 해시 4종이 모두 같았다.

설계(1.4)와 달라진 실행 세부: `ALLSTATS LAST`는 측정한 커서를 그대로 재실행하면 행 단위 통계가 남지 않아(TYPICAL에서 파싱된 커서) 같은 SQL 텍스트를 `STATISTICS_LEVEL=ALL` 세션에서 다시 준비해 수집했다. 35케이스 모두 측정 커서와 ALLSTATS 커서의 plan hash value가 같았고, 자식 커서는 케이스마다 1개였다.

## 3. 결과

아래 응답시간은 클라이언트 측 중앙값(1회차 / 2회차, ms), DB elapsed와 buffer gets는 1회차 `V$SQL` 실행당 값이다. **buffer gets와 실행계획은 35케이스 모두 두 회차가 같았다.** 응답시간의 회차 간 차이는 최대 24%(S0 Q2 light)였다.

### 3.1 시나리오별 접근 경로 (ALLSTATS 실제 계획)

| 케이스 | S0 | S1 | S2a | S2b | S2c |
|---|---|---|---|---|---|
| Q1 heavy (ORDERS) | FULL | FULL | FULL | **FULL** | FULL |
| Q1 mid·light (ORDERS) | FULL | FULL | RANGE SCAN `IX_ORDERS_USER` | RANGE SCAN DESCENDING `IX_ORDERS_USER_DATE` | FULL |
| Q1 전 등급 (ORDER_ITEMS) | FULL | RANGE SCAN | RANGE SCAN | RANGE SCAN | RANGE SCAN |
| Q2 전 등급 (ORDERS) | FULL | FULL | RANGE SCAN | RANGE SCAN | INDEX FAST FULL SCAN `IX_ORDERS_DATE_USER` |
| Q3 (ORDER_ITEMS) | FULL | RANGE SCAN | RANGE SCAN | RANGE SCAN | RANGE SCAN |

FULL = `TABLE ACCESS FULL`, RANGE SCAN = `INDEX RANGE SCAN`. Q3의 ORDERS는 모든 시나리오에서 PK `INDEX UNIQUE SCAN`이다. Q1의 PRODUCT(2,001행, 22 buffers)는 모든 시나리오에서 전체 스캔 + 해시 조인이며 측정 대상에서 뺐다.

**전환 건수**: S0에서 7케이스에 걸쳐 `ORDERS`·`ORDER_ITEMS`의 `TABLE ACCESS FULL`이 10개 있었다. S2b에서는 그중 9개가 `INDEX RANGE SCAN`으로 바뀌었고, Q1 heavy의 ORDERS 1개가 전체 스캔으로 남았다.

### 3.2 인덱스 전후: S0 → S2b

| 케이스 | S0 중앙값 | S2b 중앙값 | S0 → S2b DB elapsed | S0 → S2b buffer gets |
|---|---:|---:|---:|---:|
| Q1 heavy | 137.6 / 132.4 | 29.9 / 24.3 | 136.4 → 29.5 | 14,363 → 8,113 |
| Q1 mid | 85.0 / 86.1 | 0.97 / 1.02 | 84.1 → 0.28 | 14,363 → 67 |
| Q1 light | 80.8 / 86.0 | 0.89 / 0.95 | 79.6 → 0.25 | 14,363 → 47 |
| Q2 heavy | 22.3 / 20.8 | 1.36 / 1.18 | 21.5 → 0.73 | 8,066 → 47 |
| Q2 mid | 21.0 / 20.4 | 0.62 / 0.67 | 20.5 → 0.03 | 8,066 → 4 |
| Q2 light | 19.5 / 24.2 | 0.66 / 0.66 | 19.0 → 0.02 | 8,066 → 3 |
| Q3 | 20.1 / 20.3 | 0.70 / 0.70 | 19.2 → 0.05 | 6,285 → 14 |

Q3은 S1(`ORDER_ITEMS(ORDER_ID)`)만으로 같은 값이 나왔다. S1은 Q1의 주문상품 조인도 해시 조인 + 전체 스캔에서 중첩 루프 + 인덱스 범위 스캔으로 바꿨다(Q1 heavy 137.6 → 26.1ms, buffer gets 14,363 → 8,113). S2a·S2b·S2c의 개선은 이 S1 위에 쌓인 값이다.

0.6~1ms 구간의 응답시간은 대부분 호스트 → Docker 왕복이다. 같은 케이스의 DB elapsed는 0.02~0.3ms다.

### 3.3 복합 인덱스 컬럼 순서: S2b `(USER_ID, ORDER_DATE, ORDER_ID)` vs S2c `(ORDER_DATE, ORDER_ID, USER_ID)`

| 케이스 | S2b 중앙값 | S2c 중앙값 | S2b buffer gets | S2c buffer gets |
|---|---:|---:|---:|---:|
| Q1 heavy | 29.9 / 24.3 | 28.5 / 26.3 | 8,113 | 8,113 |
| Q1 mid | 0.97 / 1.02 | 24.0 / 18.8 | 67 | 8,120 |
| Q1 light | 0.89 / 0.95 | 20.4 / 15.9 | 47 | 8,105 |
| Q2 heavy | 1.36 / 1.18 | 22.5 / 22.2 | 47 | 2,694 |
| Q2 mid | 0.62 / 0.67 | 21.0 / 19.1 | 4 | 2,694 |
| Q2 light | 0.66 / 0.66 | 17.8 / 15.1 | 3 | 2,694 |

같은 세 컬럼이라도 등치 조건 컬럼(`USER_ID`)이 맨 앞이면 회원 범위만 읽고, 맨 뒤면 옵티마이저가 Q1에서 인덱스를 쓰지 않았다(S1과 같은 계획). Q2에서는 S2c 인덱스를 테이블보다 작은 세그먼트로 통째로 읽는 `INDEX FAST FULL SCAN`(2,694 blocks)을 택했다.

단일 컬럼 S2a와 비교하면 Q1 mid에서 차이가 났다. S2a는 회원 주문 150건을 모두 읽고 정렬했고(`WINDOW SORT PUSHED RANK`, A-Rows 150, 207 buffers), S2b는 정렬 없이 10건에서 멈췄다(`WINDOW NOSORT STOPKEY`, A-Rows 10, 67 buffers). Q2는 인덱스가 좁은 S2a가 같거나 적었다(heavy 29 vs 47, mid 3 vs 4, light 3 vs 3).

## 4. 해석과 남은 문제

- **주문 상세(Q3)와 주문상품 조인은 FK 컬럼 인덱스 하나로 해결됐다.** Oracle이 FK 컬럼에 인덱스를 자동으로 만들지 않아 R1 스키마에서는 주문 1건을 볼 때도 주문상품 150만 행을 전부 읽었다.
- **주문 내역(Q1)은 S2b에서 가장 적게 읽었다**(mid 67 vs S2a 207, light는 47로 같음). 건수(Q2)는 더 좁은 단일 컬럼 S2a가 같거나 조금 적었다(heavy 29 vs 47, mid 3 vs 4). 컬럼 순서를 뒤집은 S2c는 Q1에서 인덱스가 없는 것과 같았다.
- **heavy 회원의 주문 내역 첫 페이지(Q1 heavy)는 어떤 인덱스로도 전체 스캔에서 벗어나지 못했다.** 옵티마이저는 히스토그램으로 이 회원의 주문을 9,734건으로 추정했고(실제 10,000), S2b가 있어도 `ORDERS`를 전체 스캔한 뒤 상위 10건을 골랐다. 실제로 필요한 행은 10건이다(A-Rows 10). Peeked Binds에는 회원 ID(`:1`)만 있고 OFFSET·FETCH 바인드(`:2`, `:3`)는 없다. 행 수 제한을 옵티마이저가 비용에 반영했는지는 이 측정만으로 확인하지 않았다.
- 응답시간 개선 폭은 회차 간 차이(최대 24%)보다 모두 컸다. 단, Q1 heavy의 S1·S2a·S2b·S2c 사이 차이(24~30ms)는 회차 간 차이 안에 있어 순위를 매기지 않는다.

후속 후보(이번 범위 밖, 측정하지 않음): Q1 heavy에 대해 행 수 제한 방식(바인드 대신 상수, `FIRST_ROWS(n)`, 키셋 페이지네이션)을 바꿨을 때의 계획 비교. 

**적용**: S2b의 두 인덱스(`IX_ORDER_ITEMS_ORDER`, `IX_ORDERS_USER_DATE`)를 앱 스키마에 Flyway `V3__order_indexes.sql`로 반영했다. 단일 컬럼 `ORDERS(USER_ID)`는 건수 조회에서 조금 덜 읽었지만(heavy 29 vs 47) 복합 인덱스 하나로 두 조회를 모두 처리할 수 있어 추가하지 않았다. 측정 도구는 기준선 재현을 위해 V2까지만 마이그레이션한다.

## 5. V3 후속 — heavy 첫 페이지 해결

위 R2 결과는 당시 SQL 그대로의 역사적 기록이다. 이후 V3 비교 설계·모든 후보 결과는
`docs/load-test-v3.md` 1절에 있다. 시드·S2b 인덱스·회원·5회 예열/30회 측정·2회차를 유지했다.

상수 OFFSET/FETCH는 heavy의 ORDERS 전체 스캔과 8,113 gets를 바꾸지 못했다.
FIRST_ROWS(10)은 ORDER_ITEMS까지 전체 스캔으로 악화했다(14,363 gets).
정렬된 주문 선택 뷰 바깥에 바인드 ROWNUM 상한을 두면 COUNT STOPKEY와
IX_ORDERS_USER_DATE 역순 범위 스캔이 생겼다. 실제 앱 첫 페이지에 이 형태를 적용했다.
공통 정렬 SQL을 공유하고 이후 번호 페이지는 기존 OFFSET/FETCH를 유지한다.

| 실제 앱 Q1 | V2 전 (2회 동일 gets) | V3 후 (2회 동일 gets) |
|---|---:|---:|
| heavy | 8,113 | **55** |
| mid | 67 | 67 |
| light | 47 | 47 |

heavy 계획 해시 2322670658 → 1212016781. 실제 ORDERS 10행만 읽고,
조인 뒤 23라인을 온전히 반환했다. Q2 gets 47/4/3, Q3 14는 그대로다.
최종 앱 매퍼의 측정/ALLSTATS 해시는 일치했다.
근거: `remake/perf/results/v3-orders-{r1,r2,final-r1,final-r2}/`.
논리 읽기 감소는 99.32%지만 이 고정 시드의 첫 페이지 SQL 범위이며 HTTP·운영 용량 수치가 아니다.

상수도 실패했으므로 "OFFSET/FETCH 바인드 미피킹이 유일한 원인"이라고 쓰지 않는다.
관측으로 확인한 것은 WINDOW SORT PUSHED RANK 형태와 명시적 ROWNUM STOPKEY 형태의
접근 경로/비용 계산 차이다. Oracle 내부 버그 여부는 확정하지 않았다.
API 계약(번호 페이지·total·size 1~100·동률 정렬·회원 격리)과 전체 라인은 Oracle 테스트로 확인했다.
깊은 페이지의 OFFSET 비용과 키셋 API 전환은 이번 첫 페이지 해결에 필요하지 않아 변경하지 않았다.
