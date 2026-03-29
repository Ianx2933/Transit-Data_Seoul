import requests
import folium
import pandas as pd
import os
from collections import defaultdict

# ──────────────────────────────────────────────
# 설정: 노선 목록 + 캐시 경로
# ──────────────────────────────────────────────
노선목록 = '333,343,345,440,N73,3217,3313,3314,3315,3420,3422,송파02'                          # 추가할 노선은 여기만 수정
캐시경로_혼잡도 = 'C:/data/cache_congestion.csv'   # 혼잡도 캐시 파일
캐시경로_od승차 = 'C:/data/cache_od_승차.csv'      # OD 승차 캐시 파일
캐시경로_od하차 = 'C:/data/cache_od_하차.csv'      # OD 하차 캐시 파일
강제갱신 = True                                    # True: 캐시 무시하고 API 재호출 (총승객수 추가됐으므로 갱신 필요)

# ──────────────────────────────────────────────
# 1. 혼잡도 데이터 로드 (캐시 우선)
# ──────────────────────────────────────────────
if not 강제갱신 and os.path.exists(캐시경로_혼잡도):
    print("혼잡도 캐시 로드 중...")
    df = pd.read_csv(캐시경로_혼잡도, dtype={'ARS': str})
    print(f"  → 캐시에서 {len(df)}개 정류장 로드 완료")
else:
    print("혼잡도 API 호출 중...")
    df = pd.DataFrame(requests.get(
        f'http://localhost:8080/api/congestion?routes={노선목록}'
    ).json())
    df = df.dropna(subset=['위도', '경도'])
    df.to_csv(캐시경로_혼잡도, index=False, encoding='utf-8-sig')
    print(f"  → {len(df)}개 정류장 로드 및 캐시 저장 완료")

전체최대재차량 = df['재차량'].max()

# ──────────────────────────────────────────────
# 2. OD 데이터 로드 (캐시 우선)
# ──────────────────────────────────────────────
if not 강제갱신 and os.path.exists(캐시경로_od승차) and os.path.exists(캐시경로_od하차):
    print("OD 캐시 로드 중...")
    승차df = pd.read_csv(캐시경로_od승차, dtype={'승차_정류장ARS': str})
    하차df = pd.read_csv(캐시경로_od하차, dtype={'하차_정류장ARS': str})
    print(f"  → 캐시에서 OD 로드 완료")
else:
    print("OD API 호출 중 (시간이 걸릴 수 있습니다)...")
    od_raw = requests.get('http://localhost:8080/api/od/all').json()
    승차df = pd.DataFrame(od_raw.get('승차', []))
    하차df = pd.DataFrame(od_raw.get('하차', []))
    승차df.to_csv(캐시경로_od승차, index=False, encoding='utf-8-sig')
    하차df.to_csv(캐시경로_od하차, index=False, encoding='utf-8-sig')
    print(f"  → OD 로드 및 캐시 저장 완료")

# OD 딕셔너리 변환 (빠른 조회를 위해)
print("OD 딕셔너리 변환 중...")
od_map = defaultdict(lambda: defaultdict(lambda: {'승차목적지': [], '하차출발지': []}))

for _, row in 승차df.iterrows():
    od_map[str(row['노선명'])][str(row['승차_정류장ARS'])]['승차목적지'].append({
        '정류장명': row['하차_정류장명'],
        '승객수': row['승객수']
    })

for _, row in 하차df.iterrows():
    od_map[str(row['노선명'])][str(row['하차_정류장ARS'])]['하차출발지'].append({
        '정류장명': row['승차_정류장명'],
        '승객수': row['승객수']
    })

print(f"  → OD 딕셔너리 변환 완료")

# ──────────────────────────────────────────────
# 3. 색상 함수
# ──────────────────────────────────────────────
def get_color(혼잡도):
    """혼잡도(0~100)를 초록→노랑→빨강 HEX 색상으로 변환"""
    ratio = max(0, min(혼잡도 / 100.0, 1.0))
    colors = [
        (0, 255, 0),    # 0%   초록
        (128, 255, 0),  # 25%  연두
        (255, 255, 0),  # 50%  노랑
        (255, 128, 0),  # 75%  주황
        (255, 0, 0)     # 100% 빨강
    ]
    scaled = ratio * 4
    idx = int(min(scaled, 3))
    t = scaled - idx
    r1, g1, b1 = colors[idx]
    r2, g2, b2 = colors[idx + 1]
    return f'#{int(r1+(r2-r1)*t):02X}{int(g1+(g2-g1)*t):02X}{int(b1+(b2-b1)*t):02X}'

# ──────────────────────────────────────────────
# 4. OD 팝업 HTML 생성 함수
# ──────────────────────────────────────────────
def get_popup_html(노선명, ARS, 정류장명, 상대혼잡도, 혼잡도등급, 재차량, 총승객수):
    """정류장 클릭 시 OD 데이터 포함 팝업 HTML 생성"""
    od = od_map.get(str(노선명), {}).get(str(ARS), {'승차목적지': [], '하차출발지': []})

    승차rows = ""
    for item in od['승차목적지']:
        승차rows += f"<tr><td style='padding:2px 6px;'>{item['정류장명']}</td><td style='padding:2px 6px; text-align:right;'>{item['승객수']}명</td></tr>"

    하차rows = ""
    for item in od['하차출발지']:
        하차rows += f"<tr><td style='padding:2px 6px;'>{item['정류장명']}</td><td style='padding:2px 6px; text-align:right;'>{item['승객수']}명</td></tr>"

    색상 = get_color(상대혼잡도)
    글자색 = "white" if 상대혼잡도 > 50 else "black"

    html = f"""
    <div style='font-family:Arial; width:320px; max-height:480px; overflow-y:auto;'>
        <div style='padding:8px 10px; background:{색상}; color:{글자색}; border-radius:4px 4px 0 0;'>
            <b>🚌 {노선명}번 버스</b>
        </div>
        <div style='padding:8px 10px; background:#f5f5f5; border:1px solid #ddd; border-top:none;'>
            <b>{정류장명}</b><br>
            혼잡도: <b>{상대혼잡도}%</b> ({혼잡도등급})<br>
            재차량: <b>{재차량}명</b> &nbsp;|&nbsp; 총승객수: <b>{총승객수}명</b>
        </div>
        <div style='padding:8px 10px; border:1px solid #ddd; border-top:none;'>
            <b>📤 여기서 타면 → 어디서 내리나</b>
            <table style='width:100%; border-collapse:collapse; font-size:12px; margin-top:4px;'>
                <tr style='background:#eee;'>
                    <th style='text-align:left; padding:2px 6px;'>하차 정류장</th>
                    <th style='text-align:right; padding:2px 6px;'>승객수</th>
                </tr>
                {승차rows if 승차rows else "<tr><td colspan='2' style='padding:4px 6px; color:#999;'>데이터 없음</td></tr>"}
            </table>
        </div>
        <div style='padding:8px 10px; border:1px solid #ddd; border-top:none; border-radius:0 0 4px 4px;'>
            <b>📥 어디서 타고 → 여기서 내리나</b>
            <table style='width:100%; border-collapse:collapse; font-size:12px; margin-top:4px;'>
                <tr style='background:#eee;'>
                    <th style='text-align:left; padding:2px 6px;'>승차 정류장</th>
                    <th style='text-align:right; padding:2px 6px;'>승객수</th>
                </tr>
                {하차rows if 하차rows else "<tr><td colspan='2' style='padding:4px 6px; color:#999;'>데이터 없음</td></tr>"}
            </table>
        </div>
    </div>
    """
    return html

# ──────────────────────────────────────────────
# 5. 지도 생성
# ──────────────────────────────────────────────
print("지도 생성 중...")
m = folium.Map(
    location=[37.5465, 127.0000],
    zoom_start=12,
    tiles='CartoDB positron'
)

# ──────────────────────────────────────────────
# 레이어 1: 노선별 독립 혼잡도 (노선별 레이어 분리)
# ──────────────────────────────────────────────
print("  노선별 독립 혼잡도 레이어 생성 중...")

for 노선 in df['노선명'].unique():
    print(f"    {노선}번 노선 처리 중...")
    layer = folium.FeatureGroup(
        name=f"🚌 {노선}번 - 노선별 혼잡도",
        show=True
    )
    노선df = df[df['노선명'] == 노선].sort_values('순번').reset_index(drop=True)

    # 구간 선 그리기
    for i in range(len(노선df) - 1):
        출발 = 노선df.loc[i]
        도착 = 노선df.loc[i + 1]
        folium.PolyLine(
            locations=[[출발['위도'], 출발['경도']], [도착['위도'], 도착['경도']]],
            color=출발['혼잡도색상'],
            weight=5,
            opacity=0.85,
            tooltip=f"{출발['노선명']}번 | {출발['정류장명']} → {도착['정류장명']} | {출발['상대혼잡도']}% ({출발['혼잡도등급']})"
        ).add_to(layer)

    # 정류장 마커
    for _, row in 노선df.iterrows():
        popup_html = get_popup_html(
            row['노선명'], row['ARS'], row['정류장명'],
            row['상대혼잡도'], row['혼잡도등급'], row['재차량'], row['총승객수']
        )
        folium.CircleMarker(
            location=[row['위도'], row['경도']],
            radius=5,
            color=row['혼잡도색상'],
            fill=True,
            fill_opacity=0.9,
            tooltip=f"{row['노선명']}번 | {row['정류장명']} ({row['상대혼잡도']}%)",
            popup=folium.Popup(popup_html, max_width=340)
        ).add_to(layer)

    layer.add_to(m)

# ──────────────────────────────────────────────
# 레이어 2: 전체 노선 통합 기준 혼잡도
# ──────────────────────────────────────────────
print("  전체 통합 혼잡도 레이어 생성 중...")
layer2 = folium.FeatureGroup(name="📊 전체 통합 기준 혼잡도", show=False)

for 노선 in df['노선명'].unique():
    노선df = df[df['노선명'] == 노선].sort_values('순번').reset_index(drop=True)
    for i in range(len(노선df) - 1):
        출발 = 노선df.loc[i]
        도착 = 노선df.loc[i + 1]
        통합혼잡도 = round(출발['재차량'] / 전체최대재차량 * 100, 1) if 전체최대재차량 > 0 else 0
        색상 = get_color(통합혼잡도)
        folium.PolyLine(
            locations=[[출발['위도'], 출발['경도']], [도착['위도'], 도착['경도']]],
            color=색상,
            weight=5,
            opacity=0.85,
            tooltip=f"{출발['노선명']}번 | {출발['정류장명']} | 통합혼잡도: {통합혼잡도}%"
        ).add_to(layer2)

for _, row in df.iterrows():
    통합혼잡도 = round(row['재차량'] / 전체최대재차량 * 100, 1) if 전체최대재차량 > 0 else 0
    색상 = get_color(통합혼잡도)
    popup_html = get_popup_html(
        row['노선명'], row['ARS'], row['정류장명'],
        통합혼잡도, row['혼잡도등급'], row['재차량'], row['총승객수']
    )
    folium.CircleMarker(
        location=[row['위도'], row['경도']],
        radius=5,
        color=색상,
        fill=True,
        fill_opacity=0.9,
        tooltip=f"{row['노선명']}번 | {row['정류장명']} (통합 {통합혼잡도}%)",
        popup=folium.Popup(popup_html, max_width=340)
    ).add_to(layer2)

layer2.add_to(m)

# ──────────────────────────────────────────────
# 레이어 3: 정류장 단위 승객 밀집도
# ──────────────────────────────────────────────
print("  정류장 밀집도 레이어 생성 중...")
layer3 = folium.FeatureGroup(name="📊 정류장 단위 승객 밀집도", show=False)

밀집도df = df.groupby(['ARS', '정류장명', '위도', '경도'])['재차량'].sum().reset_index()
밀집도df.columns = ['ARS', '정류장명', '위도', '경도', '총재차량']
최대밀집도 = 밀집도df['총재차량'].max()

for _, row in 밀집도df.iterrows():
    밀집도 = round(row['총재차량'] / 최대밀집도 * 100, 1) if 최대밀집도 > 0 else 0
    색상 = get_color(밀집도)
    크기 = max(4, int(밀집도 / 8) + 4)
    folium.CircleMarker(
        location=[row['위도'], row['경도']],
        radius=크기,
        color=색상,
        fill=True,
        fill_opacity=0.8,
        tooltip=f"{row['정류장명']} | 총재차량: {row['총재차량']}명 | 밀집도: {밀집도}%",
        popup=folium.Popup(
            f"<div style='font-family:Arial; padding:8px;'>"
            f"<b>{row['정류장명']}</b><br>"
            f"ARS: {row['ARS']}<br>"
            f"전 노선 합산 재차량: <b>{row['총재차량']}명</b><br>"
            f"밀집도: <b>{밀집도}%</b>"
            f"</div>",
            max_width=250
        )
    ).add_to(layer3)

layer3.add_to(m)

# ──────────────────────────────────────────────
# 6. 레이어 컨트롤 + 범례
# ──────────────────────────────────────────────
folium.LayerControl(collapsed=False).add_to(m)

legend_html = """
<div style='position:fixed; bottom:30px; left:30px; z-index:1000;
     background:white; padding:12px 16px; border-radius:8px;
     border:2px solid #ccc; font-family:Arial; font-size:13px; line-height:1.8;
     box-shadow: 2px 2px 6px rgba(0,0,0,0.2);'>
    <b>🎨 혼잡도 범례</b><br>
    <span style='color:#00CC00; font-size:16px;'>●</span> 쾌적 (0~20%)<br>
    <span style='color:#80CC00; font-size:16px;'>●</span> 여유 (20~40%)<br>
    <span style='color:#CCCC00; font-size:16px;'>●</span> 보통 (40~60%)<br>
    <span style='color:#CC6600; font-size:16px;'>●</span> 혼잡 (60~80%)<br>
    <span style='color:#CC0000; font-size:16px;'>●</span> 매우혼잡 (80~100%)
</div>
"""
m.get_root().html.add_child(folium.Element(legend_html))

# ──────────────────────────────────────────────
# 7. 저장
# ──────────────────────────────────────────────
output_path = 'C:/data/congestion_map.html'
m.save(output_path)
print(f"\n✅ 완료! {output_path}")
print(f"   노선 추가: 상단 '노선목록' 변수에 쉼표로 추가 후 강제갱신=True")
print(f"   지도만 재생성: 강제갱신=False (캐시 사용, 빠름)")