# 서울특별시 시내버스 OD(Origin-Destination) 데이터 분석 파이프라인

> 서울 버스 승하차(OD) 데이터를 활용한 정류장별 혼잡도 계산 및 인터랙티브 시각화 프로젝트  
> 궁극적 목표: OD 분석 → **트립체인(Trip Chain) 분석 및 가공**으로 확장하여 이용자의 이동 패턴을 파악하고 이동 효율화의 데이터 근거 마련

[![GitHub Pages](https://img.shields.io/badge/Live-Demo-brightgreen)](https://ianx2933.github.io/Transit-Data_Seoul/03_visualization/congestion_map.html)

---

## 프로젝트 개요

서울시 버스 OD 원시 데이터를 전처리하고, 정류장 단위 재차량 및 혼잡도를 계산하여 인터랙티브 지도로 시각화하는 엔드 투 엔드 데이터 파이프라인을 구축했습니다.

- **데이터**: 서울시 버스 OD 데이터 (약 157만건)
- **분석 노선**: 143, 302, 303, 333, 343, 345, 422, 440, 4318, 9401, 9404, 9408 등
- **분석 기간**: 20231017, 20241019, 20251014 (날짜별 시계열 비교)
- **최종 목표**: 버스 OD 분석 → 트립체인 분석 → 이동 패턴 기반 서비스 개인화

---

## 트립체인 분석으로의 확장

이 프로젝트는 단순 혼잡도 시각화를 넘어, 트립체인 분석의 기초 단계입니다.

```
버스 출발지-도착지 수요 파악 및 분석
    ↓
환승 구간 및 시간대별 이동 패턴 축적
    ↓
트립체인 분석 (개인의 하루 이동 흐름 전체)
    ↓
이동 패턴 기반 노선 개편 및 서비스 개인화
```

트립체인 분석에서 이동 패턴 분석으로 생활을 분석하고 효율적인 서비스를 제공하려고 하는 것은 앱 로그 분석에서 행동 패턴으로 서비스를 개인화하는 것과 본질적으로 같은 방법론입니다.

---

## 기술 스택

| 구분 | 기술 |
|------|------|
| 언어 | Java 19, Python 3.x, SQL |
| 프레임워크 | Spring Boot 4.0.4, JPA/Hibernate 7 |
| DB | PostgreSQL 18 + PostGIS 3.6 |
| ORM | JdbcTemplate (CTE + 윈도우 함수) |
| 인프라 | Docker, Docker Compose |
| 파이프라인 | Apache Airflow 2.9.0 |
| 시각화 | Python Folium |
| 데이터 처리 | Pandas |
| 배포 | GitHub Pages |
| 버전관리 | Git / GitHub |
| 개발도구 | VS Code, pgAdmin, Maven, IntelliJ |

---

## 파이프라인 구조

```
원시 OD 데이터 (CSV)
        ↓
PostgreSQL 18 DB 적재
        ↓
데이터 전처리 (Spring Boot REST API + Airflow DAG 자동화)
- 가상 정류장 승하차 처리 (ARS=00000)
- 승하차 순번 불일치 보정
- 표준코드 NULL 채우기 (타날짜 조인)
- 중복 합산 처리
        ↓
Spring Boot REST API
- 재차량 계산 (CTE + 윈도우 함수)
- 19시간 기준 시간당 평균 보정
- 혼잡도 등급 산정 (5단계)
- 위경도 조인 (ARS + 정류장명 복합)
- 출발-도착 구간 혼잡도 조회 API
        ↓
Python Folium 시각화
- 날짜별 레이어 (2023 / 2024 / 2025 시계열 비교)
- 3개 레이어 (노선별 / 통합 / 밀집도)
- OD 팝업 (승차목적지 / 하차출발지)
- CSV 캐싱으로 성능 최적화
        ↓
GitHub Pages 배포
인터랙티브 혼잡도 지도
```

---

## 재차량 계산 알고리즘

재차량은 버스 내 실제 탑승 인원을 의미하며, 다음 누적 공식으로 계산합니다.

**재차량(n번 정류장) = 재차량(n-1번) + 승차(n번) - 하차(n번)**

이 계산은 노선 전체 순번이 정확해야 하므로, 전처리 파이프라인이 이 계산의 정확성을 보장합니다.

```sql
WITH 순승객 AS (
    SELECT 노선명, CAST(승차_정류장순번 AS INT) AS 순번,
        승차_정류장ARS AS ARS, 승차_정류장명 AS 정류장명,
        SUM(승객수) AS 순승객수
    FROM analysis_table_final
    WHERE 노선명 = ? AND 기준일자 = ?
    AND 승차_정류장ARS != '00000'
    GROUP BY 노선명, 승차_정류장순번, 승차_정류장ARS, 승차_정류장명

    UNION ALL

    SELECT 노선명, CAST(하차_정류장순번 AS INT) AS 순번,
        하차_정류장ARS AS ARS, 하차_정류장명 AS 정류장명,
        -SUM(승객수) AS 순승객수
    FROM analysis_table_final
    WHERE 노선명 = ? AND 기준일자 = ?
    AND 하차_정류장ARS != '00000'
    GROUP BY 노선명, 하차_정류장순번, 하차_정류장ARS, 하차_정류장명
),
정류장별합산 AS (
    SELECT 노선명, 순번, ARS, 정류장명,
        SUM(순승객수) AS 정류장순승객
    FROM 순승객
    GROUP BY 노선명, 순번, ARS, 정류장명
),
재차량계산 AS (
    SELECT 노선명, 순번, ARS, 정류장명,
        SUM(정류장순승객) OVER (
            PARTITION BY 노선명
            ORDER BY 순번
            ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
        ) AS 재차량
    FROM 정류장별합산
)
SELECT 노선명, 순번, ARS, 정류장명,
    CASE WHEN 재차량 < 0 THEN 0
         ELSE ROUND(재차량 / 19.0) END AS 재차량,
    ROUND(재차량 * 100.0 / NULLIF(MAX(재차량) OVER (PARTITION BY 노선명), 0), 1) AS 상대혼잡도
FROM 재차량계산
ORDER BY 순번
```

---

## 전처리 파이프라인 상세

전처리는 Spring Boot REST API로 구현되어 Airflow DAG으로 매일 새벽 3시 자동 실행됩니다.

### 전처리 그룹 구성

| 그룹 | 내용 | API |
|------|------|-----|
| 그룹 1 | 가상 정류장 처리 (ARS=00000 통합, 정류장 순번 부여) | `GET /api/busstop/fix-virtual-stop` |
| 그룹 2 | 순번/ARS 불일치 보정 | `GET /api/busstop/fix-sequence` |
| 그룹 3 | 승차=하차 동일 보정 | `GET /api/busstop/fix-same-stop-od` |
| 그룹 4 | 표준코드 NULL 보정 (타날짜 조인) | `GET /api/busstop/fix-null` |
| 그룹 5 | 중복 합산 처리 | `GET /api/busstop/deduplicate` |

### 표준코드 NULL 처리 전략

**1단계**: 2025 데이터 조인으로 대량 채우기

```sql
UPDATE a SET a.승차_정류장표준코드 = b.승차_정류장표준코드
FROM analysis_table_final a
JOIN analysis_table_final b ON a.승차_정류장ars = b.승차_정류장ars
WHERE a.기준일자 = '2023XXXX' AND b.기준일자 LIKE '2025%'
AND b.승차_정류장표준코드 IS NOT NULL
```

**2단계**: 잔여 NULL은 타날짜 데이터 조인으로 채우기 (2023/2024 데이터 활용)

```sql
UPDATE a SET a.승차_정류장표준코드 = b.승차_정류장표준코드
FROM analysis_table_final a
JOIN analysis_table_final b ON a.승차_정류장ars = b.승차_정류장ars
WHERE a.승차_정류장표준코드 IS NULL
AND a.기준일자 = '2023XXXX'
AND b.기준일자 != '2023XXXX'
AND b.승차_정류장표준코드 IS NOT NULL
```

---

## API 엔드포인트 (Swagger UI)

Swagger UI: `http://localhost:8080/swagger-ui/index.html`

### 혼잡도 API

| 메서드 | 엔드포인트 | 설명 |
|--------|-----------|------|
| GET | `/api/congestion/{노선명}?date=20231017` | 단일 노선 혼잡도 |
| GET | `/api/congestion?routes={노선명,노선명}&date=20231017` | 복수 노선 혼잡도 |
| GET | `/api/congestion/stops?route={노선명}&date=20231017` | 정류장 목록 |
| GET | `/api/congestion/section?route={노선명}&from={출발지}&to={도착지}&date=20231017` | 출발-도착 구간 혼잡도 |

### OD API

| 메서드 | 엔드포인트 | 설명 |
|--------|-----------|------|
| GET | `/api/od/{노선명}/{ARS}?date=20231017` | 정류장별 OD |
| GET | `/api/od/all?date=20231017` | 전체 노선 OD |

### 구간 혼잡도 조회 예시

```json
GET /api/congestion/section?route=6713&from=국회의사당역5번출구&to=샛강역4번출구.여의도자이&date=20251014

[
  { "노선명": "6713", "순번": 46, "정류장명": "국회의사당역5번출구", "재차량": 73, "상대혼잡도": 67.6, "혼잡도등급": "혼잡" },
  { "노선명": "6713", "순번": 47, "정류장명": "여의도역6번출구", "재차량": 93, "상대혼잡도": 86.1, "혼잡도등급": "매우혼잡" },
  { "노선명": "6713", "순번": 48, "정류장명": "샛강역4번출구.여의도자이", "재차량": 100, "상대혼잡도": 92.6, "혼잡도등급": "매우혼잡" }
]
```

재차량이 73 → 93 → 100으로 증가하는 패턴이 구간별 혼잡도 심화를 정확하게 반영합니다.

---

## 인터랙티브 혼잡도 지도

**[지도 바로보기](https://lanx2933.github.io/Transit-Data_Seoul/03_visualization/congestion_map.html)**

### 레이어 구성
- **노선별 독립 혼잡도**: 각 노선별 재차량 기준 혼잡도 (노선별 독립 레이어)
- **전체 통합 기준 혼잡도**: 전체 노선 통합 기준 비교
- **정류장 단위 승객 밀집도**: 정류장별 총 재차량 합산

### 팝업 기능
- 정류장 클릭 시 혼잡도(%), 재차량, 총승객수 표시
- 여기서 타면 → 어디서 내리는지 (노선 순번 순)
- 어디서 타고 → 여기서 내리는지 (노선 순번 순)

### 날짜 추가 방법

```python
날짜목록 = ['20231017', '20241019', '20251014']  # 여기만 수정
```

---

## 시각화 결과물

### 1. 노선 전체 혼잡도
![노선 전체 혼잡도](docs/images/노선_전체_혼잡도.png)

### 2. 143번 혼잡 구간 분석
![143번 혼잡도](docs/images/143.png)

### 3. 송파02 노선 혼잡도
![송파02 노선 혼잡도](docs/images/송파02.png)

### 4. 위례 지역 전체 노선 혼잡도
![위례 전체 노선 혼잡도](docs/images/위례_전체.png)

### 5. 위례 지역 정류장 단위 승객 밀집도
![위례 정류장 혼잡도](docs/images/위례_정류장_혼잡도.png)

### 6. OD 팝업 + 레이어 컨트롤 전체 화면
![전체 포괄](docs/images/전체_포괄.png)

---

## 데이터 기반 인사이트 도출

### 1. 143번 종로2가 - 고속터미널 구간: 다람쥐버스 투입 근거

혼잡도 시각화 결과, **143번 버스의 종로2가 - 고속터미널 구간**이 매우혼잡(빨강) 등급으로 나타났으며 실제 민원이 빈번한 구간입니다.

**분석 결과**:
- 해당 구간은 143번 전체 노선 중 혼잡도가 가장 높은 구간
- 기존 대형버스만으로는 수요 커버에 한계 존재
- 혼잡 구간에 출퇴근 집중배차 맞춤버스(다람쥐버스) 투입 시 분산 효과 기대

> **정책 제언**: 종로2가 - 고속터미널 구간에 다람쥐버스를 투입하여 혼잡 완화 및 민원 감소 효과를 도모할 수 있습니다.

---

### 2. 302번 노선 개편: 상대원 - 중앙시장 구간 경기도 시내버스 분리 검토

| 정류장 | 순번 | 하차승객수 |
|--------|------|-----------|
| 성남시의료원.신흥1동행정복지센터 | 19 | **390명** |
| 성일중고.성일정보고.동광중고 | 16 | 161명 |
| 성남종합운동장.성남동행정복지센터 | 15 | 191명 |
| 중앙시장 | 21 | 139명 |
| 성호시장입구.단대리약국 | 18 | 132명 |
| 중원구청 | 14 | 74명 |

**분석 결과**:
- 상대원차고지 - 중앙시장 구간은 서울행 수요보다 성남 생활권 중심의 수요 패턴
- 302번과 303번이 겹치지 않는 해당 구간은 경기도 시내버스로 분리 운행이 효율적

> **정책 제언**: 302번을 복정역으로 단축하여 복정역 - 상왕십리역 운행 시 노선 효율화 가능

---

### 3. 노선 수요 집중 구간 정량화

| 노선 | 구간 | 구간승객수 | 전체대비 |
|------|------|-----------|---------|
| 303 | 상대원 ↔ 잠실역 (왕복) | 14,319명 | **56.3%** |
| 303 | 상대원 ↔ 통계청.태평역 (왕복) | 4,939명 | 19.4% |
| 302 | 상대원 ↔ 잠실역 (왕복) | 9,559명 | **51.2%** |
| 4425 | 은곡마을 ↔ 삼성역 (왕복) | 4,457명 | **56.3%** |

> **인사이트**: 302번, 303번, 4425번 모두 전체 수요의 50% 이상이 특정 핵심 구간에 집중되어 있어, 해당 구간 증편 또는 노선 변경의 데이터 기반 근거가 됩니다.

---

## 핵심 분석 지표

### 혼잡도 등급 기준

| 등급 | 범위 | 색상 |
|------|------|------|
| 쾌적 | 0~20% | 초록 |
| 여유 | 20~40% | 연두 |
| 보통 | 40~60% | 노랑 |
| 혼잡 | 60~80% | 주황 |
| 매우혼잡 | 80~100% | 빨강 |

---

## 시행착오 및 문제 해결

### 1. 시각화 도구 3번 교체: Tableau → QGIS → Folium

- **Tableau**: 커스텀 좌표 매핑 복잡, OD 분석 시각화에 부적합
- **QGIS**: 정적 이미지만 생성 가능, 인터랙티브 기능 구현 불가
- **Python Folium 최종 채택** ✅ Spring Boot API 연동, 동적 시각화, GitHub Pages 배포 가능

### 2. ARS 코드만 존재 → 표준코드 9자리 매칭 오류

- **시도 1**: ARS 단독 조인 → 서울/경기 동일 ARS 중복 문제 발생
- **시도 2**: 표준코드 조인 → 경기도 표준코드 오입력 케이스 발견
- **최종 해결**: ARS + 정류장명 복합 조인 + 좌표 범위 필터 ✅

```sql
ON b.ARS코드 = r.ARS
AND b.정류장명 = r.정류장명
AND 맵핑좌표Y_F BETWEEN 37.0 AND 38.5
AND 맵핑좌표X_F BETWEEN 126.0 AND 128.0
```

### 3. 가상 정류장 및 차고지 처리

```sql
-- 가상 정류장 필터링
WHERE 승차_정류장ARS != '00000' AND 하차_정류장ARS != '00000'

-- 차고지 음수 재차량 보정
CASE WHEN 재차량 < 0 THEN 0 ELSE ROUND(재차량 / 19.0) END
```

### 4. BOM 문자 처리 문제

```sql
-- REPLACE로 NULL 반환 버그 발생 → SUBSTRING으로 우회
SUBSTRING(노드ID, 2, LEN(노드ID))
```

### 5. ARS 번호 Zero Padding

```python
str(x).zfill(5)  # '7511' → '07511'
```

### 6. N+1 문제 해결: 표준코드 보정 성능 개선

- **기존**: NULL 목록 조회 → 건당 API 호출 → 수천 번 DB 접근
- **개선**: 2025 데이터 조인으로 대량 UPDATE → 잔여만 타날짜 조인 처리

### 7. SQL Server Express 메모리 부족 → PostgreSQL 마이그레이션

- SQL Server 최대 메모리 무제한 설정으로 8GB RAM 전부 소진
- **해결**: PostgreSQL 18 마이그레이션 → API 응답속도 3초 개선

### 8. 컬럼 타입 최적화 (VARCHAR → INT)

```sql
ALTER TABLE analysis_table_final ALTER COLUMN 승차_정류장순번 INT;
ALTER TABLE analysis_table_final ALTER COLUMN 하차_정류장순번 INT;
ALTER TABLE analysis_table_final ALTER COLUMN 승객수 INT;
ALTER TABLE analysis_table_final ALTER COLUMN 전환_노선ID BIGINT;
```

### 9. Hibernate 7.x 호환성 문제

Spring Boot 4.0.4 + Hibernate 7.x 업그레이드 후 JPQL 오류 → JdbcTemplate으로 전환하여 Native SQL 직접 실행

---

## 한계점 및 향후 계획

### 한계점
- 정류장명 불일치로 인한 약 1.5% 좌표 미매칭 (데이터 품질 한계)
- Bus_Stop_Location 좌표 데이터 미적재 (공공 API 연동 예정)

### 기술적 향후 계획

**성능 고도화**
- `@Async` + `CompletableFuture`: 공공 API 병렬 처리 전환
- `Redis` 캐싱: 노선별 혼잡도 TTL 24시간, 정류장 표준코드 TTL 7일

**파이프라인 고도화**
- Kafka 기반 일별 승하차 데이터 실시간 수집
- Spark 기반 대용량 처리 (데이터 안심구역 2TB OD 데이터 처리)

**분석 고도화**
- 시간대별/요일별 데이터 수집 → 혼잡도 예측 모델 (XGBoost/LSTM)
- 트립체인 분석 모델 도입
- 데이터 안심구역 연계: 환승 패턴, 이용자 유형별 트립체인 심층 분석

**서비스 고도화**
- 출발/도착 정류장 입력 시 구간 혼잡도 즉시 조회 웹 서비스 구현
- 일별 승하차 공공 API 연동으로 혼잡도 예측 서비스 구현

---

## 실행 방법

### Docker로 전체 스택 실행 (권장)

```bash
docker-compose up -d
```

서비스 접속:
- Spring Boot API: http://localhost:8080/swagger-ui/index.html
- Airflow UI: http://localhost:8081 (admin/admin)

### 환경변수 설정

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://seouldb:5432/Seoul_Transit
SPRING_DATASOURCE_USERNAME=postgres
SPRING_DATASOURCE_PASSWORD=your_password
```

### Folium 지도 생성

```python
# make_map.py 상단 설정
노선목록 = '143,345,4318'
날짜목록 = ['20231017', '20241019', '20251014']
강제갱신 = False  # True: API 재호출, False: 캐시 사용

python make_map.py
```

---

## 파일 구조

```
Transit-Data_Seoul/
├── README.md
├── Dockerfile                        # Spring Boot Docker 이미지
├── docker-compose.yml                # 전체 스택 (PostgreSQL + Spring Boot + Airflow)
├── 01_preprocessing/
│   ├── dags/
│   │   └── preprocessing_dag.py     # Airflow DAG (전처리 자동화)
│   └── (Python 전처리 스크립트)
├── 02_api/                           # Spring Boot REST API
│   └── src/
├── 03_visualization/                 # Folium 시각화
│   ├── make_map.py
│   └── congestion_map.html
└── docs/
    └── images/
```
