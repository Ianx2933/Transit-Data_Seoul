from airflow import DAG
from airflow.providers.http.operators.http import HttpOperator
from datetime import datetime, timedelta

default_args = {
    'owner': 'airflow',
    'retries': 1,
    'retry_delay': timedelta(minutes=5),
}

with DAG(
    dag_id='bus_preprocessing',
    default_args=default_args,
    description='버스 데이터 전처리 파이프라인',
    schedule='0 3 * * *',
    start_date=datetime(2026, 4, 1),
    catchup=False,
) as dag:

    fix_virtual = HttpOperator(
        task_id='fix_virtual_stop',
        http_conn_id='seoul_transit_api',
        endpoint='/api/busstop/fix-virtual-stop?date={{ ds_nodash }}',
        method='GET',
        log_response=True,
        response_check=lambda response: response.status_code in [200, 201],
        extra_options={'timeout': 300},
    )

    fix_sequence = HttpOperator(
        task_id='fix_sequence',
        http_conn_id='seoul_transit_api',
        endpoint='/api/busstop/fix-sequence?date={{ ds_nodash }}',
        method='GET',
        log_response=True,
        response_check=lambda response: response.status_code in [200, 201],
        extra_options={'timeout': 300},
    )

    fix_same_od = HttpOperator(
        task_id='fix_same_stop_od',
        http_conn_id='seoul_transit_api',
        endpoint='/api/busstop/fix-same-stop-od?date={{ ds_nodash }}',
        method='GET',
        log_response=True,
        response_check=lambda response: response.status_code in [200, 201],
        extra_options={'timeout': 300},
    )

    deduplicate = HttpOperator(
        task_id='deduplicate',
        http_conn_id='seoul_transit_api',
        endpoint='/api/busstop/deduplicate?date={{ ds_nodash }}',
        method='GET',
        log_response=True,
        response_check=lambda response: response.status_code in [200, 201],
        extra_options={'timeout': 300},
    )

    fix_virtual >> fix_sequence >> fix_same_od >> deduplicate