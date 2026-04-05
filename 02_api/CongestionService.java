package com.example.demo.service;

import com.example.demo.dto.CongestionDto;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CongestionService {

    private final JdbcTemplate jdbcTemplate;
    // JPA Repository 대신 JdbcTemplate을 쓰는 이유:
    // CTE(WITH절) + 윈도우 함수(SUM OVER)가 포함된 복잡한 SQL은
    // JPA @Query로 처리하기 어렵기 때문에 JdbcTemplate으로 직접 실행

    /**
     * 노선명과 날짜를 받아서 해당 노선의 정류장별 재차량과 혼잡도를 계산하여 반환
     * 날짜 파라미터 추가로 날짜별 혼잡도 비교 가능
     * 위경도 조인 방식: ARS코드 + 정류장명 복합 조인
     * → 표준코드 오입력 문제 해결 + ARS 코드 중복 문제 동시 해결
     * → 서울/경기 광역 노선 모두 지원
     * 재차량은 19시간(04~23시) 기준 시간당 평균으로 보정
     * 총승객수는 승차 + 하차 합산
     * @param routeName 조회할 노선 번호 (예: "422")
     * @param date      조회할 날짜 (예: "20231017")
     * @return 정류장별 혼잡도 + 위경도 + 총승객수 정보 목록
     */
    public List<CongestionDto> getCongestionByRoute(String routeName, String date) {

        String sql =

            "WITH 순승객 AS (" +

            // 승차 데이터: 승차 정류장순번 기준으로 승객수 합산 (양수)
            "    SELECT 노선명, CAST(승차_정류장순번 AS INT) AS 순번, " +
            "        승차_정류장ARS AS ARS, 승차_정류장명 AS 정류장명, " +
            "        SUM(CAST(승객수 AS INT)) AS 순승객수 " +
            "    FROM Analysis_Table_Final " +
            "    WHERE 노선명 = ? " +
            "    AND 기준일자 = ? " +
            "    AND 승차_정류장ARS != '00000' " +
            "    GROUP BY 노선명, 승차_정류장순번, 승차_정류장ARS, 승차_정류장명 " +

            "    UNION ALL " +

            // 하차 데이터: 하차 정류장순번 기준으로 승객수 합산 (음수)
            "    SELECT 노선명, CAST(하차_정류장순번 AS INT) AS 순번, " +
            "        하차_정류장ARS AS ARS, 하차_정류장명 AS 정류장명, " +
            "        -SUM(CAST(승객수 AS INT)) AS 순승객수 " +
            "    FROM Analysis_Table_Final " +
            "    WHERE 노선명 = ? " +
            "    AND 기준일자 = ? " +
            "    AND 하차_정류장ARS != '00000' " +
            "    GROUP BY 노선명, 하차_정류장순번, 하차_정류장ARS, 하차_정류장명 " +
            "), " +

            // 2단계 CTE: 같은 순번의 승차/하차를 합산하여 정류장별 순승객 계산
            "정류장별합산 AS (" +
            "    SELECT 노선명, 순번, ARS, 정류장명, " +
            "        SUM(순승객수) AS 정류장순승객 " +
            "    FROM 순승객 " +
            "    GROUP BY 노선명, 순번, ARS, 정류장명 " +
            "), " +

            // 3단계 CTE: 순번 순서대로 누적 합산하여 재차량 계산
            "재차량계산 AS (" +
            "    SELECT 노선명, 순번, ARS, 정류장명, 정류장순승객, " +
            "        SUM(정류장순승객) OVER (" +
            "            PARTITION BY 노선명 " +
            "            ORDER BY 순번 " +
            "            ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW" +
            "        ) AS 재차량 " +
            "    FROM 정류장별합산 " +
            ") " +

            // 최종 SELECT: 재차량 기반 혼잡도 + ARS+정류장명 복합 조인으로 위경도 매칭
            "SELECT r.노선명, r.순번, r.ARS, r.정류장명, " +
            "    CASE WHEN r.재차량 < 0 THEN 0 ELSE ROUND(r.재차량 / 19.0, 0) END AS 재차량, " +
            "    MAX(CASE WHEN r.재차량 < 0 THEN 0 ELSE ROUND(r.재차량 / 19.0, 0) END) OVER (PARTITION BY r.노선명) AS 최대재차량, " +
            "    ROUND(" +
            "        CASE WHEN r.재차량 < 0 THEN 0 ELSE ROUND(r.재차량 / 19.0, 0) END * 100.0 " +
            "        / NULLIF(MAX(CASE WHEN r.재차량 < 0 THEN 0 ELSE ROUND(r.재차량 / 19.0, 0) END) OVER (PARTITION BY r.노선명), 0)" +
            "    , 1) AS 상대혼잡도, " +
            "    b.위도, " +
            "    b.경도, " +
            "    ISNULL((" +
            "        SELECT SUM(CAST(승객수 AS INT)) " +
            "        FROM Analysis_Table_Final " +
            "        WHERE 노선명 = r.노선명 " +
            "        AND 기준일자 = ? " +
            "        AND 승차_정류장ARS = r.ARS " +
            "        AND 승차_정류장ARS != '00000' " +
            "    ), 0) + " +
            "    ISNULL((" +
            "        SELECT SUM(CAST(승객수 AS INT)) " +
            "        FROM Analysis_Table_Final " +
            "        WHERE 노선명 = r.노선명 " +
            "        AND 기준일자 = ? " +
            "        AND 하차_정류장ARS = r.ARS " +
            "        AND 하차_정류장ARS != '00000' " +
            "    ), 0) AS 총승객수 " +
            "FROM 재차량계산 r " +
            "LEFT JOIN (" +
            "    SELECT " +
            "        SUBSTRING(정류장번호, 2, 5) AS ARS코드, " +
            "        SUBSTRING(노드명, 2, LEN(노드명)) AS 정류장명, " +
            "        맵핑좌표Y_F AS 위도, " +
            "        맵핑좌표X_F AS 경도 " +
            "    FROM master.dbo.Bus_Stop_Location " +
            "    WHERE 맵핑좌표Y_F IS NOT NULL " +
            "    AND 맵핑좌표X_F IS NOT NULL " +
            "    AND 맵핑좌표Y_F BETWEEN 37.0 AND 38.5 " +
            "    AND 맵핑좌표X_F BETWEEN 126.0 AND 128.0 " +
            ") b ON b.ARS코드 = r.ARS " +
            "    AND b.정류장명 = r.정류장명 " +
            "ORDER BY r.순번";

        return jdbcTemplate.query(sql,
            (rs, rowNum) -> {

                int 재차량값 = rs.getInt("재차량");
                double 혼잡도 = rs.getDouble("상대혼잡도");

                String 등급;
                if (혼잡도 < 20) {
                    등급 = "쾌적";
                } else if (혼잡도 < 40) {
                    등급 = "여유";
                } else if (혼잡도 < 60) {
                    등급 = "보통";
                } else if (혼잡도 < 80) {
                    등급 = "혼잡";
                } else {
                    등급 = "매우혼잡";
                }

                String 색상 = getGradientColor(혼잡도);

                Double 위도 = rs.getObject("위도") != null ? rs.getDouble("위도") : null;
                Double 경도 = rs.getObject("경도") != null ? rs.getDouble("경도") : null;

                return new CongestionDto(
                    rs.getString("노선명"),
                    rs.getInt("순번"),
                    rs.getString("ARS"),
                    rs.getString("정류장명"),
                    재차량값,
                    rs.getInt("최대재차량"),
                    혼잡도,
                    등급,
                    색상,
                    위도,
                    경도,
                    rs.getInt("총승객수")
                );
            },
            routeName, date, routeName, date, date, date);
    }

    /**
     * 특정 노선의 정류장명 목록 조회 (자동완성용)
     * 순번 순서대로 반환하여 노선 흐름대로 표시
     * @param routeName 조회할 노선 번호
     * @param date      조회할 날짜
     * @return 정류장명 목록 (순번 순)
     */
    public List<String> getStopsByRoute(String routeName, String date) {
        String sql =
            "SELECT 승차_정류장명 " +
            "FROM Analysis_Table_Final " +
            "WHERE 노선명 = ? " +
            "AND 기준일자 = ? " +
            "AND 승차_정류장ARS != '00000' " +
            "GROUP BY 승차_정류장명, 승차_정류장순번 " +
            "ORDER BY MIN(승차_정류장순번) ASC"; // 순번 순서대로 정렬

        return jdbcTemplate.queryForList(sql, String.class, routeName, date);
    }

    /**
     * 출발-도착 구간 혼잡도 조회
     * 기존 getCongestionByRoute 결과에서 구간만 필터링
     * @param routeName 노선명
     * @param from      출발 정류장명
     * @param to        도착 정류장명
     * @param date      조회할 날짜
     * @return 구간 내 정류장별 혼잡도 목록
     */
    public List<CongestionDto> getSectionCongestion(
            String routeName, String from, String to, String date) {

        // 전체 노선 데이터 가져오기 (기존 로직 재사용)
        List<CongestionDto> allStops = getCongestionByRoute(routeName, date);

        // 출발-도착 구간 필터링
        boolean 구간시작 = false;
        List<CongestionDto> section = new ArrayList<>();

        for (CongestionDto stop : allStops) {
            if (stop.get정류장명().equals(from)) {
                구간시작 = true; // 출발 정류장부터 포함
            }
            if (구간시작) {
                section.add(stop);
            }
            if (구간시작 && stop.get정류장명().equals(to)) {
                break; // 도착 정류장에서 종료
            }
        }

        return section;
    }

    /**
     * 혼잡도(0~100%)를 초록→연두→노랑→주황→빨강 그라데이션 HEX 색상으로 변환
     */
    private String getGradientColor(double 혼잡도) {
        double ratio = Math.max(0, Math.min(혼잡도 / 100.0, 1.0));

        int[][] colors = {
            {0,   255, 0},
            {128, 255, 0},
            {255, 255, 0},
            {255, 128, 0},
            {255, 0,   0}
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
