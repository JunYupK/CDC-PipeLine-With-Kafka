from confluent_kafka import Consumer
import json


def create_kafka_consumer():
    # Consumer 설정
    config = {
        'bootstrap.servers': 'localhost:9092',  # Kafka 브로커 주소
        'group.id': 'news-consumer-group',  # Consumer 그룹 ID
        'auto.offset.reset': 'earliest'  # 처음부터 메시지 읽기
    }

    return Consumer(config)


def process_message(msg):
    try:
        # 메시지 디코딩 및 JSON 파싱
        message_data = json.loads(msg.value().decode('utf-8'))

        # Debezium 메시지 구조에서 'after' 필드에 실제 데이터가 있음
        if 'payload' in message_data and 'after' in message_data['payload']:
            article_data = message_data['payload']['after']
            # title 출력
            if 'title' in article_data:
                print(f"Title: {article_data['title']}")

    except json.JSONDecodeError as e:
        print(f"JSON 파싱 에러: {e}")
    except Exception as e:
        print(f"메시지 처리 중 에러 발생: {e}")


def main():
    # Consumer 생성
    consumer = create_kafka_consumer()

    try:
        # 토픽 구독
        consumer.subscribe(['news.public.articles'])

        print("메시지 대기 중...")

        while True:
            # 메시지 폴링 (1초 타임아웃)
            msg = consumer.poll(1.0)

            if msg is None:
                continue

            if msg.error():
                print(f"Consumer 에러: {msg.error()}")
                continue

            # 메시지 처리
            process_message(msg)

    except KeyboardInterrupt:
        print("\nConsumer 종료...")
    finally:
        # Consumer 종료 및 정리
        consumer.close()


if __name__ == "__main__":
    main()