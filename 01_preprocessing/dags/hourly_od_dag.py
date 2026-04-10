from airflow import DAG
from airflow.operators.python import PythonOperator
from datetime import datetime, timedelta
import requests
import psycopg2
import pendulum

# ============================================================
# 설정
# ============================================================
API_KEY = "47764c6d7273746134356745597371"
DB_CONN = {
    "host": "seouldb",
    "port": 5432,
    "dbname": "Seoul_Transit",
    "user": "postgres",
    "password": "330218"
}

KST = pendulum.timezone("Asia/Seoul")

# ============================================================
# 시간대별 승하차 데이터 수집 및 적재
# ============================================================
def collect_hourly_od(**context):
    # 전월 년월 계산
    execution_date = context["execution_date"].in_timezone(KST)
    first_day = execution_date.replace(day=1)
    last_month = first_day - timedelta(days=1)
    target_ym = last_month.strftime("%Y%m")
    print(f"[정보] 수집 대상 년월: {target_ym}")

    url = f"http://openapi.seoul.go.kr:8088/{API_KEY}/json/CardBusTimeStatisticsServiceNew/1/1000/{target_ym}"

    response = requests.get(url, timeout=30)
    data = response.json()

    # API 응답 확인
    if "CardBusTimeStatisticsServiceNew" not in data:
        print(f"[경고] API 응답 없음: {data}")
        return

    rows = data["CardBusTimeStatisticsServiceNew"]["row"]
    print(f"[정보] 수집된 행 수: {len(rows)}")

    # DB 적재
    conn = psycopg2.connect(**DB_CONN)
    cursor = conn.cursor()

    for row in rows:
        cursor.execute("""
            INSERT INTO hourly_od_data (
                기준년월, 노선번호, 노선명,
                버스정류장ARS번호, 역명,
                시간대, 승차인원, 하차인원
            ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
            ON CONFLICT DO NOTHING
        """, (
            row.get("USE_MON"),
            row.get("LINE_NUM"),
            row.get("LINE_NM"),
            row.get("BSST_ARS_NO"),
            row.get("STATION_NM"),
            int(row.get("HHMM", 0)),
            int(row.get("RIDE_PASGR_NUM", 0)),
            int(row.get("ALIGHT_PASGR_NUM", 0))
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
    dag_id="hourly_od_collection",
    default_args=default_args,
    description="서울 버스 시간대별 승하차 데이터 수집",
    schedule_interval="0 3 1 * *",  # 매월 1일 한국시간 새벽 3시
    start_date=datetime(2026, 4, 10, tzinfo=KST),
    catchup=False,
    tags=["seoul", "bus", "hourly"],
) as dag:

    collect_task = PythonOperator(
        task_id="collect_hourly_od",
        python_callable=collect_hourly_od,
        provide_context=True,
    )