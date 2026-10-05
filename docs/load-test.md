# 로컬 혼합 API 부하 테스트

## 1. 측정 전 확정한 설계 (2026-10-05)

실제 운영 통계는 없다. 아래 수치는 **가정**이며 관측 트래픽이나 서비스 최대 용량이 아니다.

- 고정 시드 회원 22,021명 중 일평균 활성 사용자(DAU) 1,500명(약 6.8%)을 가정한다. 개인 프로젝트의 소규모 서비스 설계 예시이며 외부 서비스 통계를 차용하지 않았다.
- 사용자당 하루 API 요청 평균 30건, 그중 40%가 피크 30분에 집중한다고 가정한다. 상품 탐색과 재방문을 포함한 평균이며 정적 파일·브라우저 렌더링·건강 기록 기능은 제외한다.
- 목표 피크 = `1,500 × 30 × 0.40 / (30 × 60) = 10 HTTP 요청/초(TPS)`; 0.5배 5, 1배 10, 2배 20 TPS를 각각 2회 측정한다.

요청 구성: 상품 목록/검색 70%, 주문 내역 15%, 로그인 10%, 주문 생성 5%. 조회:주문 생성 = 85:5 = 17:1(로그인 10% 별도). 사용자당 평균 각각 21·4.5·3·1.5건이다. 주문 쓰기 경로까지 검증하도록 배정한 테스트 구성으로, 실제 구매 전환율의 근거는 없다. 1 TPS는 HTTP 요청 1건으로 정의한다.

코드 확인: `GET /api/products`, `POST /api/auth/login`, `POST /api/orders`, `GET /api/orders?page=…&size=10`. **상품 상세 API는 구현되어 있지 않아 호출하지 않는다.** 상품 정보는 목록 응답에 포함된다. 주문 상세 API는 존재하지만 이번 사전 확정 혼합 구성에 넣지 않는다.

각 실행은 준비용 로그인 후 30초 워밍업, 10초 간격, 120초 측정이다. constant-arrival-rate의 반복 하나가 HTTP 요청 하나이며 응답이 느려져도 목표 도착률을 유지한다. 요청 종류는 전역 반복 번호 20개마다 14·3·2·1개로 고정한다. 상품 목록 60%·상품명 검색(`perf`) 10%이며 목록은 1~20페이지, heavy/mid 주문 내역은 1~3페이지, 기존 주문 5건인 light는 1페이지를 조회한다. 목표 도착 수는 600·1,200·2,400개/회. 단계마다 40 VU 사전 할당, 최대 40 VU(두 시나리오 예약 합계 80), HTTP timeout 5초. 워밍업과 준비 로그인은 지연/처리량 표에서 제외한다.

판정 기준(설계 선택): 목표 10 TPS에서 혼합 p95 < 500ms, HTTP/업무 검증 오류율 < 1%, dropped_iterations = 0. 목표 부하에서 두 회차 모두 나빠지고 앱/DB 증거로 원인이 확인될 때만 한 번에 하나씩 개선한다. 2배 결과는 여유 부하 관측이며 최대 용량 검증이 아니다.

데이터: 기존 측정 볼륨의 고정 시드 스키마를 **BYH_LOAD 측정 전용 스키마로 복제**한다. 회원 22,021 · 상품 2,001 · 주문 600,000 · 주문상품 1,499,546행으로 매 회차 시작한다. 생성 회원의 `!` 비밀번호만 측정 스키마에서 기존 demo의 BCrypt(cost 10)로 바꾼다. 세션 준비 계정 100개는 heavy 1·mid 9·light 90이며 로그인·주문 내역·생성을 이 계정들에 분산한다. heavy가 실제 회원 비중보다 많이 표집되므로 이 구성은 명시적인 테스트 조건이다. 테스트 주문은 `recipient_name='K6_LOAD_TEST'`로 표시해 실행 완료 후 자식 행부터 삭제하며, 시드 행은 수정하지 않는다. IDENTITY 값과 주문 시각은 회차마다 달라지고 콘텐츠 행 수·시드 해시로 초기 상태를 확인한다.

원본 `docs/db-tuning.md` 및 `remake/perf/results/`는 변경하지 않는다. 기존 SQL 단독 인덱스 비교와 이번 HTTP 혼합 부하를 개선 전후로 혼합하지 않는다.

## 2. 실행 환경 (실측 2026-10-06 KST)

| 항목 | 실제 실행 조건 |
|---|---|
| 호스트 | Windows 11, AMD Ryzen 7 5700X3D 8코어·16스레드, RAM 34,270,076,928 bytes(약 31.9GiB) |
| Docker | Desktop Engine 29.8.1, Compose v5.5.1, WSL2 kernel 5.15.167.4; VM 4 CPU·10,434,232,320 bytes(약 9.7GiB) |
| 앱 | Spring Boot 4.1.1·MyBatis starter 4.1.0, Java Temurin 17.0.20.1, Docker CPU quota 2·메모리 2GiB, JVM Xms512m/Xmx1024m |
| 풀/서버 | Hikari 총 커넥션 10개 관측, pool 크기·Tomcat 설정은 앱의 기본 구성 사용; 부하 측정용 MBean 등록과 JMX만 환경 변수/JVM 옵션으로 활성화 |
| DB | `gvenzl/oracle-free:23-slim-faststart`, 실제 제품 Oracle AI Database 26ai Free 23.26.3.0.0; 컨테이너 별도 CPU/메모리 quota 없음(공유 VM), Oracle `cpu_count=2` |
| DB 메모리/옵티마이저 | buffer cache 838,860,800 bytes, PGA target 536,870,912 bytes, PGA limit 2,147,483,648 bytes; `optimizer_features_enable=23.1.0`, `statistics_level=TYPICAL`, `sga_target=0` |
| DB 구조 | Flyway V3 적용; `IX_ORDERS_USER_DATE(USER_ID,ORDER_DATE,ORDER_ID)`, `IX_ORDER_ITEMS_ORDER(ORDER_ID)`; USER_ID 히스토그램 SIZE 254, 나머지 SIZE 1 |
| k6 | `grafana/k6` v2.3.0, commit e088784614, Go 1.27.1, linux/amd64; 실행 이미지 digest `sha256:e66db15b860113878fa74670e31f5e274830b7b6e42c8bff28b2f2d86a257603` 고정 |
| 통신/격리 | k6·앱·DB·JMX 수집기가 동일 로컬 Docker bridge(`byh-perf_default`)에 위치; k6→앱 컨테이너 HTTP. 호스트↔Docker 포트 포워딩은 측정 지연에 포함하지 않음 |
| 관측 | JDK JMX 수집기 1초 간격, Docker stats 약 5초 간격(명령 소요 + 3초 휴지), Oracle 실행 전후 V$SQL/V$SESSION_EVENT 차분·DBMS_XPLAN 커서 계획 |

앱 이미지는 저장소 HEAD `c53732546d40d74782ea100fbb31dbdfa3103922`의 수정하지 않은 main source로 빌드했다. 빌드 이미지 manifest ID `sha256:9d7ce99a…741e9d`, 실행 컨테이너 Image 참조 `sha256:db87ff64…715c7b`, 앱 JAR SHA256 `b295004e1e49691a1a7b0cd0a7fec1daaea0cffca7e5541619d603efe7df4dad`. Oracle digest `sha256:f5ff1903…7028093`, 수집기 JDK digest `sha256:5d6042fb…91f824`. 전체 ID·버전·호스트 조회 결과는 `remake/loadtest/results/environment/`에 있다. 테스트·빌드 컨테이너는 부하 측정과 동시에 실행하지 않았다.

응답 지연은 k6 `http_req_duration`에 해당하는 sending+waiting+receiving 시간(ms)이며 DNS/새 TCP 연결 대기는 별도다. 측정 단계 custom Trend(`measured_latency`)만 표에 사용한다. p50/p95/p99는 k6의 선형 보간 분위수이고, raw JSON을 재계산해 요약과 1e-6ms 이내 일치를 확인한다. 실제 처리량은 측정 단계에서 완료한 요청 수/120초이다. k6 기본 Counter의 rate는 준비·워밍업까지 포함하므로 표에 사용하지 않는다. 도착 구간의 경계에 요청 1개가 더 시작될 수 있다.

HTTP 오류와 응답 본문의 기본 계약(로그인 id, 목록/내역 items·total, 생성 orderId·totalPrice)을 모두 검사한다. p99는 5 TPS에서도 약 600개 혼합 표본으로 계산하지만 주문 생성의 단독 p99는 회차당 30개뿐이므로 꼬리 지연의 일반화에는 표본 한계가 있다. 데이터/풀/캐시는 웜 상태다. 실제 브라우저·TLS·WAN·장시간 soak·최대 수용량은 이 실행의 검증 범위에 포함하지 않았다.

## 3. 단계별 결과

모두 **현행 앱 코드에서 관측한 값**이다. 단위는 TPS, ms, %. 각 회차 준비 로그인 100건·30초 워밍업을 제외한 120초 측정 구간의 결과다. 처리량은 소수 둘째 자리, 지연은 소수 둘째 자리로 반올림했다.

| 단계 | 회차 | 목표 TPS | 실제 요청 수 | 실제 처리량 | p50 | p95 | p99 | HTTP/업무 오류율 | dropped |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 0.5배 | 1 | 5 | 601 | 5.01 | 4.17 | 75.34 | 93.35 | 0 / 0% | 0 |
| 0.5배 | 2 | 5 | 601 | 5.01 | 3.52 | 73.85 | 90.96 | 0 / 0% | 0 |
| 1배 | 1 | 10 | 1,201 | 10.01 | 3.24 | 74.64 | 88.42 | 0 / 0% | 0 |
| 1배 | 2 | 10 | 1,201 | 10.01 | 3.20 | 72.39 | 92.08 | 0 / 0% | 0 |
| 2배 | 1 | 20 | 2,400 | 20.00 | 2.83 | 71.56 | 86.41 | 0 / 0% | 0 |
| 2배 | 2 | 20 | 2,401 | 20.01 | 2.73 | 74.81 | 90.54 | 0 / 0% | 0 |

합계 8,405 HTTP 요청(측정 구간)이며 주문 생성은 30·30·60·60·120·120 = 420건이다. 생성 주문은 두 상품 라인·수량 1/2로 고정한다. 워밍업 주문도 따로 발생하므로 `db-after.txt`는 시드보다 행 수가 많다. 모든 회차 `db-before.txt`와 `db-clean.txt`가 22,021/2,001/600,000/1,499,546행이며, 정리 후 콘텐츠 해시 4종이 원본과 일치한다.

### 3.1 API별 p95 (ms)

| 목표 TPS | 상품 목록/검색 1회/2회 | 로그인 1회/2회 | 주문 내역 1회/2회 | 주문 생성 1회/2회 |
|---:|---:|---:|---:|---:|
| 5 | 4.81 / 4.18 | 101.62 / 100.47 | 5.87 / 4.57 | 9.43 / 8.35 |
| 10 | 3.80 / 3.82 | 93.09 / 97.07 | 3.84 / 3.92 | 9.86 / 15.77 |
| 20 | 3.25 / 3.18 | 91.03 / 96.37 | 3.29 / 3.04 | 8.86 / 9.95 |

### 3.2 앱·리소스 관측

Hikari는 회차별 측정 구간 120개, 합계 720개 1초 표본에서 total=10, active 최대 1, awaiting 최대 0이었다. 이는 순간 표본이며 1초보다 짧은 풀 대기나 모든 요청의 커넥션 획득 시간을 증명하는 자료는 아니다.

Docker stats의 측정 구간 표본별 최대값이다(회차당 22~23개). CPU 100%는 1코어 사용량이며 앱 quota는 2코어다. 표본 최대값을 지속 사용률로 해석하지 않는다.

| 목표 TPS | 앱 CPU% 최대 1회/2회 | DB CPU% 최대 1회/2회 | 앱 메모리 MiB 최대 1회/2회 | DB 메모리 MiB 최대 1회/2회 |
|---:|---:|---:|---:|---:|
| 5 | 51.02 / 72.55 | 35.50 / 13.96 | 527.6 / 535.9 | 2310.1 / 2296.8 |
| 10 | 18.20 / 29.01 | 14.46 / 14.78 | 534.9 / 538.7 | 2294.8 / 2316.3 |
| 20 | 28.60 / 28.72 | 22.02 / 14.28 | 540.1 / 542.0 | 2322.4 / 2308.1 |

k6는 전체 회차에서 측정 표본 CPU 최대 5.54%, 메모리 최대 35.12MiB였다. 부하 생성기의 dropped=0과 함께 기록한다.

### 3.3 Oracle 실행계획·SQL·대기

`plans.txt`는 실제 BYH_LOAD 커서의 `DBMS_XPLAN.DISPLAY_CURSOR(...,'TYPICAL +PEEKED_BINDS')` 출력이다. 주문 내역 커서 SQL_ID `3tbr80mcz9gad`, plan hash `598019367`: `WINDOW NOSORT STOPKEY` → `IX_ORDERS_USER_DATE`의 `INDEX RANGE SCAN DESCENDING`, 주문상품은 `IX_ORDER_ITEMS_ORDER`의 `INDEX RANGE SCAN`. 주문 건수 커서는 SQL_ID `1n4a2va1rxptz`, plan hash `210329678`, 같은 회원 인덱스의 `INDEX RANGE SCAN`. 각 회차 원문을 보존했다. 상품명 `%perf%` 검색에서는 PRODUCT 전체 스캔이 관측되지만 2,001행이고 API p95는 표 3.1 수준이었다.

DB 수치는 **준비 로그인+워밍업+본 측정 전체 실행 전후** V$SQL 차분이다. HTTP 120초의 분위수와 DB SQL 실행당 평균은 측정 단위가 다르다. 10 TPS의 두 회차:

| SQL | 실행 수 1회/2회 | buffer gets/exec 1회/2회 | DB elapsed ms/exec 1회/2회 |
|---|---:|---:|---:|
| 주문 내역 페이지 | 225 / 225 | 61.06 / 61.16 | 0.410 / 0.520 |
| 주문 건수 | 225 / 225 | 3.68 / 3.68 | 0.119 / 0.124 |
| 로그인 회원 조회 | 250 / 250 | 3.66 / 3.66 | 0.109 / 0.118 |
| 주문 헤더 INSERT | 75 / 75 | 12.96 / 12.89 | 0.296 / 0.318 |
| 주문상품 INSERT | 150 / 150 | 13.42 / 13.35 | 0.104 / 0.108 |

10 TPS 주문 헤더 INSERT의 누적 I/O wait 차분은 0 / 0.463ms, concurrency wait는 0.029 / 0.046ms, application wait는 0 / 0ms였다. 주문상품 INSERT는 각각 I/O 0 / 0.031ms, concurrency 0.029 / 0.034ms, application 0 / 0ms. 이는 전체 실행의 SQL 커서 대기 합계로, 커밋 대기까지 합친 요청 지연이 아니다.

세션 대기 이벤트는 Idle wait를 제외한 BYH_LOAD의 V$SESSION_EVENT 합계를 전후 저장했다. 5 TPS의 `log file sync`는 38회·67.025ms / 39회·67.041ms, 20 TPS는 157회·290.918ms / 158회·322.732ms였다(각 실행 전체 합계). **10 TPS 두 회차는 세션 합계 카운터가 감소해 차분 비교 불가**다. 예를 들어 1회차 log file sync 차분은 -88회/-152.329ms, 2회차는 -18회/-20.839ms였다. 감소의 구체 원인은 이 관측만으로 확정하지 않았다. 음수를 개선 또는 대기 0으로 바꾸지 않았고 `analysis.json`의 `not_comparable_counter_decrease` 표시로 병목 판정에서 제외했다. V$SQL 커서 대기는 별도 지표로 남겼다. AWR/ASH·유료 진단 도구는 사용하지 않았다.

## 4. 병목 분석

**목표 10 TPS에서 개선할 병목을 확인하지 못했다.** 두 회차 모두 사전 기준 p95 500ms·오류 1% 미만을 만족했고 dropped=0이다. Hikari 순간 표본에 대기는 없었고 주문 내역의 기존 인덱스 접근이 유지됐으며, SQL 실행당 DB elapsed와 커서 대기에서 목표 부하의 악화 근거가 나타나지 않았다. 세션 이벤트 차분의 한계 때문에 DB의 모든 대기가 없었다고 판정하지 않는다.

로그인이 상대적으로 가장 느렸다. 코드상 BCrypt cost 10 검증이 있고 회원 조회 DB elapsed는 약 0.11~0.12ms지만 HTTP 로그인 p95는 93.09~97.07ms였다. 이는 다음 분석 대상의 단서이며 CPU 프로파일러로 비용을 분해한 결과는 아니다. 목표 부하 지연 악화·오류·풀 압박이 없으므로 BCrypt 강도·풀 크기·SQL·인덱스를 변경하지 않았다. 20 TPS도 동일 기준을 만족했지만 최대 용량으로 주장하지 않는다. 낮은 부하보다 일부 지연이 작다는 사실은 실행 순서/웜 상태의 영향을 분리하지 못하므로 개선으로 해석하지 않는다.

## 5. 수정 전후·이력서 근거

| 구분 | 상태 |
|---|---|
| 앱 수정 전 | 위 6회 현행 코드 측정 |
| 앱 수정 내용 | 없음 — 목표 부하에서 근거 있는 개선 대상 미확인 |
| 앱 수정 후 재측정 | 없음 — 변경하지 않았으므로 전후 감소율/감소 문장 없음 |

기존 인덱스 실측(주문 상세 20.1→0.70ms)은 별도 SQL 단독 실험이며 이번 k6 개선값으로 사용하지 않는다. `docs/db-tuning.md` SHA256 `a8be3192cd67b1e8eb5ac3c8413f63063001f30ff3c5730fecb10902edf71278`이 작업 전후 동일하다.

이번 결과로 뒷받침되는 이력서 후보:

> DAU 1,500명의 예상 피크 10 TPS를 가정해 k6로 혼합 API 부하를 측정하고, 목표 부하에서 p95 72.39~74.64ms·오류율 0%를 확인함(로컬 Docker VM 4 CPU, 앱 2 CPU·2GiB, 주문 60만 건 초기 데이터, 30초 워밍업 후 120초씩 2회).

> 예상 피크 10 TPS의 0.5·1·2배 부하를 k6로 검증하고 HikariCP·Oracle 실행계획을 함께 분석해, 20 TPS에서 p95 71.56~74.81ms·오류율 0%를 확인함(동일 로컬 환경·고정 시드, 단계별 120초×2회).

이 두 후보는 **측정·검증 문장**이다. 이번 실행에서 개선하지 않았으므로 “개선해 p95를 A→B로 줄임” 문장은 근거가 없다.

참고: [k6 constant-arrival-rate 공식 문서](https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/constant-arrival-rate/), [API request rate 공식 문서](https://grafana.com/docs/k6/latest/testing-guides/api-load-testing/).

## 6. 재현

저장소 루트에서 실행한다. Windows 운영 스크립트는 PowerShell 7+ `pwsh`가 필요하다. `test/k6-load-test` 작업 브랜치를 사용했다. 저장소 밖 파일 생성 금지 조건 때문에 공통 Start-Task.ps1의 브랜치 모드를 사용했고 별도 worktree는 생성하지 않았다. 중앙 OMP는 외부 상태/세션 파일 기록을 요구하므로 이번 로컬 측정은 현재 세션에서 진행했다.

```powershell
pwsh -NoProfile -File remake/loadtest/Verify.ps1
docker --context desktop-linux compose -f remake/perf/compose.yaml up -d --wait --wait-timeout 600
```

첫 실행의 빈 DB에서만 기존 고정 시드 로더를 실행한다. 이 측정은 기존 `byh-perf_perf-db-data` 볼륨을 재사용했다. 이미 주문 60만 건이 있는 원본에 `load`를 다시 실행하지 않는다.

```powershell
docker --context desktop-linux run --rm --network byh-perf_default `
  -e PERF_DB_URL=jdbc:oracle:thin:@byh-perf-perfdb-1:1521/FREEPDB1 `
  -e PERF_DB_USERNAME=byh_perf -e PERF_DB_PASSWORD=byhperf123 `
  byh-loadtest-build sh mvnw -q -Pperf test-compile exec:java '-Dexec.args=load'
```

`Prepare.ps1`은 원본 행 수 검증 → Oracle Data Pump 복제 → 콘텐츠 해시 4종 비교 → 측정 스키마 테스트 비밀번호 설정/통계 수집 → 앱 빌드/Flyway V3 적용을 수행한다. 원본은 읽기만 한다. 이미 준비된 BYH_LOAD 스키마에는 검사 후 `-ReusePreparedSchema`로 재사용할 수 있다. 비밀번호를 로컬 기본값에서 바꾸었다면 `PERF_ORACLE_PASSWORD`, `LOAD_DB_PASSWORD`를 같은 환경으로 설정한다.

```powershell
pwsh -NoProfile -File remake/loadtest/Prepare.ps1
# 이미 복제된 스키마를 재사용할 때:
# pwsh -NoProfile -File remake/loadtest/Prepare.ps1 -ReusePreparedSchema

# 앱 기동이 완료되고 /api/products가 200을 반환하는 것을 확인한 뒤:
$runLabel = 'rerun-20261006'
foreach ($tps in @(5,10,20)) {
  foreach ($repeat in @(1,2)) {
    pwsh -NoProfile -File remake/loadtest/Run.ps1 -Tps $tps -Repeat $repeat -Label $runLabel
  }
}
python remake/loadtest/analyze.py --label rerun-20261006
```

결과 디렉터리가 이미 있으면 덮어쓰기를 거부하므로 새 label을 사용한다. `Run.ps1`은 Docker 컨테이너에 JS/JMX 파일을 복사해 실행하고 완료 후 출력만 호스트의 저장소 안으로 복사한다. Windows bind mount I/O 오류를 피하기 위한 실행 방식이며 k6는 로컬 Docker에서만 실행한다. 매 회차 끝에 테스트 주문만 정리하고 행 수·해시를 다시 수집한다. 로그/원자료에는 세션 쿠키를 기록하지 않는다.

원자료 구성:

- `results/baseline-{5,10,20}-r{1,2}/`: `raw.json.gz`, `summary.json`, `k6.txt`, `k6-stderr.txt`, `exit-code.txt`, `hikari.csv`, `docker-stats.txt`, `db-before.txt`, `db-after.txt`, `db-clean.txt`, `plans.txt`, `cleanup.txt`, `seed-checksums.txt`, `app.txt`.
- `results/analysis.json`: raw 분위수·실제 요청 수·HTTP/업무 오류·관측 구간 풀/CPU/메모리·SQL/대기 차분의 재계산 결과.
- `results/environment/`: 호스트·버전·이미지 ID·옵티마이저·CPU/메모리 설정·최초 seed 해시·시나리오 파일 SHA256.
- `results/verify-copy.txt`, `results/test-reports/`: 마지막 전체 테스트 실행 로그와 Surefire 보고서.

정식 6회 외 기록: 초기 Data Pump import는 기존 사용자 중복 ORA-31684를 보고했으나 5개 테이블 적재를 확인한 뒤 해시 검증으로 재사용했다. 재현 스크립트는 `exclude=USER`로 이를 방지한다. 최초 Windows bind mount 기반 테스트는 최종 출력이 불완전해 검증 근거로 사용하지 않았다. `preflight-5-r1`은 컨테이너 기동 실패, `pilot-page-mix-5-r1`은 light 회원의 빈 2·3페이지 호출 구성을 발견해 중단한 실행으로 결과 표에서 제외했다. 이들은 앱 개선 전 측정값으로도 사용하지 않는다.

종료 전 보호 상태 보조 조회 중 원본 Flyway의 version 컬럼 조회는 인용 오류(ORA-00904)로 실패해 근거에서 제외했다(`environment/protected-source-final.txt`). 앱 V3 적용 여부는 기동 로그와 실제 인덱스/계획으로 확인했다. 원본 비밀번호 sentinel 22,020개와 원본 인덱스 목록, 측정 주문 잔존 0건은 같은 파일에 별도로 관측값을 보존했다.

## 7. 파일 변경·테스트

신규 파일: `docs/load-test.md`, `remake/loadtest/{compose.yaml,mixed.js,JmxProbe.java,Prepare.ps1,Run.ps1,Verify.ps1,analyze.py,snapshot.sql,plans.sql,checksum.sql}` 및 `remake/loadtest/results/` 원자료. 앱 main/test source, README, pom.xml, 기존 DB 튜닝 문서/원자료는 변경하지 않았다. 작업 시작 시 이미 미추적이던 AGENTS.md와 PR 템플릿도 수정하지 않았다.

마지막 전체 테스트는 Windows 파일 공유를 사용하지 않는 JDK 17 Docker 이미지 내부에서 `sh mvnw -B verify`로 실행했고, 실제 Oracle Testcontainers를 포함한 54건을 모두 실행했다. 종료 코드 0, 2026-10-05 23:59:15 KST:

```text
[INFO] Tests run: 54, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  01:18 min
[INFO] Finished at: 2026-10-05T14:59:15Z
```

6회 k6 실행도 종료 코드 0이고 raw 검증(요청 구성/수·분위수 재계산·초기/정리 후 행 수·seed 해시 비교)을 통과했다. 앱 수정이 없어 추가 전후 테스트 실행은 없다. 작업을 마친 뒤 이번 앱/DB 컨테이너는 중지하고 측정 데이터 볼륨은 보존한다.

## 8. 2차: 처리 한계와 먼저 포화되는 자원

### 8.1 목적·조건·판정 방법

1차 시나리오·문서·원자료를 먼저 `2071d8c`에 로컬 커밋한 뒤 같은 `test/k6-load-test` 브랜치에서 측정했다. 운영 피크 가정은 앞 절의 10 TPS 그대로이며, 2차의 증량은 운영 수요 추정이 아니라 현재 환경의 용량 탐색이다. 앱을 재빌드하지 않았고 실행 JAR SHA256 `b295004e1e49691a1a7b0cd0a7fec1daaea0cffca7e5541619d603efe7df4dad`는 1차와 같다. 2차 앱 컨테이너가 보고한 이미지 ID는 `sha256:9d7ce99ad1bf42a30f87d48a1fe1acb041cb3ea49f803238c93520dee0741e9d`이다. CPU 쿼터 2 CPU, 메모리 한도 2GiB, JVM heap 512MiB~1GiB, Docker VM 4 CPU·약 9.7GiB, Oracle Free CPU_COUNT=2와 기존 이미지·버전을 유지했다. 환경 원문은 `remake/loadtest/results/capacity-environment/`에 있다.

`capacity.js`는 1차 `mixed.js`의 요청 선택·계정 분포·페이징·검증 로직을 복사했다. 본문 비교에서도 시작 시각 관측용 카운터 외에 차이가 없음을 확인했다. 상품 70%(검색 10%p 포함)·주문 내역 15%·로그인 10%·주문 생성 5%, 준비 로그인 100회, 고정 시드와 복제 스키마·테스트 주문 정리 방식은 같다. k6 실행 슬롯 부족이 앱 한계보다 먼저 나타나는 것을 줄이기 위해 각 시나리오의 preAllocatedVUs/maxVUs만 40→256으로 늘렸다(동시에 부하를 발생시키는 시나리오는 하나). 앱 설정 변경이나 성능 개선으로 계산하지 않는다.

- 도착률 20→40→80→160→320→… TPS. 단계마다 30초 워밍업, 10초 간격, 본 측정 120초를 2회 반복한다. 첫 실패 회차가 있어도 같은 단계의 확인 회차만 허용하고 더 높은 단계로 가지 않는다.
- 중단 기준: 본 측정 혼합 p95 **500ms 초과**, 업무 응답 계약 오류율 **1% 이상**, 또는 warmup/measured 어느 단계든 **dropped_iterations 발생**. API별 p95도 수집하지만 개별 API threshold의 종료 코드만으로 혼합 부하 중단을 판정하지 않는다.
- 실제 처리량: 첫 `measured_started` 원자료 시각부터 120초 안에 완료된 요청 수÷120초. 종료 유예 5초에 완료된 요청은 별도 집계한다. 지연 분위수·오류율은 본 측정에서 시작해 완료된 전체 요청 표본으로 계산하며 워밍업/준비 로그인은 제외한다. k6 HTTP duration(send+wait+receive), raw 선형 보간 분위수 정의는 1차와 같다.
- JMX 약 1초 간격: Hikari active/awaiting/total, 앱 누적 ProcessCpuTime, HTTP 스레드 누적 CPU, RUNNABLE HTTP 스레드 중 BCrypt 프레임이 있는 표본 수. 이 비율은 **스레드 스택 표본 비율**이며 BCrypt의 정확한 CPU 시간 비율이 아니다. HTTP 집계에는 acceptor/poller도 포함한다.
- 약 5~6초 간격: 앱·Oracle·k6 Docker CPU, 앱 cgroup `cpu.stat`(사용 CPU 시간, nr_periods/nr_throttled/throttled_usec), Oracle 현재 BYH_LOAD 세션 및 `V$SYSTEM_EVENT`/`V$SYS_TIME_MODEL`. Oracle 시스템 차분은 본 측정 안의 첫/마지막 관측 사이에만 계산하며 **FREEPDB1 전체(CON_ID=3, SYS 관측 쿼리 포함)** 범위다(BYH_LOAD 전용이 아님). 1차의 세션 교체에 따른 누적값 감소 문제를 피한다. 커서별 실행계획과 V$SQL 차분은 기존처럼 준비·워밍업을 포함하므로 별도로 표시한다.

원자료 분석기는 정상 완료 수와 summary의 일치, raw 분위수 재계산, drop 카운터, 초기/정리 후 행 수, 원본·복제 콘텐츠 해시 4종을 검증한다. drop이 있는 단계의 요청 구성은 실제 시작/완료 비율로 남기며 목표 요청 수에 억지로 맞추지 않는다. 관측 컨테이너와 k6도 같은 로컬 Docker VM을 사용한 결과다.

### 8.2 단계별 결과

측정일: 2026-10-06 KST, 본 측정 시작 01:37:50~02:07:48. 아래는 앱 수정 전 현행 코드의 10회 결과다. 목표를 충족한 마지막 계단은 **160 TPS**, 처음 실패한 계단은 **320 TPS**다. 640 TPS 이상은 실행하지 않았다. 160과 320 사이를 세분화하지 않았으므로 정확한 최대 TPS는 측정하지 않았다.

| 단계·회차 | 목표 TPS | 120초 내 완료 TPS | p50 / p95 / p99 (ms) | 오류율 | drop (warmup / measured) | 판정 |
|---|---:|---:|---|---:|---|---|
| 20·r1 | 20 | 20.01 | 3.07 / 64.99 / 70.47 | 0.000% | 0 / 0 | 통과 |
| 20·r2 | 20 | 20.01 | 2.42 / 63.90 / 70.35 | 0.000% | 0 / 0 | 통과 |
| 40·r1 | 40 | 39.99 | 2.80 / 80.23 / 105.09 | 0.000% | 0 / 0 | 통과 |
| 40·r2 | 40 | 39.99 | 2.77 / 79.42 / 103.37 | 0.000% | 0 / 0 | 통과 |
| 80·r1 | 80 | 79.99 | 2.29 / 74.80 / 97.05 | 0.000% | 0 / 0 | 통과 |
| 80·r2 | 80 | 79.99 | 2.41 / 78.26 / 106.43 | 0.000% | 0 / 0 | 통과 |
| 160·r1 | 160 | 159.99 | 2.17 / 76.20 / 110.85 | 0.000% | 0 / 0 | 통과 |
| 160·r2 | 160 | 159.99 | 2.09 / 73.52 / 111.83 | 0.000% | 0 / 0 | 통과 |
| 320·r1 | 320 | 238.36 | 549.36 / 5000.37 / 5001.22 | 9.007% | 1915 / 9567 | 중단 기준 초과 |
| 320·r2 | 320 | 231.19 | 568.73 / 5000.38 / 5001.55 | 8.977% | 2063 / 10406 | 중단 기준 초과 |

완료 TPS에는 오류로 종료된 요청도 포함한다. 정상 응답 계약을 충족하고 120초 내 완료된 TPS는 320 TPS의 r1/r2에서 **217.34 / 210.89**였다. 본 측정 전체 완료 수는 **28,833 / 27,995**, 이 중 120초 이후 유예 시간 완료는 **230 / 252건**이었다. 본 측정 오류 **2,597 / 2,513건은 모두 로그인**이고, k6 원로그에 요청 타임아웃이 남아 있다. p95·p99 약 5,000ms는 설정한 HTTP 5초 타임아웃으로 절단된 관측값이므로 실제 서버 꼬리 지연의 상한으로 해석하지 않는다. drop은 서버 오류율이 아니라 256 VU가 바쁜 상태에서 예정된 도착을 시작하지 못한 수다.

API별 p95(ms):

| 단계·회차 | 상품 | 주문 내역 | 로그인 | 주문 생성 |
|---|---:|---:|---:|---:|
| 20·r1 | 4.23 | 9.01 | 73.04 | 8.87 |
| 20·r2 | 3.33 | 3.45 | 72.03 | 7.73 |
| 40·r1 | 3.92 | 3.71 | 115.54 | 24.36 |
| 40·r2 | 3.81 | 3.79 | 114.64 | 23.26 |
| 80·r1 | 3.84 | 3.09 | 105.62 | 16.09 |
| 80·r2 | 4.96 | 3.17 | 119.42 | 23.70 |
| 160·r1 | 9.04 | 7.09 | 124.29 | 25.20 |
| 160·r2 | 9.81 | 7.31 | 128.46 | 23.72 |
| 320·r1 | 1138.13 | 1142.87 | 5002.49 | 1192.27 |
| 320·r2 | 1250.11 | 1285.80 | 5003.92 | 1314.18 |

### 8.3 한계 부근 자원·병목 판정

앱 CPU는 ProcessCpuTime 차분÷관측 벽시계 시간으로 환산한 평균 사용 코어 수다. Hikari 값은 관측 표본의 평균/최대이며, 풀 설정은 10개다. BCrypt 비율은 RUNNABLE HTTP 스택 표본에서 해당 프레임을 확인한 비율이다.

| 단계·회차 | 앱 평균 CPU(코어) | active 평균/최대 | awaiting 평균/최대 | CPU 제한 기간 비율 | BCrypt 스택 표본 비율 |
|---|---:|---|---|---:|---:|
| 160·r1 | 1.41 | 0.68 / 3 | 0.00 / 0 | 6.49% | 35.01% |
| 160·r2 | 1.38 | 0.48 / 3 | 0.00 / 0 | 6.74% | 34.74% |
| 320·r1 | 2.00 | 7.14 / 10 | 7.09 / 33 | 99.83% | 90.94% |
| 320·r2 | 2.00 | 7.00 / 10 | 7.67 / 21 | 99.72% | 90.96% |

Oracle 지표는 FREEPDB1 시스템 범위의 본 측정 안쪽 관측 차분이며 SYS 수집 쿼리도 포함한다. log file sync는 여러 세션에서 누적한 대기 합계다.

| 단계·회차 | DB 관측 구간(s) | DB CPU 평균(코어) | log file sync 횟수 / 누적(ms) | JMX 표본 수 / 평균 간격(s) |
|---|---:|---:|---|---|
| 160·r1 | 115.65 | 0.198 | 938 / 4549.69 | 118 / 1.02 |
| 160·r2 | 114.19 | 0.188 | 926 / 3292.35 | 118 / 1.02 |
| 320·r1 | 105.35 | 0.263 | 1234 / 6265.70 | 22 / 5.42 |
| 320·r2 | 108.20 | 0.278 | 1237 / 5510.56 | 24 / 5.00 |

**주된 포화 자원은 앱의 2 CPU 쿼터이며, BCrypt 검증이 주요 CPU 소비 위치로 판정된다.** 160 TPS에서 1.38~1.41코어·풀 대기 0이던 상태가 320 TPS에서 2.00코어·제한 기간 99.72~99.83%로 바뀌었다. 320 TPS의 앱 전체 CPU 중 HTTP 스레드 누적 CPU가 약 98%이고(1.96코어), RUNNABLE HTTP 스택 표본의 약 91%가 BCrypt 프레임이었다. 복제 회원 22,021명의 저장 해시 prefix `$2a$10$`를 직접 조회했으며, `AuthController.login`은 회원 조회 뒤 BCrypt 검증을 수행한다.

Hikari active 최대 10, awaiting 최대 33/21도 함께 관측했다. 따라서 풀 압박이 없다고 보고하지 않는다. 다만 CPU 제한·BCrypt 실행 스레드 증가·로그인 타임아웃이 지배적이고, Oracle 평균 DB CPU는 0.263/0.278코어로 CPU_COUNT=2보다 낮았다. 로그인 회원 조회 SQL의 실행당 DB elapsed는 0.06/0.07ms·buffer gets 약 3.66, 주문 내역 페이지는 0.46/0.51ms·약 77.7 gets였다(준비·워밍업 포함 V$SQL 차분). 내역 plan hash `598019367`, 건수 조회 `210329678`이며 기존 두 인덱스의 범위 스캔이 유지된다. 320 TPS의 DB CPU resource-manager 대기 합계는 r1 2.281ms(1회), r2 추가 없음이었다. CPU 포화에 따른 앱 스레드 실행/커넥션 반환 지연이 풀 대기를 동반한 것으로 해석한다. CPU와 풀 포화의 밀리초 단위 선후나 단독 기여도를 분리한 실험은 하지 않았다.

부하 발생기 CPU나 메모리가 먼저 포화됐다는 근거는 없다. 320 TPS에서 Docker 표본의 k6 평균 CPU는 25.24/26.33%(1코어=100%), Oracle 컨테이너 평균은 31.93/35.21%, 앱 메모리 최대는 683.8/689.9MiB였다. 앱 Docker CPU%는 약 210%로 표시됐으므로 쿼터 사용 판정은 누적 CPU 시간과 제한 카운터를 우선했다. 전체 VM CPU 사용률을 직접 수집한 것은 아니며, 256 VU 한도를 바꾼 독립 실험도 수행하지 않았다.

**관측 한계:** 포화 시 JMX 응답이 지연되어 목표 1초 주기에서 실제 평균 5.42/5.00초, 표본 22/24개로 줄었다. 초기 분석기는 100개 표본을 가정해 첫 실패 단계 분석에서 멈췄다. 측정값을 변경하지 않고 실제 표본 수·간격을 보존하는 검증으로 정정해 원자료를 다시 분석했으며, 같은 320 TPS 확인 회차까지 실행했다. Hikari/스택 비율은 이 성긴 표본의 관측값이고 정확한 CPU 시간 분해나 미세 시간 순서 증명은 아니다. 시스템 대기 원문·cgroup·k6 오류/지연이 같은 결론을 지지한다. 관측 도구가 없는 환경의 최대 용량으로 일반화하지 않는다.

### 8.4 개선 여부·전후·이력서 후보

앱 수정 **없음**. 보안 강도를 유지한 채 주된 BCrypt CPU 비용을 줄일 수 있는 수정 근거를 이번 자료에서 확인하지 못했다. 풀 증설이나 인덱스 추가를 전체 용량 개선으로 연결할 근거도 부족하다. BCrypt cost 하향·검증 결과 캐시·검증 생략은 적용하지 않았다. 앱 CPU/메모리 증설도 하지 않았으며 개선값으로 주장하지 않는다.

| 구분 | 결과 |
|---|---|
| 수정 전 | 160 TPS 2회 통과, 320 TPS 2회 중단 기준 초과 |
| 수정 내용 | 없음 |
| 수정 후 계단 측정 | 미실행 — 앱 변경이 없어 전후 감소 수치 없음 |

이력서 후보(측정/분석 결과만 사용):

> 예상 피크 10 TPS를 가정해 k6 혼합 API 계단 부하를 측정하고, 160 TPS에서 p95 73.52~76.20ms·오류율 0%를 검증함(로컬 Docker VM 4 CPU, 앱 2 CPU·2GiB, 주문 60만 건 초기 데이터, 단계별 30초 워밍업 후 120초×2회).

> k6 혼합 부하의 160 TPS 통과·320 TPS 실패 구간을 확인하고, CPU 제한 카운터·BCrypt 스택 표본·Oracle 대기를 비교해 앱 CPU 포화를 주요 병목으로 진단함(동일 로컬 환경·고정 시드, 로그인 10%, BCrypt cost 10, 단계별 120초×2회).

앱 개선을 수행하지 않았으므로 “개선해 p95를 A→B로 줄임” 후보는 제안하지 않는다. 기존 주문 상세 인덱스 개선값을 이 2차 k6의 개선값으로 합치지 않는다.

### 8.5 재현·파일·검증

기존 준비된 고정 시드/복제 스키마와 앱 이미지를 재사용할 때 저장소 루트에서 실행한다. 빈 DB/최초 준비는 6절을 따른다. Oracle가 준비되기 전에 앱을 기동하면 listener 준비 오류로 앱이 종료될 수 있으므로 DB healthy를 기다린다.

```powershell
docker --context desktop-linux compose -f remake/perf/compose.yaml up -d --wait --wait-timeout 600
docker --context desktop-linux compose -f remake/loadtest/compose.yaml up -d
# /api/products가 200을 반환한 후, 사용하지 않은 새 label로 실행:
pwsh -NoProfile -File remake/loadtest/Staircase.ps1 -Label capacity-rerun
python remake/loadtest/analyze_capacity.py --label capacity-rerun
```

`Staircase.ps1`은 2회차 결과까지 확인한 후 중단 기준이 있으면 다음 증량을 실행하지 않는다. 이미 완료된 label은 재사용하되 불완전한 결과가 있으면 중단한다. `Run-Capacity.ps1`은 본 측정 최소 120초, 고정 시드 시작 검사와 테스트 주문만 정리하는 방식을 유지한다. k6 threshold 초과 종료 코드 99는 실패 단계의 관측 결과로 보존한다. 이번 20~160 TPS 8회는 코드 0, 320 TPS 2회는 코드 99였다.

변경 파일은 이 문서, 새 도구 `remake/loadtest/{capacity.js,CapacityProbe.java,Run-Capacity.ps1,Staircase.ps1,capacity-probe.sql,analyze_capacity.py}`, `results/capacity-before-*-r*/`, `results/analysis-capacity-before.json`, `results/capacity-environment/`다. 기존 1차 시나리오/원자료와 앱 main/test, 기존 인덱스 실측 문서는 수정하지 않았다.

10회 원자료 분석 검증을 통과했으며 매 회차 시작/정리 후 회원 22,021·상품 2,001·주문 600,000·주문상품 1,499,546행과 원본/복제 콘텐츠 해시 4종이 같았다. 앱 수정이 없어 전체 테스트 재실행 조건은 발생하지 않았다. 마지막 전체 테스트 결과는 7절의 Oracle 통합 포함 54건(실패·오류·건너뜀 0, BUILD SUCCESS, 2026-10-05T14:59:15Z)과 원출력을 그대로 보존했다. 기존 `docs/db-tuning.md` SHA256도 1차의 값과 동일하다.
