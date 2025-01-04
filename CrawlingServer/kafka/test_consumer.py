from confluent_kafka import Consumer
import json
from datetime import datetime

conf = {
    'bootstrap.servers': 'localhost:9092',
    'group.id': 'my-group',
    'auto.offset.reset': 'earliest'
}


def format_message(msg_value):
    """메시지를 보기 좋게 포맷팅"""
    payload = msg_value.get('payload', {})

    # 변경 타입 매핑
    op_types = {
        'c': 'INSERT',
        'u': 'UPDATE',
        'd': 'DELETE',
        'r': 'READ'
    }

    # after 또는 before 데이터 가져오기
    data = payload.get('after') or payload.get('before') or {}
    op_type = op_types.get(payload.get('op', ''), 'UNKNOWN')

    # 타임스탬프 변환
    ts_ms = payload.get('ts_ms')
    timestamp = datetime.fromtimestamp(ts_ms / 1000).strftime('%Y-%m-%d %H:%M:%S') if ts_ms else 'N/A'

    formatted_msg = f"""
{'=' * 50}
Timestamp: {timestamp}
Operation: {op_type}
{'=' * 50}
Title: {data.get('title', 'N/A')}
Content: {data.get('content', 'N/A')[:100]}{'...' if len(data.get('content', '')) > 100 else ''}
Link: {data.get('stored_date', 'N/A')}
Stored Date: {data.get('stored_date', 'N/A')}
{'=' * 50}
"""
    return formatted_msg


consumer = Consumer(conf)
consumer.subscribe(['news.public.articles'])

print("Waiting for messages...")

try:
    while True:
        msg = consumer.poll(1.0)
        if msg is None:
            continue
        if msg.error():
            print(f"Consumer error: {msg.error()}")
            continue

        try:
            value = json.loads(msg.value().decode('utf-8'))
            print(format_message(value))
        except Exception as e:
            print(f"Error processing message: {e}")

except KeyboardInterrupt:
    print("\nClosing consumer...")
finally:
    consumer.close()