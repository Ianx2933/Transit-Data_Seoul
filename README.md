# Seoul Transit — 버스 OD 분석 및 혼잡도 예측 시스템

> 교통카드 OD 데이터를 기반으로 수집·전처리·분석·예측·서빙까지 1인으로 설계 및 운영하는 데이터 파이프라인
> **궁극 목표**: OD 분석 → **트립체인(Trip Chain) 분석**으로 확장하여 이용자 이동 패턴 기반 노선 개편 및 서비스 개인화

[![Live Demo](https://img.shields.io/badge/Live-Demo-brightgreen)](https://ianx2933.github.io/Transit-Data_Seoul/03_visualization/congestion_map.html)
[![Stack](https://img.shields.io/badge/Stack-Spring%20Boot%20%7C%20PostgreSQL%20%7C%20Redis%20%7C%20Airflow%20%7C%20Docker-blue)]()

---

## 한눈에 보기

| 지표 | 값 |
|---|---|
| **API 응답속도 개선** | 10초 → **0.2초 (98% ↓)** — Redis TTL 차등 캐싱 |
| **운영 중 데이터 규모** | 일별 OD **약 2,000만 행** + 시간별 OD **1,424만 행** |
| **예측 모델 성능** | 9개 광역버스 노선 평균 **R² 0.91** (XGBoost) |
| **마이그레이션** | SQL Server → PostgreSQL **당일 완료, 무결성 100%** |
| **자동화** | Airflow DAG **5개** · Docker **8개 서비스** 단일 명령 부팅 |
| **확장 설계 규모** | 데이터 안심구역에서 확인한 전국 raw 트랜잭션 **일 2TB** 가정 |

---

## 시스템 아키텍처

```
[공공 데이터 소스]                [오케스트레이션]                    [저장소]
서울 열린데이터광장 OA-12912 ──┐
서울 열린데이터광장 OA-12913 ──┼─→ Airflow DAG 5개 ────────→ PostgreSQL 16 + PostGIS 3.4
국토부 노드정보 CSV ──────────┘    · daily_od (매일 02:00 KST)     · daily_od_data (~2,000만)
                                   · hourly_od (매월 1일 03:00)    · hourly_od_data (~1,424만)
                                   · preprocessing_dag (4 Task)    · analysis_table_final (602만)
                                   · Blue-Green 멱등성              · holiday_config / bus_stop_location
                                                                              ↓
[서빙 레이어]
Spring Boot 4.0 REST API ←── Redis 7.2 (TTL 차등 캐싱)
        │                       · congestion 24h
        │                       · stops 24h
        │                       · arsStandardCode 7d
        ↓
Flask 마이크로서비스 (XGBoost 예측 모델, R² 0.91)
        │
        ↓
클라이언트 / Folium 인터랙티브 시각화 (GitHub Pages)
```

전체 스택은 `docker-compose up -d` 한 줄로 부팅합니다.

---

## 기술 스택

| 영역 | 기술 |
|---|---|
| **Backend** | Spring Boot 4.0, Java 19, JdbcTemplate, Swagger UI |
| **Database** | PostgreSQL 16 + PostGIS 3.4 |
| **Caching** | Redis 7.2 (`@Cacheable`, TTL 차등 전략) |
| **Data Engineering** | Apache Airflow 2.9.0, Python 3.11 |
| **ML / Prediction** | XGBoost, Flask 마이크로서비스 |
| **Infrastructure** | Docker, docker-compose (9 서비스), Spark (분석 레이어 도입) |
| **Visualization** | Python Folium, GitHub Pages |

---

# 기술 선택

## 1. SQL Server → PostgreSQL + PostGIS 마이그레이션

### 왜 이것을 선택하였는가?

처음에는 SQL Server 17 Express로 시작했습니다. 윈도우 환경에서 가장 빠르게 셋업할 수 있고, T-SQL로 복잡한 쿼리를 작성하기 익숙했기 때문입니다. 그러나 데이터 규모가 약 50만 행을 넘길 때부터 실행 시간이 과도하게 오래 걸리는 한계가 발생하였습니다.

- **Express 에디션의 메모리 한계** — 무제한 설정으로 두자 RAM이 전부 소진되며 인덱스가 빈번히 손실되는 문제 발생
- **Windows OS 의존성** — 운영 환경 이식이 어렵고 클라우드 비용 부담
- **GIS 확장의 까다로움** — Spatial 기능이 있긴 하지만 PostGIS만큼 풍부하지 않고, 정류장 위경도 매핑 작업에 부적합
- **Docker 이미지 용량과 라이선스** — 개발/운영 환경 통일이 어려움

이 시점에 **MySQL**과 **PostgreSQL** 두 가지를 비교 검토했습니다.

| 기준 | MySQL | PostgreSQL |
|---|---|---|
| **공간 분석** | MySQL Spatial (제한적) | **PostGIS — 업계 표준, 풍부한 함수** |
| **CTE / 윈도우 함수** | 8.0부터 지원, 안정성 보통 | **초기부터 지원, 안정적** |
| **Docker 이미지** | 친화적 | **친화적, alpine 경량 이미지 존재** |
| **JSON 처리** | JSON 타입 | **JSONB 타입 (인덱싱 가능)** |
| **운영비** | 오픈소스 | **오픈소스** |
| **타입 엄격성** | 비교적 느슨 | **엄격 — 데이터 무결성 강제** |

결정적인 차이는 **"공간 분석을 DB 레이어에서 통합 처리할 수 있는가"** 였습니다. 정류장 위경도 매핑, 행정동 경계 분석, 향후 도시 규모 공간 쿼리를 모두 PostGIS의 `ST_Within`, `ST_Distance` 같은 함수로 한 번에 처리할 수 있다는 점이 결정적이었습니다. MySQL은 같은 작업을 애플리케이션 레이어에서 별도로 처리해야 했습니다.

또한 CTE와 윈도우 함수 안정성이 PostgreSQL이 더 높았는데, 이 프로젝트의 핵심 쿼리(재차량 누적 계산)가 정확히 이 기능에 의존하고 있었습니다.

### 어떻게 해결책을 찾았는가?

마이그레이션 자체는 단순한 도구 교체에서 끝나는 것이 아닌 **데이터 정합성을 유지하며 옮기는 것**이 핵심이었습니다.

**1단계: 데이터 추출 전략 결정**
- pgloader, AWS DMS, 수동 dump 세 가지를 검토
- pgloader는 SQL Server 호환성이 불완전하였고, AWS DMS는 클라우드 전제라 부적합하다고 판단
- 따라서 **Python(pyodbc) + pandas로 UTF-8 CSV 추출 → psql `\COPY`로 적재** 방식 채택. 가장 단순하게 할 수 있으며 디버깅이 가능하다는 점에서 채택

**2단계: 인코딩 함정 해결**
- 첫 시도에서 한글 컬럼명이 깨졌음 (`\copy: parse error at "뼱"`)
- 원인 추적: pgAdmin이 내부적으로 호출하는 psql 경로 인코딩 문제
- 해결: pgAdmin 우회하고 psql 직접 실행 + `ENCODING 'UTF8' NULL 'NULL'` 명시

**3단계: 문법 전면 수정**

마이그레이션 후 Spring Boot가 즉시 작동하지 않았습니다. SQL Server 전용 문법이 코드 곳곳에 박혀 있었기 때문입니다.

```diff
- DELETE a FROM table_a a JOIN table_b b ON a.id = b.id
+ DELETE FROM table_a USING table_b WHERE table_a.id = table_b.id

- UPDATE a SET a.col = b.col FROM table_a a JOIN table_b b ON ...
+ UPDATE table_a SET col = table_b.col FROM table_b WHERE ...

- CREATE TABLE #temp (...)
+ CREATE TEMP TABLE temp (...)

- ISNULL(col, 0) / LEN(str)
+ COALESCE(col, 0) / LENGTH(str)
```

이 작업을 grep으로 일괄 검색해 한 번에 변경하지 않고, **하나씩 수정하면서 단위 테스트로 검증**했습니다. 이유는 같은 함수명이라도 동작이 미묘하게 다르게 나타나는 경우가 존재하여, 한 번에 바꾸었을 때 어디에서 깨져서 오류가 발생하였는지 추적이 불가능하기 때문입니다.

**4단계: Entity 타입 정비**
- PostgreSQL에서 `INTEGER`로 생성된 컬럼이 Java Entity에서는 `String`이라 `column "..." is of type integer but expression is of type text` 에러 발생
- 승차/하차 순번 `String → Integer`, 전환_노선ID `String → Long`으로 수정
- 이 과정에서 **DB 스키마와 도메인 객체 타입을 의도적으로 일치시키는 원칙**을 세워서 이후에도 적용

### 결과

- **당일 마이그레이션 완료**, 602만 행 무결성 100% 유지
- API 응답속도 평균 3초로 줄이며 개선 (인덱스 안정성 + 쿼리 플래너 차이)
- 이후 PostGIS 기반 공간 분석 작업이 단순화됨

---

## 2. NULL 데이터 99% 복원 — 도메인 기반 조인 전략

### 왜 해당 데이터를 단순하게 삭제하지 않았는가?

마이그레이션 직후 NULL이 발견됐을 때 가장 쉬운 선택은 `WHERE col IS NOT NULL` 로 걸러내는 것이었습니다. 그러나 이 방식은 두 가지 문제가 있었습니다:

1. **데이터 손실량이 큼** — 노선ID NULL만 108만 건. 전체의 약 7%를 버리면 분석 결과 자체가 편향될 가능성 높아짐
2. **도메인적으로 복원할 수 있음을 확인** — 정류장 ID와 정류장명은 살아있는 상태였기 때문에, 외부 매핑 테이블이나 다른 날짜 데이터에서 정답을 가져올 수 있었음

### 어떻게 해결책을 찾았는가?

각 NULL 컬럼마다 결측 패턴을 먼저 분석하고, 그에 맞는 복원 전략을 따로 세웠습니다.

| 컬럼 | NULL 건수 | 복원 전략 | 결과 |
|---|---|---|---|
| 노선ID | 1,080,000 | 노선 매핑 테이블 조인 (외부 CSV) | **100% 복원** |
| 표준코드 | 35,617 | CSV 매핑 + 타날짜 데이터 조인 | **99.99% 복원** (3건 잔여) |
| 노선명 | 13,718 | 정류장 경로 패턴 분석 알고리즘 | **100% 복원** |

**가장 어려웠던 케이스: 표준코드 복원**

표준코드는 9자리 국토부 표준 식별자인데, 원본 데이터에 약 35,617건이 NULL이었습니다. 가장 단순한 접근은 ARS(5자리)를 이용한 조인이었지만, **서울/경기/인천에 서로 겹치는 ARS 코드가 존재**하여 단순한 조인은 잘못된 매핑을 생성했습니다.

```SQL
-- 첫 시도: 단순 ARS 조인 → 중복 매핑 발생
UPDATE a SET a.표준코드 = b.표준코드
FROM table a JOIN table b ON a.ars = b.ars
-- 결과: 한 ARS에 여러 표준코드가 매핑되는 오류 발생
```

해결 과정:

```SQL
-- 2단계 시도: ARS + 정류장명 복합 조인 + 좌표 범위 필터
UPDATE a SET a.표준코드 = b.표준코드
FROM table a JOIN bus_stop b
  ON a.ars = b.ars
  AND a.정류장명 = b.정류장명
  AND b.위도 BETWEEN 37.0 AND 38.5
  AND b.경도 BETWEEN 126.0 AND 128.0
```

**핵심 통찰**: 식별자 하나로 부족하면 식별자 + 도메인 속성(이름, 좌표 범위)를 결합해서 유일성을 만든다. 데이터베이스 이론에서 배운 후보 키 개념을 직접 사용하였습니다.

**N+1 성능 문제**

복원 로직을 처음 작성할 때, NULL 행마다 API를 호출해 매핑을 가져오는 구조였습니다. 35,617건이면 35,617번의 DB 왕복. 처리 시간이 30분을 넘겼습니다.

해결: **건당 처리를 배치 조인 UPDATE로 전환**. 한 번의 SQL로 일괄 처리해 약 57만 건을 단일 트랜잭션에서 처리. 처리 시간이 분 단위에서 초 단위로 단축됐습니다.

### 무엇을 배웠는가

- **데이터 품질은 알고리즘 이전의 문제** — 모델을 더 좋게 만들기 전에 입력 데이터부터 정제해야 함
- **조인 전략에 도메인 지식을 결합** — 단순 식별자 조인이 안 되면 도메인 속성을 추가
- **건당 처리는 효율적으로 해야 한다** — 대용량 처리는 배치 SQL이 답

---

## 3. JdbcTemplate vs JPA — 왜 JPA를 선택하지 않았는가?

### 검토한 대안들

Spring Boot에서 데이터 접근 방식은 크게 세 가지였습니다:

| 도구 | 장점 | 이 프로젝트에서의 단점 |
|---|---|---|
| **JPA / Hibernate** | 객체 매핑 자동, 생산성 높음, CRUD 간결 | **CTE·윈도우 함수 표현 어려움, 성능 예측 어려움** |
| **MyBatis** | XML로 SQL 분리 가능, 동적 쿼리 강점 | **타입 안전성 약함, 추가 의존성, 학습 비용** |
| **JdbcTemplate** | 순수 SQL 직접 작성, Spring 표준, 성능 제어 | **매핑 코드 직접 작성 필요** |

### 왜 JdbcTemplate인가

이 프로젝트의 핵심 쿼리는 **재차량 누적 계산**입니다. 이 쿼리는 다음을 동시에 요구합니다:

1. **CTE(WITH 절)** — 여러 단계 변환 (순승객 → 정류장별 합산 → 누적합)
2. **윈도우 함수** — `SUM() OVER (PARTITION BY 노선 ORDER BY 순번 ROWS UNBOUNDED PRECEDING)`
3. **단일 커넥션 보장** — 임시 테이블이 같은 세션에서 유지되어야 함

JPQL이나 Criteria API로 이 쿼리를 표현하면 가독성이 급격히 떨어지거나, Native Query로 우회해야 합니다. Native Query를 쓸 거면 JPA의 장점인 객체 매핑이 사라지고, ORM 추상화 비용을 추가로 지출하게 됩니다. 추가적으로, Hibernate 7.x 업그레이드 후 **JPQL 호환성 문제**가 발생한 것도 결정의 한 이유였습니다. 이 시점에서 'ORM 추상화에 의존하여 버전 호환성 문제로 막히는 구조'보다는 'SQL을 직접 제어하여 안정성을 확보하는 구조'가 더 낫다고 판단했습니다.

### 결과

```SQL
WITH 순승객 AS (
    SELECT 노선명, 승차_정류장순번 AS 순번, 승차_정류장ARS AS ARS,
           SUM(승객수) AS 순승객수
    FROM analysis_table_final
    WHERE 노선명 = ? AND 기준일자 = ? AND 승차_정류장ARS != '00000'
    GROUP BY 노선명, 승차_정류장순번, 승차_정류장ARS
    UNION ALL
    SELECT 노선명, 하차_정류장순번, 하차_정류장ARS, -SUM(승객수)
    FROM analysis_table_final
    WHERE 노선명 = ? AND 기준일자 = ? AND 하차_정류장ARS != '00000'
    GROUP BY 노선명, 하차_정류장순번, 하차_정류장ARS
),
재차량계산 AS (
    SELECT 노선명, 순번, ARS,
           SUM(SUM(순승객수)) OVER (
               PARTITION BY 노선명
               ORDER BY 순번
               ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
           ) AS 재차량
    FROM 순승객
    GROUP BY 노선명, 순번, ARS
)
SELECT * FROM 재차량계산 ORDER BY 순번;
```

이 쿼리를 JdbcTemplate으로 직접 작성·관리한 결과, 실행 계획을 직접 확인하고 인덱스를 조정할 수 있었습니다.

---

## 4. Redis TTL 차등 캐싱 — 왜 균일 TTL을 쓰지 않았는가?

### 문제 인식

복잡한 혼잡도 쿼리(CTE + 윈도우 함수)가 매 요청마다 약 2천만 행을 풀스캔하면서 응답속도가 2-3분에 달하여 사용하기 어려운 상태가 되었습니다.

### 검토한 대안들

| 접근 | 장단점 |
|---|---|
| **쿼리 튜닝 (인덱스 추가)** | 효과 한계 — CTE 자체가 비싼 연산, 인덱스만으로는 1초 이내 어려움 |
| **머티리얼라이즈드 뷰** | 갱신 주기 관리 부담, 쓰기 시 락 발생 |
| **애플리케이션 메모리 캐시 (Caffeine)** | 단일 인스턴스에서만 동작, 분산 환경 확장 불가 |
| **Redis 캐싱** | **분산 환경 친화, TTL 관리 표준 기능, Spring `@Cacheable` 통합** |

Redis를 선택한 결정적 이유는 **확장성**입니다. 향후 Spring Boot 인스턴스를 여러 개 운영해도 캐시 일관성이 유지되는 구조가 필요했습니다.

### 왜 TTL을 모두 동일하게 두지 않았는가

처음에는 모든 캐시를 24시간 TTL로 설정하려 했습니다. 단순하니까요. 그러나 캐시별로 데이터의 성격이 완전히 다르다는 것을 깨달았습니다:

| 캐시 | 데이터 특성 | 적정 TTL | 근거 |
|---|---|---|---|
| `congestion` (노선별 혼잡도) | 매일 새벽 배치 후 변경 | **24시간** | 일일 갱신 주기와 일치 |
| `stops` (정류장 목록) | 변경 거의 없음, 안전 버퍼 시간 필요 | **24시간** | 정류장은 신설/폐지가 드물지만 이름 변화가 꽤 빈번하다는 점 발견 → 갱신 주기 통일 |
| `arsStandardCode` (ARS-표준코드 매핑) | 사실상 불변 | **7일** | 매번 메모리 적재가 비효율, 거의 변하지 않음 |

**이 결정의 핵심**: TTL은 '얼마나 자주 바뀌는가'를 고려하기 보다는 **'사용자한테 제공하는 데이터의 유통기한을 어디까 설정하는가'** 의 질문입니다. ARS 표준코드는 1주일 전 데이터를 보여줘도 문제없지만, 혼잡도는 날마다 달라지기 때문에 어제의 혼잡도 데이터를 보여주면 신뢰도가 떨어지게 됩니다.

### 검증

도입 후 Redis CLI로 직접 keys를 조회해 캐시 적중을 확인했습니다:

```Bash
redis-cli
> KEYS congestion::*
1) "congestion::6713_20251014"
2) "congestion::143_20251014"
> TTL congestion::6713_20251014
(integer) 86234   # 24시간 카운트다운 정상
```

**결과**: API 응답속도 10초 → 0.2초 (98% 개선). 캐시 미스 시 첫 요청은 여전히 10초이지만, 사용 패턴상 같은 노선 및 날짜를 반복 조회하는 구조라 캐시 적중률이 매우 높습니다.

### 다음 단계로 인지하고 있는 한계

- **캐시 워밍**: 앱 기동 시 자주 조회되는 노선·날짜를 미리 적재하면 첫 사용자도 0.2초 경험 가능
- **이벤트 기반 무효화**: 현재는 TTL 만료에 의존. 실시간성이 더 중요해지면 메시지 큐 기반 무효화 필요
- **캐시 스탬피드 방지**: 동시 만료 시 일시적 부하 가능성. 만료 시점에 작은 랜덤 노이즈 추가 검토

---

## 5. Java와 Python 사이 소통이 어려움 — Flask 마이크로서비스로 우회

### 문제

XGBoost 예측 모델을 Python으로 학습시킨 후, Spring Boot 백엔드에서 호출해야 했습니다. 그런데 **Java 프로세스가 Python 객체(`congestion_model.pkl`)를 직접 로드할 방법이 없습니다.**

### 검토한 대안들

| 접근 | 장점 | 치명적 단점 |
|---|---|---|
| **Jython** | JVM에서 Python 실행 | **유지보수 정체 (Python 3 미지원), 라이브러리 호환성 거의 없음** |
| **ONNX 변환** | 표준화된 모델 포맷, JVM 추론 가능 | **변환 손실, 디버깅 난이도 ↑, XGBoost는 변환 시 일부 기능 손실** |
| **GraalVM Polyglot** | 다언어 통합 실행 | 설정 복잡, 운영 환경 보장 어려움 |
| **Flask 마이크로서비스** | **단순, 모델 교체 자유, 언어 무관 REST 인터페이스** | 네트워크 호출 오버헤드 |

### 왜 Flask인가

Jython과 ONNX는 모두 **"Java 프로세스 안에서 Python 코드를 실행하려는 시도"** 였습니다. 이 접근의 본질적 한계는 **두 런타임을 한 프로세스에 묶으면 양쪽 모두의 자유도가 제한된다**는 점입니다. XGBoost를 업그레이드하면 Java 빌드도 영향을 받고, Java 라이브러리를 교체하면 Python 추론에 영향을 줄 수 있습니다.

Flask 마이크로서비스로 분리하면 해당 이점을 얻을 수 있습니다:
- **언어 독립성**: Python을 자유롭게 업그레이드 가능
- **모델 교체 용이성**: 모델 파일만 교체하고 컨테이너 재기동
- **확장성**: 예측 서비스만 별도로 스케일 가능
- **인터페이스 단순**: HTTP REST는 누구나 호출 가능

네트워크 오버헤드(localhost 호출 시 보통 1~5ms)는 모델 추론 시간(수십 ms~수백 ms)에 비해 무시할 수준이라 판단했습니다.

### 어떻게 구조화했는가

```
[Spring Boot]
    GET /api/prediction/hourly?routeNo=9401&arsNo=01267&date=20260411
    ↓
[PredictionService]
    1. holiday_config 조회 → dayType 결정 (평일/토요일/일요일/공휴일)
    2. daily_od_data 조회 → lag-7, lag-30 계산
    3. POST /predict (features) → Flask
    ↓
[Flask :5000]
    1. XGBoost 일별 예측
    2. hourly_ratio.csv에서 정류장별 시간대 비율 추출
    3. time_weight_config에서 요일타입별 가중치 적용
    4. 일별 예측 × 시간대 비율 = 시간대별 승차/하차
    ↓
[Spring Boot → 클라이언트]
    JSON 응답
```

**핵심 설계 원칙**: Spring Boot가 도메인 로직(공휴일 판단, lag feature 계산)을 책임지고, Flask는 순수 ML 추론만 담당합니다. 이렇게 책임을 분리하면 모델을 교체할 때 Spring Boot 코드는 건드릴 필요가 없습니다.

---

## 6. XGBoost 선택과 도메인 검증

### 왜 XGBoost인가

이 데이터의 특성은:
- **정형 데이터** — 요일, 월, 노선, 정류장, 과거 승객수 등 구조화된 피처
- **표본 크기 중간** — 노선당 수천-수만 행
- **데이터 해석 필요** — "왜 이 예측이 나왔는지"를 전문가에게 설명해야 할 필요성 존재

이 조건에서 후보는:

| 모델 | 적합성 |
|---|---|
| **XGBoost / LightGBM** | **정형 데이터에 압도적 성능, 피처 중요도 해석 가능** |
| **딥러닝 (MLP)** | 정형 데이터에서 GBM 대비 우위 없음, 학습 데이터 더 필요 |
| **Prophet** | 시계열 단일 변수에 강점, 다변수 피처 활용 어려움 |
| **선형 회귀** | 해석 쉽지만 비선형 패턴 포착 부족 |

XGBoost는 정형 데이터에 좋은 성능을 가진 모델이고, Tree 기반이라 피처 중요도를 직접 추출할 수 있어 **'왜 이러한 예측이 나왔는가'를 설명할 수 있다**는 점이 결정적이었습니다.

### 시계열 분리의 중요성

흔한 함정은 train/test를 무작위로 나누는 것입니다. 시계열 데이터에서 이렇게 하면 미래 정보가 과거 학습에 섞여서 **실제 운영 환경에서는 나올 수 없는 성능**이 측정됩니다.

이 프로젝트에서는 **2026-01-01을 기준으로 시계열 분리**했습니다. 2025년 데이터로 학습하고 2026년 데이터로 테스트. 이게 실제 배포 환경과 가장 비슷합니다.

### 도메인 지식과의 교차 검증

피처 중요도를 추출했더니 1위가 **"전주 동일 요일 승객수"** 였습니다.

이 결과를 그냥 받아들이지 않고 **교통 도메인 통념과 비교**했습니다. 교통공학에서 대중교통의 수요 발생은 "요일별 패턴이 강하게 반복되는 주기성"이 가장 중요한 변수로 알려져 있습니다. 모델이 도메인 지식과 동일한 결론에 도달했다는 것은 **피처 설계가 타당했다는 강력한 증거**입니다.

만약 예측 모델이 1위 피처로 "정류장 ID" 같은 무관해 보이는 변수를 골랐다면, 데이터 누설을 의심해야 했을 것입니다. 도메인 지식과의 교차 검증은 필수이며, 개발의 안전장치 역할을 합니다.

### 결과

| 노선 | MAE | R² | 비고 |
|---|---|---|---|
| 9404 | 8.64 | **0.9466** | 분당-서울 광역 |
| 9401-1 | 22.51 | 0.9250 | 분당-서울 광역 |
| 9408 | 4.06 | 0.8991 | |
| 422 | 9.74 | 0.8835 | |
| 9401 | 31.53 | 0.8707 | |
| 9409 | 3.00 | 0.8166 | |
| 8146 | 1.49 | 0.7140 | 새벽 노선 |
| 8541 | 2.29 | 0.6766 | 새벽 노선 |
| 8641 | 1.41 | 0.4613 | 새벽 노선, 데이터 부족 |
| **전체 평균** | **10.45** | **0.9070** | |

새벽 맞춤버스 노선(8xxx)의 R²이 낮은 것은 데이터 부족 때문(하루 2-3회 운행)으로, **예측 가능한 범위 안의 한계**입니다.

---

## 7. Airflow + Blue-Green 멱등성 패턴

### 왜 Airflow인가

매일 공공 API를 호출해 새 데이터를 적재하고 전처리하는 작업이 필요했습니다. 원래는 cron + 셸 스크립트를 썼으나 작업이 늘어나게 되어서 새로운 도구가 필요하게 되었습니다. 그래서 제가 생각했던 후보는 총 세가지였습니다.

| 도구 | 평가 |
|---|---|
| **APScheduler (Python 내장)** | 단일 프로세스 내에서만 동작, 모니터링 부족 |
| **Apache Airflow** | **DAG 시각화, 의존성 관리, UI 모니터링, 산업 표준** |
| **Prefect / Dagster** | 모던하지만 커뮤니티 자료 적음, 학습 비용 |

Airflow를 선택한 이유는 **'확장성과 의존성 관리의 용이'** 였습니다. 그리고 보기 쉬운 UI로 모니터링이 쉽다라는 것과 많은 회사에서 사용하고 있다라는 것도 선택의 요인 중 하나였습니다.

### Blue-Green 멱등성 패턴 — 왜 직접 설계했는가

Airflow DAG가 매일 자동 실행되는 환경에서 가장 무서운 시나리오는 **'데이터 전처리 도중 변경에 실패하여 데이터가 반쯤 변경된 상태로 남는 것"** 입니다. 이 상태에서 사용자가 API를 호출하면 일관성 없는 결과가 반환되게 됩니다.

해결 방법으로 **Blue-Green Table Swap 패턴**을 적용했습니다. 기존에 검증된 라이브러리가 따로 없어서 직접 설계했습니다:

```
1. 임시 테이블(STAGING)에서 모든 전처리를 수행
2. 모든 단계 성공 시에만 → 최종 테이블(FINAL)로 원샷 교체
3. 실패 시 → 최종 테이블은 손대지 않음
```

이 패턴의 핵심은 **'중간 상태가 외부에 노출되지 않는다'** 는 점입니다. 사용자는 항상 "이전 완료 상태" 또는 "새 완료 상태" 둘 중 하나만 봅니다. 부분적으로 변경된 상태는 절대 보지 않습니다.

이 아이디어는 데이터베이스 트랜잭션의 ACID 원칙과, 웹 배포에서 사용하는 Blue-Green 배포 전략을 결합한 것입니다. 두 분야에서 검증된 패턴을 데이터 파이프라인에 적용했습니다.

### 결과

- 4개 Task로 구성된 전처리 DAG가 매일 새벽 3시 자동 실행
- 처리 중 실패해도 최종 테이블은 항상 일관된 상태
- Airflow UI로 실행 이력·실패 원인 추적 가능

---

## 8. Docker 8개 서비스 오케스트레이션 — 왜 처음부터 컨테이너화했는가

### 왜 Docker인가

Docker를 선택하는 것에서 고민이 많았고, 'Docker까지 선택하는 것이 맞는가'라는 생각을 하였습니다. 그러나 처음부터 컨테이너화한 이유는:

1. **면접 시연이나 다른 환경에서도 동일하게 동작 가능**
2. **클라우드 이식성** — AWS ECS/EKS, GCP Cloud Run으로 옮길 때 추가 작업 거의 없음
3. **버전 격리** — Java 19, Python 3.11, PostgreSQL 16, Redis 7.2가 호스트 OS와 무관하게 정확한 버전으로 실행
4. **단일 명령 부팅** — `docker-compose up -d`로 8개 서비스가 한 번에 기동

### 서비스 구성 근거

| 서비스 | 이미지 | 역할 | 포트 |
|---|---|---|---|
| `seoulapi` | Spring Boot 빌드 | REST API 서버 | 8080 |
| `seouldb` | postgis/postgis:16-3.4 | 메인 DB + 공간 분석 | 5432 |
| `seoulredis` | redis:7.2-alpine | TTL 차등 캐시 | 6379 |
| `seoulprediction` | Flask + XGBoost | 예측 마이크로서비스 | 5000 |
| `airflowdb` | postgres:15 | Airflow 메타데이터 | - |
| `airflowinit` | apache/airflow:2.9 | 초기 마이그레이션 | - |
| `airflowwebserver` | apache/airflow:2.9 | DAG 모니터링 UI | 8081 |
| `airflowscheduler` | apache/airflow:2.9 | DAG 스케줄링 | - |

각 서비스를 분리한 원칙: **'한 컨테이너는 한 가지 책임만 가진다'**였습니다. PostgreSQL과 Redis를 같은 컨테이너에 넣으면 메모리 관리가 꼬이고, 둘 중 하나를 재기동할 때 다른 하나도 영향을 받습니다.

### 부딪혔던 함정과 해결

**컨테이너 이름 언더스코어 문제**

처음에 컨테이너 이름을 `seoul_transit_api`로 지었더니 Tomcat이 거부했습니다:

```
HTTP Status 400 Bad Request
The character [_] is never valid in a domain name
```

원인: Tomcat이 호스트명을 도메인으로 인식하는데, RFC 1035에서 도메인 이름에 언더스코어를 금지하고 있습니다. 해결: `seoulapi`처럼 언더스코어를 제거.

**Airflow secret_key 불일치**

```
Could not read served logs: Client error '403 FORBIDDEN'
```

원인: webserver와 scheduler 컨테이너가 서로 다른 secret_key를 생성. 해결: docker-compose.yml에서 모든 Airflow 서비스에 동일한 `AIRFLOW__WEBSERVER__SECRET_KEY` 환경변수 주입.

이런 트러블슈팅 경험이 컨테이너 환경의 미묘한 점을 이해하는 데 큰 자산이 됐습니다.

---

## 9. 확장성 설계 — 일 2TB를 처음부터 가정한 이유

### 데이터 안심구역에서 확인한 사실

분석 진행 중 **데이터 안심구역**에 신청해 전국 교통카드 raw 데이터를 직접 확인할 수 있었습니다. 여기서 확인한 규모는:

- **일 평균 약 1,000만 건의 트랜잭션**
- **일 약 2TB의 데이터 적재**

이는 현재 운영 중인 약 1,900만 행보다 **약 100배 큰 규모**입니다.

### 처음부터 확장 가능한 구조로 설계한 이유

대부분의 데이터 시스템이 작게 시작했다가 규모가 커질 때 처음부터 다시 짓는 비용을 치릅니다. 이 프로젝트에서는 **일 2TB 규모를 가정하고 설계**하여 미리 해당 문제가 발생할 것을 대비하였습니다.

1. **국토부 표준코드 기반 ID 체계**
   - 서울: `00XXXXX` (앞 2자리 `00` + 5자리 = 7자리)
   - 경기/인천 확장 시: 노선번호만 추가하면 동일 구조로 적재 가능
   - 특수 케이스(`M`, `MM` 접두사)도 처리 로직 포함

2. **Airflow DAG 분리 구조**
   - 수집 노드를 추가하기만 하면 병렬 확장 가능
   - daily/hourly/preprocessing이 서로 독립적

3. **Blue-Green 멱등성**
   - 대용량 처리 중 실패해도 안전성 보장

4. **Spark 분석 레이어 도입 (현재 진행 중)**
   - 분석 영역에 먼저 Spark를 적용해 검증
   - 검증 후 전처리 영역으로 Spark 확장 예정

### 의도적으로 미루는 것들

확장성 설계를 한다고 모든 것을 처음부터 하지는 않았습니다. **YAGNI 원칙**을 적용해 다음은 의도적으로 다음 단계로 미뤘습니다:

- Kafka 기반 실시간 스트리밍 → 배치 주기로 충분한 단계
- Kubernetes → 단일 서버 운영 단계
- 다중 인스턴스 오토스케일링 → 트래픽 패턴 확인 후

**"미래의 확장 가능성을 막지 않으면서, 현재 단계에서 불필요한 복잡성은 도입하지 않는다"** 가 설계 원칙입니다.

---

# 운영 · 품질 관리

이 프로젝트는 "취미 프로젝트"가 아닌 "운영 중인 시스템"입니다. 다음 운영 원칙을 의식적으로 적용했습니다:

| 영역 | 적용 방식 |
|---|---|
| **모니터링** | Airflow UI로 DAG 실행 상태 추적, `docker logs`로 컨테이너 로그 확인 |
| **테스트** | Spring Boot 단위 테스트 상시 운영 |
| **API 문서화** | Swagger UI 자동 생성 — `http://localhost:8080/swagger-ui/index.html` |
| **데이터 검증** | 전처리 결과를 서울시 정류장 CSV와 대조 검증 |
| **중복 검증** | Deduplicate 후 SQL로 수동 체크 |
| **멱등성** | Blue-Green 임시테이블 원샷 교체 패턴 |
| **보안** | `.env` gitignore + `.env.example`만 공개, API 키 환경변수 분리 |

---

# API 엔드포인트

Swagger UI: `http://localhost:8080/swagger-ui/index.html`

### 혼잡도 API

| 메서드 | 엔드포인트 | 설명 |
|---|---|---|
| GET | `/api/congestion/{노선명}?date=20231017` | 단일 노선 혼잡도 |
| GET | `/api/congestion?routes={노선명,노선명}&date=20231017` | 복수 노선 혼잡도 |
| GET | `/api/congestion/stops?route={노선명}&date=20231017` | 정류장 목록 |
| GET | `/api/congestion/section?route={노선명}&from={출발지}&to={도착지}&date=20231017` | 구간 혼잡도 |

### 예측 API

| 메서드 | 엔드포인트 | 설명 |
|---|---|---|
| GET | `/api/prediction/hourly?routeNo=9401&arsNo=01267&date=20260411` | 시간대별 예측 |
| PUT | `/api/prediction/weight?dayType=평일&hour=8&weight=2.5` | 가중치 업데이트 |

### 응답 예시

```json
GET /api/congestion/section?route=6713&from=국회의사당역&to=샛강역&date=20251014

[
  { "노선명": "6713", "순번": 46, "정류장명": "국회의사당역", "재차량": 73, "상대혼잡도": 67.6, "혼잡도등급": "혼잡" },
  { "노선명": "6713", "순번": 47, "정류장명": "여의도역", "재차량": 93, "상대혼잡도": 86.1, "혼잡도등급": "매우혼잡" },
  { "노선명": "6713", "순번": 48, "정류장명": "샛강역", "재차량": 100, "상대혼잡도": 92.6, "혼잡도등급": "매우혼잡" }
]
```

재차량이 73 → 93 → 100으로 증가하는 패턴이 구간별 혼잡도 심화를 정확히 반영합니다.

---

# 데이터 기반 인사이트

이 시스템에서 도출한 분석 결과 중 일부는 실제 정책 검토에 활용됐습니다.

### 1. 143번 종로2가 - 고속터미널 구간 — 다람쥐버스 투입 근거 확보

해당 구간이 매우혼잡(빨강) 등급으로 일관되게 나타났고, 이는 실제 시민 민원과 일치했습니다. **혼잡 구간에 출퇴근 집중배차 다람쥐버스 투입의 데이터 근거**를 제공했습니다.

### 2. 302번 노선 분리 검토

302번의 상대원-중앙시장 구간 분석 결과, **서울행 수요보다 성남 생활권 중심 수요 패턴**이 확인됐습니다. 이 구간을 경기도 시내버스로 분리 운행하고 302번을 단축하는 정책 검토 자료로 활용 가능합니다.

### 3. 핵심 구간 수요 정량화

| 노선 | 핵심 구간 | 비율 |
|---|---|---|
| 303 | 상대원 ↔ 잠실역 | **56.3%** |
| 302 | 상대원 ↔ 잠실역 | **51.2%** |
| 4425 | 은곡마을 ↔ 삼성역 | **56.3%** |

특정 구간에 전체 수요의 50% 이상이 집중된다는 사실을 데이터로 입증, 증편·노선 변경 의사결정의 근거를 제공했습니다.

---

# 시각화 결과물

### 인터랙티브 혼잡도 지도
**[Live Demo →](https://ianx2933.github.io/Transit-Data_Seoul/03_visualization/congestion_map.html)**

![노선 전체 혼잡도](docs/images/노선_전체_혼잡도.png)
![143번 혼잡도](docs/images/143.png)
![위례 정류장 혼잡도](docs/images/위례_정류장_혼잡도.png)

---

# 향후 계획

### Phase 5: 전국 확장 + Spark 도입
- 데이터 안심구역 데이터 정식 연동
- Spark 기반 일 2TB 규모 분산 처리
- 경기·인천·전국으로 단계적 확장

### LSTM 앙상블
- XGBoost(60%) + LSTM(40%) 앙상블로 시계열 패턴 보강
- 새벽 노선 제외 (데이터 부족)

### 관측성 (Observability)
- Prometheus + Grafana 도입
- Spring Boot Actuator 메트릭 수집
- 느린 쿼리 자동 알람

### 트립체인 분석 확장
- 환승 패턴 분석
- 이용자 유형별 트립체인
- 행동 기반 서비스 개인화 데이터 기반 마련

---

# 실행 방법

### 전체 스택 부팅

```bash
docker-compose up -d
```

### 접속 정보

| 서비스 | URL |
|---|---|
| Spring Boot API | http://localhost:8080/swagger-ui/index.html |
| Flask 예측 API | http://localhost:5000/health |
| Airflow UI | http://localhost:8081 (admin/admin) |
| PostgreSQL | localhost:5432 |
| Redis | localhost:6379 |

### 환경변수

```bash
# .env (gitignore 처리됨, .env.example 참고)
SPRING_DATASOURCE_URL=jdbc:postgresql://seouldb:5432/Seoul_Transit
SPRING_DATASOURCE_USERNAME=postgres
SPRING_DATASOURCE_PASSWORD=your_password
SEOUL_OPEN_DATA_API_KEY=your_api_key
```

---

# 프로젝트 구조

```
Transit-Data_Seoul/
├── docker-compose.yml              # 8 서비스 오케스트레이션
├── Dockerfile                      # Spring Boot 멀티스테이지 빌드
├── pom.xml                         # spring-boot-starter-data-redis 포함
├── .env.example                    # API 키 형식 안내
│
├── 01_preprocessing/
│   ├── dags/
│   │   ├── preprocessing_dag.py    # 전처리 DAG (4 Task + Blue-Green)
│   │   ├── daily_od_dag.py         # 일별 수집 (매일 02:00 KST)
│   │   └── hourly_od_dag.py        # 시간별 수집 (매월 1일 03:00)
│   ├── load_historical_data.py
│   ├── load_bus_stop_location.py
│   └── load_holiday_data.py
│
├── 04_analysis/
│   ├── CongestionAnalysis.ipynb    # XGBoost 학습 노트북
│   ├── congestion_model.pkl        # 학습된 XGBoost 모델
│   ├── route_code_map.pkl
│   ├── stop_code_map.pkl
│   └── hourly_ratio.csv
│
├── 05_prediction/                  # Flask 마이크로서비스
│   ├── app.py                      # Flask API
│   ├── Dockerfile
│   └── requirements.txt
│
└── src/main/java/com/example/demo/
    ├── DemoApplication.java        # @EnableCaching
    ├── RedisConfig.java            # CacheManager + TTL 차등
    ├── controller/
    │   ├── BusStopController.java
    │   ├── CongestionController.java
    │   ├── OdController.java
    │   └── PredictionController.java
    ├── dto/
    ├── entity/
    ├── repository/
    └── service/
        ├── BusStopService.java     # @Cacheable(arsStandardCode, TTL 7d)
        ├── CongestionService.java  # @Cacheable(congestion/stops, TTL 24h)
        └── PredictionService.java  # Flask 호출 + lag feature 계산
```

---

# 마치며

이 프로젝트를 통해 가장 크게 배운 것은 **'내가 왜 이 기술을 선택했으며, 문제가 발생하였을 때 문제를 어떻게 해결했는가를 설명할 수 있어야 한다'** 는 점입니다. PostgreSQL을 골랐다는 사실보다 **"왜 MySQL이 아니라 PostgreSQL인가, 그 결정의 근거는 무엇인가, 어떻게 검증했는가"** 가 더 중요합니다.

또한 1인 프로젝트이지만 **"운영 중인 시스템"** 으로 만들기 위해 모니터링·테스트·검증·멱등성·보안을 적용하였습니다. 단순히 "돌아가게" 만드는 것과 "안정적으로 운영되게" 만드는 것 사이의 간격이 얼마나 큰지를 직접 체감했습니다.

마지막으로, 데이터 안심구역에서 본 일 2TB 규모는 **"지금 잘 돌아가는 시스템이 100배 규모에서도 버틸 수 있는가"** 라는 질문을 항상 떠올리게 합니다. 이 질문이 모든 설계 결정의 기준이 됐습니다.

---

**Contact**
이해승 · road2005@naver.com · [github.com/Ianx2933](https://github.com/Ianx2933)
