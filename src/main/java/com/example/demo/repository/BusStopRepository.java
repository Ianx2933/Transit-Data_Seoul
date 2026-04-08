package com.example.demo.repository;

import com.example.demo.entity.BusStop;
import com.example.demo.entity.BusStopId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Repository // Data Access Layer 컴포넌트 선언
public interface BusStopRepository extends JpaRepository<BusStop, BusStopId> {

    // ============================================================
    // 1. 조회
    // ============================================================

    /**
     * 특정 날짜의 승차 표준코드가 NULL인 정류장 목록 조회
     */
    List<BusStop> findBy기준일자AndAnd승차정류장표준코드IsNull(String 기준일자);

    /**
     * 특정 날짜의 하차 표준코드가 NULL인 정류장 목록 조회
     */
    List<BusStop> findBy기준일자And하차정류장표준코드IsNull(String 기준일자);

    /**
     * ARS 코드로 정류장 조회
     */
    List<BusStop> findBy승차정류장ARS(String ars);

    /**
     * 노선명으로 정류장 조회
     */
    List<BusStop> findBy노선명(String 노선명);

    /**
     * 양방향 노선 탐지
     * 순번 0인 가상 정류장이 존재하면서 최대순번이 100 이상인 노선 탐지
     * 탐지된 노선은 수동 처리 권장
     */
    @Query("SELECT DISTINCT b.노선명 FROM BusStop b " +
           "WHERE b.기준일자 = :기준일자 " +
           "AND b.승차정류장순번 = 0 " +                // INTEGER 타입이므로 숫자로 비교
           "AND b.승차정류장ARS = '00000' " +
           "AND b.노선명 IN (" +
           "    SELECT b2.노선명 FROM BusStop b2 " +
           "    WHERE b2.기준일자 = :기준일자 " +
           "    AND b2.승차정류장순번 IS NOT NULL " +
           "    AND b2.승차정류장ARS != '00000' " +
           "    GROUP BY b2.노선명 " +
           "    HAVING MAX(b2.승차정류장순번) > 100" +   // INTEGER 타입이므로 CAST 불필요
           ")")
    List<String> detectBidirectionalRoutes(@Param("기준일자") String 기준일자);

    // ============================================================
    // 2. 표준코드 보정
    // ============================================================

    /**
     * 승차 정류장 표준코드 UPDATE
     */
    @Modifying
    @Transactional
    @Query("UPDATE BusStop b SET b.승차정류장표준코드 = :standardCode " +
           "WHERE b.기준일자 = :기준일자 " +
           "AND b.노선명 = :노선명 " +
           "AND b.승차정류장ARS = :승차ARS " +
           "AND b.하차정류장ARS = :하차ARS")
    void update승차정류장표준코드(
        @Param("기준일자") String 기준일자,
        @Param("노선명") String 노선명,
        @Param("승차ARS") String 승차ARS,
        @Param("하차ARS") String 하차ARS,
        @Param("standardCode") String standardCode
    );

    /**
     * 하차 정류장 표준코드 UPDATE
     */
    @Modifying
    @Transactional
    @Query("UPDATE BusStop b SET b.하차정류장표준코드 = :standardCode " +
           "WHERE b.기준일자 = :기준일자 " +
           "AND b.노선명 = :노선명 " +
           "AND b.승차정류장ARS = :승차ARS " +
           "AND b.하차정류장ARS = :하차ARS")
    void update하차정류장표준코드(
        @Param("기준일자") String 기준일자,
        @Param("노선명") String 노선명,
        @Param("승차ARS") String 승차ARS,
        @Param("하차ARS") String 하차ARS,
        @Param("standardCode") String standardCode
    );

    // ============================================================
    // 3. 가상 정류장 처리
    // ============================================================

    /**
     * 종점 가상 정류장 빈 정류장명 자동 보정
     * 하차 정류장명이 NULL 또는 빈 값이면서 표준코드가 가상 정류장인 경우
     * 승차 정류장명을 하차 정류장명에 복사하여 보정
     */
    @Modifying
    @Transactional
    @Query("UPDATE BusStop b SET b.하차정류장명 = b.승차정류장명 " +
           "WHERE b.기준일자 = :기준일자 " +
           "AND (b.하차정류장명 IS NULL OR b.하차정류장명 = '') " +
           "AND b.하차정류장표준코드 = '277102436'")
    int fixEmptyArrivalStopName(@Param("기준일자") String 기준일자);

    /**
     * 종점 가상 정류장 빈 정류장명 잔여 건수 조회 (보정 후 검증용)
     */
    @Query("SELECT COUNT(b) FROM BusStop b " +
           "WHERE b.기준일자 = :기준일자 " +
           "AND (b.하차정류장명 IS NULL OR b.하차정류장명 = '') " +
           "AND b.하차정류장표준코드 = '277102436'")
    int countEmptyArrivalStopName(@Param("기준일자") String 기준일자);

    /**
     * 가상 정류장 승차 ARS 00000 통합
     */
    @Modifying
    @Transactional
    @Query("UPDATE BusStop b SET b.승차정류장ARS = '00000' " +
           "WHERE b.기준일자 = :기준일자 " +
           "AND (b.승차정류장명 LIKE '%가상%' " +
           "OR b.승차정류장명 LIKE '%기점가상%' " +
           "OR b.승차정류장명 LIKE '%경유%') " +
           "AND (b.승차정류장ARS != '06137' OR b.승차정류장ARS IS NULL)")
    int fixVirtualStopBoardingArs(@Param("기준일자") String 기준일자);

    /**
     * 가상 정류장 하차 ARS 00000 통합
     */
    @Modifying
    @Transactional
    @Query("UPDATE BusStop b SET b.하차정류장ARS = '00000' " +
           "WHERE b.기준일자 = :기준일자 " +
           "AND (b.하차정류장명 LIKE '%가상%' " +
           "OR b.하차정류장명 LIKE '%기점가상%' " +
           "OR b.하차정류장명 LIKE '%경유%') " +
           "AND (b.하차정류장ARS != '06137' OR b.하차정류장ARS IS NULL)")
    int fixVirtualStopAlightingArs(@Param("기준일자") String 기준일자);

    /**
     * 가상 정류장 승차 ARS 미통합 잔여 건수 조회 (보정 후 검증용)
     */
    @Query("SELECT COUNT(b) FROM BusStop b " +
           "WHERE b.기준일자 = :기준일자 " +
           "AND (b.승차정류장명 LIKE '%가상%' " +
           "OR b.승차정류장명 LIKE '%기점가상%' " +
           "OR b.승차정류장명 LIKE '%경유%') " +
           "AND b.승차정류장ARS != '00000' " +
           "AND b.승차정류장ARS != '06137'")
    int countUnfixedVirtualBoardingArs(@Param("기준일자") String 기준일자);

    /**
     * 가상 정류장 하차 ARS 미통합 잔여 건수 조회 (보정 후 검증용)
     */
    @Query("SELECT COUNT(b) FROM BusStop b " +
           "WHERE b.기준일자 = :기준일자 " +
           "AND (b.하차정류장명 LIKE '%가상%' " +
           "OR b.하차정류장명 LIKE '%기점가상%' " +
           "OR b.하차정류장명 LIKE '%경유%') " +
           "AND b.하차정류장ARS != '00000' " +
           "AND b.하차정류장ARS != '06137'")
    int countUnfixedVirtualAlightingArs(@Param("기준일자") String 기준일자);

    /**
     * 기점 가상 정류장 승차순번 0 부여
     * 승차순번이 INTEGER 타입이므로 숫자 0으로 설정
     */
    @Modifying
    @Transactional
    @Query(value =
           "UPDATE analysis_table_final " +
           "SET 승차_정류장순번 = 0 " +                  // INTEGER 타입이므로 숫자 0
           "WHERE 기준일자 = :기준일자 " +
           "AND 승차_정류장ars = '00000' " +
           "AND 승차_정류장순번 IS NULL",
           nativeQuery = true)
    int fixBoardingSequence(@Param("기준일자") String 기준일자);

    /**
     * 기점 가상 정류장 승차순번 NULL 잔여 건수 조회 (보정 후 검증용)
     */
    @Query(value =
           "SELECT COUNT(*) FROM analysis_table_final " +
           "WHERE 기준일자 = :기준일자 " +
           "AND 승차_정류장ars = '00000' " +
           "AND 승차_정류장순번 IS NULL",
           nativeQuery = true)
    int countNullBoardingSequence(@Param("기준일자") String 기준일자);

    /**
     * 케이스 A: 승차순번 0 → 하차순번 0 부여
     * 승차순번, 하차순번 모두 INTEGER 타입이므로 숫자로 비교 및 설정
     */
    @Modifying
    @Transactional
    @Query(value =
           "UPDATE analysis_table_final " +
           "SET 하차_정류장순번 = 0 " +                  // INTEGER 타입이므로 숫자 0
           "WHERE 기준일자 = :기준일자 " +
           "AND 승차_정류장순번 = 0 " +                  // INTEGER 타입이므로 숫자 비교
           "AND 승차_정류장ars = '00000' " +
           "AND 하차_정류장ars = '00000' " +
           "AND 하차_정류장순번 IS NULL",
           nativeQuery = true)
    int fixAlightingSequenceCaseA(@Param("기준일자") String 기준일자);

    /**
     * 케이스 C1: 승차순번 1~10 → 승차 정류장으로 덮어씌우기
     * 승차순번, 하차순번 모두 INTEGER 타입이므로 CAST 불필요
     */
    @Modifying
    @Transactional
    @Query(value =
           "UPDATE analysis_table_final " +
           "SET 하차_정류장명 = 승차_정류장명, " +
           "하차_정류장ars = 승차_정류장ars, " +
           "하차_정류장표준코드 = 승차_정류장표준코드, " +
           "하차_정류장순번 = 승차_정류장순번 " +         // INTEGER끼리 직접 대입
           "WHERE 기준일자 = :기준일자 " +
           "AND 하차_정류장ars = '00000' " +
           "AND 하차_정류장순번 IS NULL " +
           "AND 승차_정류장순번 BETWEEN 1 AND 10",        // INTEGER 타입이므로 CAST 불필요
           nativeQuery = true)
    int fixAlightingSequenceCaseC1(@Param("기준일자") String 기준일자);

    /**
     * 케이스 C2: 이상치 데이터 anomaly_data 테이블로 분리
     * 승차순번이 INTEGER 타입이므로 CAST 불필요
     */
    @Modifying
    @Transactional
    @Query(value =
       "INSERT INTO anomaly_data " +
       "SELECT a.기준일자, a.노선명, a.전환_노선id, " +
       "    a.승차_정류장순번, a.승차_정류장ars, a.승차_정류장표준코드, a.승차_정류장명, " +
       "    a.하차_정류장순번, a.하차_정류장ars, a.하차_정류장표준코드, a.하차_정류장명, a.승객수 " +
       "FROM analysis_table_final a " +
       "INNER JOIN (" +
       "    SELECT 노선명, MAX(승차_정류장순번) AS 최대순번 " +   // INTEGER 타입이므로 CAST 불필요
       "    FROM analysis_table_final " +
       "    WHERE 승차_정류장순번 IS NOT NULL " +
       "    AND 기준일자 = :기준일자 " +
       "    GROUP BY 노선명" +
       ") b ON a.노선명 = b.노선명 " +
       "WHERE a.기준일자 = :기준일자 " +
       "AND a.하차_정류장ars = '00000' " +
       "AND a.하차_정류장순번 IS NULL " +
       "AND a.승차_정류장순번::FLOAT / b.최대순번 * 100 < 48 " + // INTEGER를 FLOAT로 캐스팅
       "AND a.승차_정류장순번 >= 11",                            // INTEGER 타입이므로 CAST 불필요
       nativeQuery = true)
    int insertAnomalyDataCaseC2(@Param("기준일자") String 기준일자);

    /**
     * 케이스 C2: 이상치 본 테이블에서 삭제
     * 승차순번이 INTEGER 타입이므로 CAST 불필요
     */
    @Modifying
    @Transactional
    @Query(value =
           "DELETE FROM analysis_table_final a " +
           "USING (" +
           "    SELECT 노선명, MAX(승차_정류장순번) AS 최대순번 " +  // INTEGER 타입이므로 CAST 불필요
           "    FROM analysis_table_final " +
           "    WHERE 승차_정류장순번 IS NOT NULL " +
           "    AND 기준일자 = :기준일자 " +
           "    GROUP BY 노선명" +
           ") b " +
           "WHERE a.노선명 = b.노선명 " +
           "AND a.기준일자 = :기준일자 " +
           "AND a.하차_정류장ars = '00000' " +
           "AND a.하차_정류장순번 IS NULL " +
           "AND a.승차_정류장순번::FLOAT / b.최대순번 * 100 < 48 " + // INTEGER를 FLOAT로 캐스팅
           "AND a.승차_정류장순번 >= 11",                            // INTEGER 타입이므로 CAST 불필요
           nativeQuery = true)
    int deleteAnomalyDataCaseC2(@Param("기준일자") String 기준일자);

    /**
     * 케이스 B: 종점 가상 정류장 하차순번 최대순번+1 부여
     * 승차순번, 하차순번 모두 INTEGER 타입이므로 CAST 불필요
     */
    @Modifying
    @Transactional
    @Query(value =
           "UPDATE analysis_table_final a " +
           "SET 하차_정류장순번 = b.최대순번 + 1 " +      // INTEGER 연산 결과 직접 설정
           "FROM (" +
           "    SELECT 노선명, MAX(승차_정류장순번) AS 최대순번 " +  // INTEGER 타입이므로 CAST 불필요
           "    FROM analysis_table_final " +
           "    WHERE 승차_정류장순번 IS NOT NULL " +
           "    AND 기준일자 = :기준일자 " +
           "    GROUP BY 노선명" +
           ") b " +
           "WHERE a.노선명 = b.노선명 " +
           "AND a.기준일자 = :기준일자 " +
           "AND a.하차_정류장ars = '00000' " +
           "AND a.하차_정류장순번 IS NULL",
           nativeQuery = true)
    int fixAlightingSequenceCaseB(@Param("기준일자") String 기준일자);

    /**
     * 종점 가상 정류장 하차순번 NULL 잔여 건수 조회 (보정 후 검증용)
     */
    @Query("SELECT COUNT(b) FROM BusStop b " +
           "WHERE b.기준일자 = :기준일자 " +
           "AND b.하차정류장ARS = '00000' " +
           "AND b.하차정류장순번 IS NULL")
    int countNullAlightingSequence(@Param("기준일자") String 기준일자);

    /**
     * 가상 정류장 승차 처리
     * 승차순번, 하차순번 모두 INTEGER 타입이므로 CAST 불필요
     */
    @Modifying
    @Transactional
    @Query(value =
           "UPDATE analysis_table_final " +
           "SET 하차_정류장순번 = 승차_정류장순번, " +     // INTEGER끼리 직접 대입
           "하차_정류장ars = 승차_정류장ars, " +
           "하차_정류장표준코드 = 승차_정류장표준코드, " +
           "하차_정류장명 = 승차_정류장명 " +
           "WHERE 기준일자 = :기준일자 " +
           "AND 승차_정류장ars != '00000' " +
           "AND 하차_정류장ars = '00000' " +
           "AND 하차_정류장순번 IS NOT NULL",
           nativeQuery = true)
    int fixVirtualStopBoarding(@Param("기준일자") String 기준일자);

    /**
     * 중간 경유 가상 정류장 승차 처리
     * 승차순번, 하차순번 모두 INTEGER 타입이므로 CAST 불필요
     */
    @Modifying
    @Transactional
    @Query(value =
           "UPDATE analysis_table_final " +
           "SET 승차_정류장순번 = 하차_정류장순번, " +     // INTEGER끼리 직접 대입
           "승차_정류장ars = 하차_정류장ars, " +
           "승차_정류장표준코드 = 하차_정류장표준코드, " +
           "승차_정류장명 = 하차_정류장명 " +
           "WHERE 기준일자 = :기준일자 " +
           "AND 승차_정류장ars = '00000' " +
           "AND 승차_정류장순번 != 0",                     // INTEGER 타입이므로 숫자 비교
           nativeQuery = true)
    int fixMidVirtualStopBoarding(@Param("기준일자") String 기준일자);

    /**
     * 가상 정류장 승차=하차 동일 처리
     * 승차순번, 하차순번 모두 INTEGER 타입이므로 직접 비교 및 연산
     */
    @Modifying
    @Transactional
    @Query(value =
           "UPDATE analysis_table_final " +
           "SET 하차_정류장순번 = 승차_정류장순번 + 1 " +  // INTEGER 연산 결과 직접 설정
           "WHERE 기준일자 = :기준일자 " +
           "AND 승차_정류장ars = '00000' " +
           "AND 하차_정류장ars = '00000' " +
           "AND 승차_정류장순번 = 하차_정류장순번",         // INTEGER끼리 직접 비교
           nativeQuery = true)
    int fixVirtualStopSameOD(@Param("기준일자") String 기준일자);

    // ============================================================
    // 4. 순번/ARS 보정
    // ============================================================

    /**
     * 동일 ARS 순번 불일치 보정
     * 같은 노선에서 동일한 승차ARS인데 순번만 다른 경우 가장 많이 등장하는 순번으로 통일
     */
    @Modifying
    @Transactional
    @Query(value =
       "UPDATE analysis_table_final a " +
       "SET 승차_정류장순번 = b.최빈순번 " +
       "FROM (" +
       "    SELECT 노선명, 승차_정류장ars, 승차_정류장순번 AS 최빈순번 " +
       "    FROM (" +
       "        SELECT 노선명, 승차_정류장ars, 승차_정류장순번, " +
       "            ROW_NUMBER() OVER (PARTITION BY 노선명, 승차_정류장ars ORDER BY COUNT(*) DESC) AS rn " +
       "        FROM analysis_table_final " +
       "        WHERE 승차_정류장ars != '00000' " +
       "        AND 기준일자 = :기준일자 " +
       "        GROUP BY 노선명, 승차_정류장ars, 승차_정류장순번" +
       "    ) ranked " +
       "    WHERE rn = 1" +
       ") b " +
       "WHERE a.노선명 = b.노선명 " +
       "AND a.승차_정류장ars = b.승차_정류장ars " +
       "AND a.승차_정류장순번 != b.최빈순번 " +
       "AND a.기준일자 = :기준일자",
       nativeQuery = true)
    int fixSequenceByArs(@Param("기준일자") String 기준일자);

    // ============================================================
    // 5. 승차=하차 동일 보정
    // ============================================================

    /**
     * 순번 기준 승차=하차 동일 보정
     * 승차순번, 하차순번 모두 INTEGER 타입이므로 직접 비교 및 연산
     */
    @Modifying
    @Transactional
    @Query(value =
           "UPDATE analysis_table_final " +
           "SET 하차_정류장순번 = 승차_정류장순번 + 1 " +  // INTEGER 연산 결과 직접 설정
           "WHERE 기준일자 = :기준일자 " +
           "AND 승차_정류장순번 = 하차_정류장순번 " +       // INTEGER끼리 직접 비교
           "AND 승차_정류장ars = 하차_정류장ars " +
           "AND 승차_정류장ars != '00000'",
           nativeQuery = true)
    int fixSameStopODBySequence(@Param("기준일자") String 기준일자);
}