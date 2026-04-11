from airflow import DAG
from airflow.operators.python import PythonOperator
from datetime import datetime, timedelta
from dotenv import load_dotenv
import os
import requests
import psycopg2
import pendulum

load_dotenv()

# ============================================================
# 설정
# ============================================================
API_KEY = os.environ.get("SEOUL_DAILY_API_KEY", "")
DB_CONN = {
    "host": "seouldb",
    "port": 5432,
    "dbname": "Seoul_Transit",
    "user": "postgres",
    "password": "330218"
}

KST = pendulum.timezone("Asia/Seoul")

# ============================================================
# 일별 승하차 데이터 수집 및 적재
# ============================================================
def collect_daily_od(**context):
    # 전날 날짜 계산
    target_date = (context["execution_date"].in_timezone(KST) - timedelta(days=1)).strftime("%Y%m%d")
    print(f"[정보] 수집 대상 날짜: {target_date}")

    url = f"http://openapi.seoul.go.kr:8088/{API_KEY}/json/CardBusStatisticsServiceNew/1/1000/{target_date}"

    response = requests.get(url, timeout=30)
    data = response.json()

    # API 응답 확인
    if "CardBusStatisticsServiceNew" not in data:
        print(f"[경고] API 응답 없음: {data}")
        return

    rows = data["CardBusStatisticsServiceNew"]["row"]
    print(f"[정보] 수집된 행 수: {len(rows)}")

    # DB 적재
    conn = psycopg2.connect(**DB_CONN)
    cursor = conn.cursor()

    for row in rows:
        cursor.execute("""
            INSERT INTO daily_od_data (
                기준일자, 노선번호, 노선명,
                표준버스정류장ID, 버스정류장ARS번호, 역명,
                승차총승객수, 하차총승객수
            ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
            ON CONFLICT DO NOTHING
        """, (
            row.get("USE_YMD"),
            row.get("RTE_NO"),
            row.get("RTE_NM"),
            row.get("STOPS_ID"),
            row.get("STOPS_ARS_NO"),
            row.get("SBWY_STNS_NM"),
            int(row.get("GTON_TNOPE", 0)),
            int(row.get("GTOFF_TNOPE", 0))
        ))

    conn.commit()
    cursor.close()
    conn.close()
    print(f"[정보] DB 적재 완료: {len(rows)}건")


# ============================================================
# DAG 정의
# ============================================================
default_args = {
    "owner": "airflow",
    "retries": 1,
    "retry_delay": timedelta(minutes=5),
}

with DAG(
    dag_id="daily_od_collection",
    default_args=default_args,
    description="서울 버스 일별 승하차 데이터 수집",
    schedule_interval="0 2 * * *",  # 매일 한국시간 새벽 2시
    start_date=datetime(2026, 4, 10, tzinfo=KST),
    catchup=False,
    tags=["seoul", "bus", "daily"],
) as dag:

    collect_task = PythonOperator(
        task_id="collect_daily_od",
        python_callable=collect_daily_od,
        provide_context=True,
    )