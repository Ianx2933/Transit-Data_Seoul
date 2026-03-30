package com.example.demo.service;                          // Service 레이어 패키지 선언

import com.example.demo.dto.CongestionDto;                 // 응답 데이터 구조 import
import lombok.RequiredArgsConstructor;                     // final 필드 생성자 자동 생성
import org.springframework.jdbc.core.JdbcTemplate;        // 하나의 커넥션에서 SQL 실행 보장
import org.springframework.stereotype.Service;             // Business Logic Layer 컴포넌트 선언

import java.util.List;                                     // 여러 건의 데이터를 List로 반환하기 위해 import

@Service
@RequiredArgsConstructor
public class CongestionService {

    private final JdbcTemplate jdbcTemplate;
    // JPA Repository 대신 JdbcTemplate을 쓰는 이유:
    // CTE(WITH절) + 윈도우 함수(SUM OVER)가 포함된 복잡한 SQL은
    // JPA @Query로 처리하기 어렵기 때문에 JdbcTemplate으로 직접 실행

    /**
     * 노선명을 받아서 해당 노선의 정류장별 재차량과 혼잡도를 계산하여 반환
     * 위경도 조인 방식: ARS코드 + 정류장명 복합 조인
     * → 표준코드 오입력 문제 해결 + ARS 코드 중복 문제 동시 해결
     * → 서울/경기 광역 노선 모두 지원
     * 재차량은 19시간(04~23시) 기준 시간당 평균으로 보정
     * 총승객수는 승차 + 하차 합산
     * @param routeName 조회할 노선 번호 (예: "422")
     * @return 정류장별 혼잡도 + 위경도 + 총승객수 정보 목록
     */
    public List<CongestionDto> getCongestionByRoute(String routeName) {

        String sql =

            "WITH 순승객 AS (" +

            // 승차 데이터: 승차 정류장순번 기준으로 승객수 합산 (양수)
            "    SELECT 노선명, CAST(승차_정류장순번 AS INT) AS 순번, " +
            "        승차_정류장ARS AS ARS, 승차_정류장명 AS 정류장명, " +
            "        SUM(CAST(승객수 AS INT)) AS 순승객수 " +          // 승차는 양수로 합산
            "    FROM Analysis_Table_Final " +
            "    WHERE 노선명 = ? " +                                  // 파라미터 바인딩 (SQL Injection 방지)
            "    AND 승차_정류장ARS != '00000' " +                     // 가상 정류장 제외
            "    GROUP BY 노선명, 승차_정류장순번, 승차_정류장ARS, 승차_정류장명 " +

            "    UNION ALL " +

            // 하차 데이터: 하차 정류장순번 기준으로 승객수 합산 (음수)
            "    SELECT 노선명, CAST(하차_정류장순번 AS INT) AS 순번, " +
            "        하차_정류장ARS AS ARS, 하차_정류장명 AS 정류장명, " +
            "        -SUM(CAST(승객수 AS INT)) AS 순승객수 " +         // 하차는 음수로 합산
            "    FROM Analysis_Table_Final " +
            "    WHERE 노선명 = ? " +                                  // 파라미터 바인딩
            "    AND 하차_정류장ARS != '00000' " +                     // 가상 정류장 제외
            "    GROUP BY 노선명, 하차_정류장순번, 하차_정류장ARS, 하차_정류장명 " +
            "), " +

            // 2단계 CTE: 같은 순번의 승차/하차를 합산하여 정류장별 순승객 계산
            "정류장별합산 AS (" +
            "    SELECT 노선명, 순번, ARS, 정류장명, " +
            "        SUM(순승객수) AS 정류장순승객 " +                  // 승차 - 하차 = 순승객
            "    FROM 순승객 " +
            "    GROUP BY 노선명, 순번, ARS, 정류장명 " +
            "), " +

            // 3단계 CTE: 순번 순서대로 누적 합산하여 재차량 계산
            "재차량계산 AS (" +
            "    SELECT 노선명, 순번, ARS, 정류장명, 정류장순승객, " +
            "        SUM(정류장순승객) OVER (" +                        // 윈도우 함수로 누적 합산
            "            PARTITION BY 노선명 " +                       // 노선별로 독립적으로 계산
            "            ORDER BY 순번 " +                             // 순번 오름차순으로 누적
            "            ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW" +
            "        ) AS 재차량 " +
            "    FROM 정류장별합산 " +
            ") " +

            // 최종 SELECT: 재차량 기반 혼잡도 + ARS+정류장명 복합 조인으로 위경도 매칭
            // ARS + 정류장명 복합 조인: ARS 중복 문제 + 표준코드 오입력 문제 동시 해결
            // 좌표 범위 필터: 한반도 내 좌표만 허용 (서울+경기 모두 지원)
            "SELECT r.노선명, r.순번, r.ARS, r.정류장명, " +
            "    CASE WHEN r.재차량 < 0 THEN 0 ELSE ROUND(r.재차량 / 19.0, 0) END AS 재차량, " +           // 음수 보정 + 19시간 평균
            "    MAX(CASE WHEN r.재차량 < 0 THEN 0 ELSE ROUND(r.재차량 / 19.0, 0) END) OVER (PARTITION BY r.노선명) AS 최대재차량, " +
            "    ROUND(" +
            "        CASE WHEN r.재차량 < 0 THEN 0 ELSE ROUND(r.재차량 / 19.0, 0) END * 100.0 " +
            "        / NULLIF(MAX(CASE WHEN r.재차량 < 0 THEN 0 ELSE ROUND(r.재차량 / 19.0, 0) END) OVER (PARTITION BY r.노선명), 0)" +
            "    , 1) AS 상대혼잡도, " +
            "    b.위도, " +                                           // ARS+정류장명 복합 조인 기반 위도
            "    b.경도, " +                                           // ARS+정류장명 복합 조인 기반 경도
            "    ISNULL((" +                                           // 승차 총 승객수 서브쿼리
            "        SELECT SUM(CAST(승객수 AS INT)) " +
            "        FROM Analysis_Table_Final " +
            "        WHERE 노선명 = r.노선명 " +
            "        AND 승차_정류장ARS = r.ARS " +
            "        AND 승차_정류장ARS != '00000' " +
            "    ), 0) + " +
            "    ISNULL((" +                                           // 하차 총 승객수 서브쿼리
            "        SELECT SUM(CAST(승객수 AS INT)) " +
            "        FROM Analysis_Table_Final " +
            "        WHERE 노선명 = r.노선명 " +
            "        AND 하차_정류장ARS = r.ARS " +
            "        AND 하차_정류장ARS != '00000' " +
            "    ), 0) AS 총승객수 " +                                 // 승차 + 하차 합산 총 승객수
            "FROM 재차량계산 r " +
            "LEFT JOIN (" +                                            // ARS + 정류장명 복합 조인 서브쿼리
            "    SELECT " +
            "        SUBSTRING(정류장번호, 2, 5) AS ARS코드, " +        // BOM 제거 후 ARS 5자리 추출
            "        SUBSTRING(노드명, 2, LEN(노드명)) AS 정류장명, " + // BOM 제거 후 정류장명 추출
            "        맵핑좌표Y_F AS 위도, " +
            "        맵핑좌표X_F AS 경도 " +
            "    FROM master.dbo.Bus_Stop_Location " +
            "    WHERE 맵핑좌표Y_F IS NOT NULL " +
            "    AND 맵핑좌표X_F IS NOT NULL " +
            "    AND 맵핑좌표Y_F BETWEEN 37.0 AND 38.5 " +             // 한반도 위도 범위 (서울+경기 포함)
            "    AND 맵핑좌표X_F BETWEEN 126.0 AND 128.0 " +           // 한반도 경도 범위 (서울+경기 포함)
            ") b ON b.ARS코드 = r.ARS " +                              // ARS 코드 매칭
            "    AND b.정류장명 = r.정류장명 " +                        // 정류장명도 동시 매칭 → ARS 중복 완전 해결
            "ORDER BY r.순번";

        return jdbcTemplate.query(sql,
            (rs, rowNum) -> {

                int 재차량값 = rs.getInt("재차량");
                double 혼잡도 = rs.getDouble("상대혼잡도");

                // 혼잡도 등급 결정 (5단계)
                String 등급;
                if (혼잡도 < 20) {
                    등급 = "쾌적";                                     // 0~20%
                } else if (혼잡도 < 40) {
                    등급 = "여유";                                     // 20~40%
                } else if (혼잡도 < 60) {
                    등급 = "보통";                                     // 40~60%
                } else if (혼잡도 < 80) {
                    등급 = "혼잡";                                     // 60~80%
                } else {
                    등급 = "매우혼잡";                                 // 80~100%
                }

                String 색상 = getGradientColor(혼잡도);

                // 위도/경도 NULL 처리
                Double 위도 = rs.getObject("위도") != null ? rs.getDouble("위도") : null;
                Double 경도 = rs.getObject("경도") != null ? rs.getDouble("경도") : null;

                return new CongestionDto(
                    rs.getString("노선명"),                            // 노선명
                    rs.getInt("순번"),                                 // 정류장 순번
                    rs.getString("ARS"),                               // ARS 코드
                    rs.getString("정류장명"),                          // 정류장명
                    재차량값,                                           // 시간당 평균 재차량 (19시간 보정)
                    rs.getInt("최대재차량"),                           // 최대재차량
                    혼잡도,                                            // 상대혼잡도(%)
                    등급,                                              // 혼잡도 등급
                    색상,                                              // 혼잡도 색상
                    위도,                                              // 위도 (ARS+정류장명 복합 조인)
                    경도,                                              // 경도 (ARS+정류장명 복합 조인)
                    rs.getInt("총승객수")                              // 승차 + 하차 합산 총 승객수
                );
            },
            routeName, routeName);
    }

    /**
     * 혼잡도(0~100%)를 초록→연두→노랑→주황→빨강 그라데이션 HEX 색상으로 변환
     * 5구간 내에서 선형 보간 적용
     */
    private String getGradientColor(double 혼잡도) {
        double ratio = Math.max(0, Math.min(혼잡도 / 100.0, 1.0));

        int[][] colors = {
            {0,   255, 0},   // 0%   초록
            {128, 255, 0},   // 25%  연두
            {255, 255, 0},   // 50%  노랑
            {255, 128, 0},   // 75%  주황
            {255, 0,   0}    // 100% 빨강
        };

        double scaled = ratio * 4;
        int idx = (int) Math.min(scaled, 3);
        double t = scaled - idx;

        int r1 = colors[idx][0],   r2 = colors[idx + 1][0];
        int g1 = colors[idx][1],   g2 = colors[idx + 1][1];
        int b1 = colors[idx][2],   b2 = colors[idx + 1][2];

        int r = (int)(r1 + (r2 - r1) * t);
        int g = (int)(g1 + (g2 - g1) * t);
        int b = (int)(b1 + (b2 - b1) * t);

        return String.format("#%02X%02X%02X", r, g, b);
    }
}