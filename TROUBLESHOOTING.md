# 트러블슈팅 & 데이터 품질 개선 전략

> 서울 버스 OD 데이터 분석 파이프라인 구축 과정에서 발생한 문제점과 해결 과정을 기록했습니다.
> 단순히 코드를 작동시키는 것에서 그치는 것이 아닌 실제 데이터 품질 문제를 직접 발견하고 해결한 경험을 기록했습니다.

---

## 목차

1. [시각화 도구 3번 교체: Tableau → QGIS → Folium](#1-시각화-도구-3번-교체)
2. [단일 날짜 데이터 한계 및 해결](#2-단일-날짜-데이터-한계-및-해결)
3. [ARS 코드만 존재 → 표준코드 9자리 매칭 오류](#3-ars-코드만-존재--표준코드-9자리-매칭-오류)
4. [위경도 오매칭: ARS 코드 중복 문제](#4-위경도-오매칭-ars-코드-중복-문제)
5. [BOM 문자 처리 문제](#5-bom-문자-처리-문제)
6. [가상 정류장 및 차고지 처리](#6-가상-정류장-및-차고지-처리)
7. [승하차 정보 불일치 (Logical Error)](#7-승하차-정보-불일치-logical-error)
8. [ARS 번호 규격화 (Zero Padding)](#8-ars-번호-규격화-zero-padding)
9. [결측치 처리: 표준코드 NULL](#9-결측치-처리-표준코드-null)
10. [SQL 단독 처리 → Python 자동화: 28분 → 10초](#10-sql-단독-처리--python-자동화-28분--10초)
11. [SQL Server Express 메모리 부족](#11-sql-server-express-메모리-부족)
12. [LIKE 앞 와일드카드로 인한 쿼리 속도 저하](#12-like-앞-와일드카드로-인한-쿼리-속도-저하)
13. [Hibernate 7.x 호환성 문제](#13-hibernate-7x-호환성-문제)
14. [정적 분석에서 동적 파이프라인으로 (API 전환)](#14-정적-분석에서-동적-파이프라인으로-api-전환)

---

## 1. 시각화 도구 3번 교체

### Tableau → QGIS → Folium

**시도 1: Tableau**
- 혼잡도 색상과 정류장 좌표를 연동하려 했으나 커스텀 좌표 매핑이 복잡하고 잘 연동되지 않았음
- 결정적으로 정류장별 동적 색상 표현에 한계점을 발견함
- 결론: **포기**

**시도 2: QGIS**
- 구간별 혼잡도 색상 표현은 성공, 과거에 연동을 자주 했던 경험이 있어서 구현은 쉬움
- 그러나 정적 이미지만 생성이 가능하기 때문에, 정류장 클릭이나 OD 팝업과 같은 인터랙티브 기능 구현 불가
- 결론: **포기**

**시도 3: Python Folium (최종 채택)**
- Spring Boot API와 직접 연동하여 동적 데이터 시각화 가능
- 노선별 레이어 분리, OD 팝업, 혼잡도 그라데이션 모두 구현 성공
- GitHub Pages로 URL 하나로 공유 가능
- 결론: **최종 채택** ✅

---

## 2. 단일 날짜 데이터 한계 및 해결

**현상**: 초기에 단일 날짜 CSV 파일만 보유 → 시간대별/요일별 분석 불가

**해결**: 공공데이터포털에서 날짜별 CSV 파일을 추가 다운로드하여 교체 가능한 구조로 설계
- `Analysis_Table_Final` 테이블에 새 CSV BULK INSERT → 즉시 분석 가능
- 파이프라인 자체는 날짜에 무관하게 재사용 가능

**교훈**: 데이터 수집 단계에서 날짜 범위를 넓게 설계하는 것이 중요

**차후 목표**: 현재는 개인정보보호법 상의 한계로 인하여 데이터안심구역을 통하지 않고서는 구현이 불가능하지만, 추후에 상황이 달라질 경우 API 지원 예정 

---

## 3. ARS 코드만 존재 → 표준코드 9자리 매칭 오류

**현상**: 원본 OD 데이터에는 ARS 코드(5자리)만 존재하고, 위치 마스터 데이터(`Bus_Stop_Location`)에는 표준코드(9자리)만 존재
- 두 테이블을 직접 조인할 수 있는 공통 키가 없음

**시도 1: ARS 코드 단독 조인**
- 동일한 ARS 코드가 서울과 경기도, 인천광역시에 각각 존재하는 중복 문제 발생
- 예: ARS `22026` → 서울 남태령역 / 경기도 협동마을(구리시 소재)

**시도 2: 표준코드(9자리) 조인**
- 전국 고유값이라 이론상 완벽한 해결책
- 그러나 원본 데이터에 경기도 표준코드가 오입력된 케이스 발견
- `MAX(표준코드)` 사용 시 숫자가 큰 경기도 코드가 선택되는 문제 발생

**최종 해결: ARS + 정류장명 복합 조인** ✅
```SQL
ON b.ARS코드 = r.ARS
AND b.정류장명 = r.정류장명
AND 맵핑좌표Y_F BETWEEN 37.0 AND 38.5   -- 한반도 위도 범위
AND 맵핑좌표X_F BETWEEN 126.0 AND 128.0  -- 한반도 경도 범위
```
- ARS 중복 문제 + 표준코드 오입력 문제 동시 해결
- 좌표 범위 필터로 서울/경기 광역 노선 모두 지원
- 정류장명 불일치로 인한 NULL 비율 약 1% 미만 (데이터 품질 한계로 명시)

---

## 4. 위경도 오매칭: ARS 코드 중복 문제

**현상**: 단순 ARS 조인 시 경기도 좌표로 오매칭 발생
- 예: `NH농협은행자양로지점` (서울 자양동) → 경기도 좌표로 매칭

**원인**: 동일 ARS(`05148`)에 서울 표준코드(`104000055`)와 경기도 표준코드(`204000026`) 둘 다 존재
- `MAX(표준코드)` 사용 시 숫자가 큰 경기도 코드(`204...`) 선택

**해결**: 3번 항목의 복합 JOIN으로 해결 ✅

---

## 5. BOM 문자 처리 문제

**현상**: CSV BULK INSERT 시 첫 번째 컬럼에 BOM(Byte Order Mark, `\xEF\xBB\xBF`) 문자 삽입 발견
- `REPLACE(노드ID, CHAR(65279), '')` → NULL 반환하는 버그 발생

**해결**: `SUBSTRING` 방식으로 우회 ✅
```SQL
SUBSTRING(노드ID, 2, LEN(노드ID))  -- BOM 첫 글자(1자리) 건너뛰기
```

**교훈**: REPLACE가 NULL을 반환하는 경우 SUBSTRING으로 우회 가능

---

## 6. 가상 정류장 및 차고지 처리

**현상 1: 가상 정류장 (ARS = 00000)**
- 차고지 출발/도착, 임시 정류소 등 실제 정류장이 아닌 가상 정류장
- ARS 코드가 NULL 또는 '00000'으로 저장 → 이를 '00000'으로 우선적으로 통일
- 위경도 매칭 불가, 혼잡도 계산 오류 발생

**해결 1**: 모든 쿼리에서 가상 정류장 제외 ✅
```SQL
WHERE 승차_정류장ARS != '00000'
AND 하차_정류장ARS != '00000'
```

**현상 2: 차고지 음수 재차량**
- 마지막 정류장(차고지, 종점 등)에서 모든 승객이 하차하면 재차량이 음수가 되는 경우 발생
- 누적 재차량 계산 시 음수 값이 나타남

**해결 2**: 음수 재차량 0으로 보정 ✅
```SQL
CASE WHEN r.재차량 < 0 THEN 0 ELSE ROUND(r.재차량 / 19.0, 0) END AS 재차량
```

**현상 3: 순번 보정**
- 일부 데이터에서 승차 순번과 하차 순번이 동일하거나 역전된 케이스 존재
- BMS(버스관리시스템) 로직상 오류로 발생

**해결 3**: Python으로 순번 보정 ✅
```python
if boarding_seq == alighting_seq:
    alighting_seq += 1  # 하차 순번을 승차 순번 + 1로 보정
```

---

## 7. 승하차 정보 불일치 (Logical Error)

**현상**: 승차 정류장과 하차 정류장의 순번/명칭이 동일한 데이터 존재 (BMS 로직상 오류)

**해결 도구**: Python

**이유**: "승차 순번 + 1"이라는 비즈니스 로직 적용 필요
- 행 단위의 조건부 연산이 복잡하게 들어감
- SQL의 `CASE WHEN`보다 Python의 `apply` 로직이 훨씬 가독성이 좋고 유지보수 용이

**로직**:
```Python
if boarding_seq == alighting_seq:
    alighting_seq += 1
```

---

## 8. ARS 번호 규격화 (Zero Padding)

**현상**: `07511`이 `7511`로 저장되어 발생하는 매칭 오류
- 정수형(INT)으로 저장 시 앞자리 0이 사라짐

**해결 1: Python (입력 시 근본 해결)** ✅
```Python
str(x).zfill(5)  # '7511' → '07511'
```
- 데이터를 처음부터 문자열(VARCHAR)로 유지하는 것이 근본적인 해결책

**해결 2: SQL (이미 적재된 경우)**
```SQL
RIGHT('00000' + CAST(ARS AS VARCHAR), 5)
```

**교훈**: 데이터 타입을 정수형(INT)이 아닌 문자열(VARCHAR)로 강제 → 정보 손실 방지

---

## 9. 결측치 처리: 표준코드 NULL

**현상**: 정류장 ARS 번호는 존재하나 매핑된 표준코드가 NULL인 경우

**해결 도구**: SQL

**이유**: DB 내에 다른 행(Row)이나 마스터 테이블에 ARS-코드 매핑 정보가 존재하므로 `JOIN`이나 `COALESCE` 함수를 사용해 집합 단위로 업데이트하는 것이 성능적으로 유리함

**로직**: `STATION_MASTER` 테이블과 ARS 번호를 기준으로 Self-Join하여 NULL 채움
```SQL
UPDATE a
SET a.표준코드 = m.표준코드
FROM Analysis_Table_Final a
JOIN STATION_MASTER m ON a.승차_정류장ARS = m.ARS
WHERE a.승차_정류장표준코드 IS NULL
```

---

## 10. SQL 단독 처리 → Python 자동화: 28분 → 10초

**현상**: 초기에 SQL만으로 데이터 보정 작업 수행
- 3단계 보정 작업(가상 정류장, ARS 불일치, 순번 중복)을 SQL로만 처리
- 매번 수동 실행 필요, 실행 시간 약 **28분** 소요

**해결**: Spring Boot + Python으로 자동화 파이프라인 구축 ✅
- Spring Boot REST API로 보정 로직을 모듈화
- Python으로 API 호출 → CSV 캐싱 → 지도 생성 자동화
- 전체 파이프라인 실행 시간 **28분 → 10초로 단축 (약 168배 향상)**

**구조**:
```
SQL 수동 실행 (28분)
        ↓ 개선
Spring Boot API 자동화
        ↓
Python CSV 캐싱
        ↓
Folium 지도 생성 (10초)
```

---

## 11. SQL Server Express 메모리 부족

**현상**: 복잡한 CTE + 윈도우 함수 실행 시 메모리 부족 오류 발생
```
SQL Error 802: 메모리 부족
```

**원인**: SQL Server Express의 메모리 제한(1.4GB)으로 인해 복잡한 쿼리 실행 시 캐시 부족

**해결**: 쿼리 실행 전 캐시 초기화 ✅
```SQL
DBCC FREEPROCCACHE;    -- 쿼리 실행 계획 캐시 제거
DBCC DROPCLEANBUFFERS; -- 버퍼 풀 캐시 제거
```

---

## 12. LIKE 앞 와일드카드로 인한 쿼리 속도 저하

**현상**: `LIKE '%가상%'` 조건 사용 시 쿼리가 느리게 실행됨

**원인**: 앞에 `%` 와일드카드가 붙으면 인덱스를 타지 못하고 Full Table Scan 발생

**해결**: 정류장명 컬럼에 인덱스 추가 ✅
```SQL
CREATE INDEX IX_Analysis_승차정류장명
ON Seoul_Transit.dbo.Analysis_Table_Final (승차_정류장명);

CREATE INDEX IX_Analysis_하차정류장명
ON Seoul_Transit.dbo.Analysis_Table_Final (하차_정류장명);
```

**교훈**: 자주 검색하는 컬럼에는 인덱스를 추가하고, LIKE 검색 시 앞 와일드카드 사용을 최소화

---

## 13. Hibernate 7.x 호환성 문제

**현상**: Spring Boot 4.0.4 + Hibernate 7.x 업그레이드 후 JPQL 오류 발생
- ROW_NUMBER() 등 윈도우 함수를 JPA `@Query`로 처리 불가
- Hibernate 7.x의 엄격한 JPQL 파싱으로 인한 오류

**해결**: JPA Repository 대신 JdbcTemplate으로 전환 ✅
- 복잡한 CTE + 윈도우 함수를 Native SQL로 직접 실행
- 유연성과 성능 모두 확보

```Java
// JPA @Query 대신 JdbcTemplate 사용
return jdbcTemplate.query(sql,
    (rs, rowNum) -> new CongestionDto(...),
    routeName, routeName);
```

---

## 14. 정적 분석에서 동적 파이프라인으로 (API 전환)

**현상**: CSV 기반 분석의 일회성 한계
- 노선을 바꿀 때마다 수동으로 CSV를 다시 가공해야 함 → 재현성과 확장성 둘 다 미충족

**해결**: Spring Boot REST API 구축으로 동적 파이프라인 전환 ✅

```
GET /api/congestion/{노선명}          -- 단일 노선 혼잡도
GET /api/congestion?routes=143,345   -- 복수 노선 혼잡도
GET /api/od/{노선명}/{ARS}           -- 정류장별 OD
GET /api/od/all                      -- 전체 노선 OD
```

- 노선명만 바꾸면 즉시 새 데이터 조회 가능
- Python에서 API 호출 → CSV 캐싱 → 지도 생성 자동화
- 확장성 있는 데이터 파이프라인 구축

---

## 📊 문제 해결 요약

| # | 문제 | 해결 도구 | 핵심 해결책 |
|---|------|-----------|------------|
| 1 | 시각화 도구 선택 | Folium | Spring Boot API 연동 인터랙티브 지도 |
| 2 | 단일 날짜 한계 | CSV 교체 | 날짜 독립적 파이프라인 설계 |
| 3 | ARS↔표준코드 매칭 | SQL | ARS + 정류장명 복합 조인 |
| 4 | 위경도 오매칭 | SQL | 복합 조인 + 좌표 범위 필터 |
| 5 | BOM 문자 | SQL | SUBSTRING으로 우회 |
| 6 | 가상 정류장/차고지 | SQL+Python | ARS!=00000 필터 + 음수 보정 |
| 7 | 승하차 순번 불일치 | Python | boarding_seq + 1 보정 |
| 8 | ARS Zero Padding | Python | str.zfill(5) |
| 9 | 표준코드 NULL | SQL | Self-Join COALESCE |
| 10 | 처리 속도 | Spring Boot+Python | 28분 → 10초 (168배 향상) |
| 11 | 메모리 부족 | SQL | DBCC FREEPROCCACHE |
| 12 | LIKE 속도 저하 | SQL | 인덱스 추가 |
| 13 | Hibernate 7.x | Java | JdbcTemplate 전환 |
| 14 | 정적→동적 파이프라인 | Spring Boot | REST API 구축 |