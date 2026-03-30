package com.example.demo.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class CongestionDto {
    private String 노선명;       // 버스 노선 번호 (예: 143)
    private int 순번;            // 노선 내 정류장 순번
    private String ARS;          // 정류장 ARS 코드 5자리
    private String 정류장명;      // 정류장 이름
    private int 재차량;          // 시간당 평균 재차량 (하루 누적 / 19시간)
    private int 최대재차량;       // 노선 전체 구간 중 최대 재차량 (혼잡도 기준점)
    private double 상대혼잡도;    // 재차량 / 최대재차량 × 100 (%)
    private String 혼잡도등급;    // 쾌적/여유/보통/혼잡/매우혼잡
    private String 혼잡도색상;    // Tableau/Folium 시각화용 HEX 색상코드
    private Double 위도;          // 정류장 위도 (Bus_Stop_Location 조인)
    private Double 경도;          // 정류장 경도 (Bus_Stop_Location 조인)
    private int 총승객수;         // 해당 정류장 승차 + 하차 합산 총 승객수
}