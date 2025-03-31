import requests
import json
import time
import sys

headers = {"Authorization": f"Bearer home"}
class Crawl4AiTester:
    def __init__(self, base_url: str = "http://localhost:11235"):
        self.base_url = base_url

    def submit_and_wait(self, request_data: dict, timeout: int = 300) -> dict:
        # Submit crawl job
        response = requests.post(f"{self.base_url}/crawl",headers=headers, json=request_data)
        print(response)
        task_id = response.json()["task_id"]
        print(f"Task ID: {task_id}")

        # Poll for result
        start_time = time.time()
        while True:
            if time.time() - start_time > timeout:
                raise TimeoutError(f"Task {task_id} timeout")

            result = requests.get(f"{self.base_url}/task/{task_id}")
            status = result.json()

            if status["status"] == "completed":
                return status

            time.sleep(2)

def test_deployment():
    tester = Crawl4AiTester()

    # Test basic crawl
    request = {
        "urls": "https://www.nbcnews.com/business",
        "priority": 10
    }

    result = tester.submit_and_wait(request)
    print("Basic crawl successful!")
    print(f"Content length: {len(result['result']['markdown'])}")

def test_unsecured():
    # Health check
    health = requests.get("http://localhost:11235/health")
    print("Health check:", health.json())

    # Basic crawl
    response = requests.post(
        "http://localhost:11235/crawl",
        headers=headers,
        json={
            "urls": "https://www.nbcnews.com/business",
            "priority": 10
        }
    )
    task_id = response.json()["task_id"]
    print("Task ID:", task_id)
    task_id = response.json()["task_id"]
    print(f"Task ID: {task_id}")
    result = requests.get(f"http://localhost:11235/task/{task_id}")
    status = result.json()
    print(status)


if __name__ == "__main__":
    request = {
        "urls": ["https://www.naver.com"],
        "priority": 10
    }

    # Docker Compose에서 설정한 토큰 사용
    api_token = "home"
    headers = {"Authorization": f"Bearer {api_token}"}

    # 크롤링 작업 제출
    response = requests.post(
        "http://localhost:11235/crawl",
        headers=headers,
        json=request
    )
    task_id = response.json()["task_id"]
    print(task_id)

    # 작업 상태 확인 (동일한 토큰 사용)
    result = requests.get(
        f"http://localhost:11235/task/{task_id}",
        headers=headers
    )
    print(result.json())