import requests
import time
import json
import logging

logging.basicConfig(level=logging.DEBUG)


def crawl_with_detailed_logging(url):
    api_token = "home"
    headers = {"Authorization": f"Bearer {api_token}"}

    try:
        # 크롤링 요청
        request = {
            "urls": [url],
            "priority": 10,
            "crawler_params": {
                "headless": True,
                "page_timeout": 60000,  # 타임아웃 증가
                "verbose": True,
                "simulate_user": True,
                "magic": True
            }
        }

        logging.info(f"Sending crawl request for {url}")
        response = requests.post(
            "http://localhost:11235/crawl",
            headers=headers,
            json=request
        )
        response.raise_for_status()

        task_id = response.json().get("task_id")
        logging.info(f"Task ID: {task_id}")

        # 작업 상태 확인
        return wait_for_task_completion(task_id, api_token)

    except requests.RequestException as e:
        logging.error(f"Request error: {e}")
        return None


def wait_for_task_completion(task_id, api_token, max_wait=300):
    headers = {"Authorization": f"Bearer {api_token}"}
    start_time = time.time()

    while time.time() - start_time < max_wait:
        try:
            result = requests.get(
                f"http://localhost:11235/task/{task_id}",
                headers=headers
            )
            status = result.json()

            logging.debug(f"Task status: {json.dumps(status, indent=2)}")

            if status['status'] == 'completed':
                logging.info("Task completed successfully")
                # 결과 상세 로깅
                for result_item in status.get('results', []):
                    logging.info(f"URL: {result_item.get('url')}")
                    logging.info(f"Success: {result_item.get('success')}")
                    logging.info(f"Error: {result_item.get('error_message')}")
                return status
            elif status['status'] == 'failed':
                logging.error("Task failed")
                return None

            time.sleep(2)
        except Exception as e:
            logging.error(f"Error checking task status: {e}")

    logging.warning("Task timed out")
    return None


# 실행
if __name__ == "__main__":
    result = crawl_with_detailed_logging("https://www.python.org")
    if result:
        print("Crawling completed")