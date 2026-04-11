from flask import Flask, request, jsonify
import joblib
import pandas as pd
import psycopg2
from dotenv import load_dotenv
import os

load_dotenv()

app = Flask(__name__)

# ============================================================
# 모델 및 매핑 로드
# 서버 시작 시 한 번만 로드하여 메모리에 유지
# ============================================================
BASE_DIR  = os.path.dirname(os.path.abspath(__file__))
MODEL_DIR = os.path.join(BASE_DIR, '..', '04_analysis')

model         = joblib.load(os.path.join(MODEL_DIR, 'congestion_model.pkl'))  # XGBoost 모델
노선코드_맵   = joblib.load(os.path.join(MODEL_DIR, 'route_code_map.pkl'))    # 노선번호 → 노선코드 매핑
정류장코드_맵 = joblib.load(os.path.join(MODEL_DIR, 'stop_code_map.pkl'))     # ARS번호 → 정류장코드 매핑
df_ratio      = pd.read_csv(os.path.join(MODEL_DIR, 'hourly_ratio.csv'),
                             dtype={'버스정류장ars번호': str})                  # 문자열 타입으로 로드 (앞의 0 보존)

# hourly_ratio.csv의 ARS번호 앞의 0 제거하여 정규화
df_ratio['버스정류장ars번호'] = df_ratio['버스정류장ars번호'].str.lstrip('0')

# DB 연결 정보 (환경변수 우선, 기본값은 Docker 컨테이너 내부 주소)
DB_CONN = {
    "host":     os.environ.get("DB_HOST", "seouldb"),
    "port":     int(os.environ.get("DB_PORT", 5432)),
    "dbname":   os.environ.get("DB_NAME", "Seoul_Transit"),
    "user":     os.environ.get("DB_USER", "postgres"),
    "password": os.environ.get("DB_PASSWORD", "330218")
}

# 새벽 노선: 패턴 단순 → 가중치 적용 제외
새벽노선 = ['8541', '8146', '8641']

# 요일타입 코드 → 이름 매핑
요일타입명_맵 = {0: '평일', 1: '토요일', 2: '일요일', 3: '공휴일'}


# ============================================================
# DB 연결 헬퍼
# 매 요청마다 새 연결 생성 후 사용 완료 시 닫기
# ============================================================
def get_db_connection():
    return psycopg2.connect(**DB_CONN)


# ============================================================
# 헬스체크
# Spring Boot에서 Flask 서비스 정상 여부 확인용
# ============================================================
@app.route('/health', methods=['GET'])
def health():
    return jsonify({"status": "ok"})


# ============================================================
# 시간대별 예측 API
# Spring Boot → Flask 호출
# 일별 XGBoost 예측 × 시간대 비율 = 시간대별 승차/하차 예측
# ============================================================
@app.route('/predict', methods=['POST'])
def predict():
    try:
        data = request.json
        노선번호    = data['routeNo']
        ARS번호     = data['arsNo']
        요일타입코드 = int(data['dayType'])    # 0:평일 1:토요일 2:일요일 3:공휴일
        월          = int(data['month'])
        전주승객수   = float(data['prevWeek'])  # 7일 전 동일 정류장 승객수 (lag feature)
        전월승객수   = float(data['prevMonth']) # 30일 전 동일 정류장 승객수 (lag feature)

        # 노선코드, 정류장코드 매핑 확인
        if 노선번호 not in 노선코드_맵:
            return jsonify({"error": f"노선번호 {노선번호} 없음"}), 400
        if ARS번호 not in 정류장코드_맵:
            return jsonify({"error": f"ARS번호 {ARS번호} 없음"}), 400

        노선코드  = 노선코드_맵[노선번호]
        정류장코드 = 정류장코드_맵[ARS번호]

        # XGBoost로 일별 총 승객수 예측
        X_pred = pd.DataFrame([{
            '요일타입코드': 요일타입코드,
            '월':          월,
            '노선코드':    노선코드,
            '정류장코드':  정류장코드,
            '전주승객수':  전주승객수,
            '전월승객수':  전월승객수
        }])
        일별예측 = float(model.predict(X_pred)[0])

        # hourly_ratio.csv에서 정류장별 시간대 비율 가져오기
        # 5시, 23시는 교통카드 정산 시스템 특성상 이상치 → 수집 시 제외됨
        # ARS번호 앞의 0 제거하여 CSV 데이터와 매칭
        ARS번호_정규화 = ARS번호.lstrip('0')
        비율     = df_ratio[df_ratio['버스정류장ars번호'] == ARS번호_정규화].set_index('시간대')
        승차비율 = 비율['승차비율'].reindex(range(24), fill_value=0)
        하차비율 = 비율['하차비율'].reindex(range(24), fill_value=0)

        # 요일타입별 가중치 적용 (새벽 노선 제외)
        # 가중치는 DB time_weight_config 테이블에서 동적으로 조회
        # → STCIS 데이터 확인 후 /weight/update API로 수정 가능
        if 노선번호 not in 새벽노선:
            요일타입명 = 요일타입명_맵[요일타입코드]
            가중치 = get_weights(요일타입명)
            if 가중치:
                승차비율, 하차비율 = apply_weights(승차비율, 하차비율, 가중치)

        # daily_od_data 실측치 기반 하차/승차 비율 계산
        하차배율 = get_alighting_ratio(노선번호, ARS번호)

        # 시간대별 승차/하차 예측값 계산
        hourly = [
            {
                "hour":      hour,
                "boarding":  round(승차비율.get(hour, 0) / 100 * 일별예측, 1),
                "alighting": round(하차비율.get(hour, 0) / 100 * (일별예측 * 하차배율), 1)
            }
            for hour in range(24)
        ]

        return jsonify({
            "dailyPrediction":  round(일별예측, 1),
            "hourlyPrediction": hourly
        })

    except Exception as e:
        return jsonify({"error": str(e)}), 500


# ============================================================
# 가중치 적용 및 정규화
# 가중치 적용 후 합이 100이 되도록 정규화
# ============================================================
def apply_weights(승차비율, 하차비율, 가중치):
    weight_series = pd.Series(가중치).reindex(range(24), fill_value=1.0)
    승차비율 = 승차비율 * weight_series
    하차비율 = 하차비율 * weight_series

    총합승차 = 승차비율.sum()
    총합하차 = 하차비율.sum()

    if 총합승차 > 0:
        승차비율 = 승차비율 / 총합승차 * 100
    if 총합하차 > 0:
        하차비율 = 하차비율 / 총합하차 * 100

    return 승차비율, 하차비율


# ============================================================
# 가중치 조회
# DB time_weight_config 테이블에서 요일타입별 가중치 조회
# 가중치 없으면 None 반환 → 원본 비율 그대로 사용
# ============================================================
def get_weights(요일타입명):
    try:
        conn = get_db_connection()
        cursor = conn.cursor()
        cursor.execute(
            "SELECT 시간대, 가중치 FROM time_weight_config WHERE 요일타입 = %s",
            (요일타입명,)
        )
        rows = cursor.fetchall()
        cursor.close()
        conn.close()
        return {row[0]: row[1] for row in rows} if rows else None
    except Exception:
        return None


# ============================================================
# 하차 비율 계산
# daily_od_data 실측치 기반으로 정류장별 하차/승차 비율 계산
# 데이터 없으면 1:1 비율 반환
# ============================================================
def get_alighting_ratio(노선번호, ARS번호):
    try:
        conn = get_db_connection()
        cursor = conn.cursor()
        cursor.execute("""
            SELECT SUM(하차총승객수), SUM(승차총승객수)
            FROM daily_od_data
            WHERE 노선번호 = %s AND 버스정류장ars번호 = %s
        """, (노선번호, ARS번호))
        row = cursor.fetchone()
        cursor.close()
        conn.close()
        if row and row[1] and row[1] > 0:
            return row[0] / row[1]
        return 1.0
    except Exception:
        return 1.0


# ============================================================
# 가중치 업데이트 API
# STCIS 데이터 확인 후 요일타입별 시간대 가중치 수정
# Spring Boot PUT /api/prediction/weight → Flask POST /weight/update
# ============================================================
@app.route('/weight/update', methods=['POST'])
def update_weight():
    try:
        data     = request.json
        요일타입 = data['dayType']  # 평일/토요일/일요일/공휴일
        시간대   = int(data['hour'])
        가중치   = float(data['weight'])

        conn = get_db_connection()
        cursor = conn.cursor()
        # 이미 존재하면 UPDATE, 없으면 INSERT
        cursor.execute("""
            INSERT INTO time_weight_config (요일타입, 시간대, 가중치)
            VALUES (%s, %s, %s)
            ON CONFLICT (요일타입, 시간대)
            DO UPDATE SET 가중치 = %s, updated_at = NOW()
        """, (요일타입, 시간대, 가중치, 가중치))
        conn.commit()
        cursor.close()
        conn.close()

        return jsonify({"success": True})

    except Exception as e:
        return jsonify({"error": str(e)}), 500


if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000, debug=False)