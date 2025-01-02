from kafka import KafkaProducer
import json
from pathlib import Path
import time


class NaverNewsKafkaProducer:
    def __init__(self, bootstrap_servers=['localhost:9092']):
        self.producer = KafkaProducer(
            bootstrap_servers=bootstrap_servers,
            value_serializer=lambda x: json.dumps(x).encode('utf-8'),
            acks='all'
        )

    def read_and_send_json(self, json_file_path: str, topic: str):
        try:
            # JSON 파일 읽기
            with open(json_file_path, 'r', encoding='utf-8') as file:
                news_data = json.load(file)

            # 데이터가 리스트인 경우 각 항목을 개별적으로 전송
            if isinstance(news_data, list):
                for article in news_data:
                    # 타임스탬프 추가
                    article['timestamp'] = int(time.time() * 1000)  # 현재 시간을 밀리초로

                    # Kafka로 전송
                    future = self.producer.send(topic, value=article)
                    # 전송 결과 확인
                    result = future.get(timeout=60)
                    print(f"메시지 전송 완료: partition={result.partition}, offset={result.offset}")

            # 모든 메시지가 전송되었는지 확인
            self.producer.flush()
            print("모든 메시지 전송 완료")

        except FileNotFoundError:
            print(f"파일을 찾을 수 없습니다: {json_file_path}")
        except json.JSONDecodeError:
            print(f"잘못된 JSON 형식입니다: {json_file_path}")
        except Exception as e:
            print(f"에러 발생: {str(e)}")

    def close(self):
        self.producer.close()


def main():
    # 데이터 디렉토리 경로 설정
    data_dir = Path("data")

    # Kafka 프로듀서 생성
    producer = NewsKafkaProducer()

    try:
        # data 디렉토리의 모든 JSON 파일 처리
        for json_file in data_dir.glob("naver_it_news_*.json"):
            print(f"\n{json_file} 처리 중...")
            producer.read_and_send_json(
                json_file_path=str(json_file),
                topic="raw_news"
            )

    finally:
        producer.close()


if __name__ == "__main__":
    main()