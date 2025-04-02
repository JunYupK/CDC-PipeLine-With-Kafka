import asyncio
import aiohttp
import time

API_BASE_URL = "http://localhost:11235"
API_TOKEN = "home"
HEADERS = {"Authorization": f"Bearer {API_TOKEN}"}

async def submit_crawl_task(session, url):
    payload = {"urls": url, "priority": 10}
    async with session.post(f"{API_BASE_URL}/crawl", json=payload, headers=HEADERS) as response:
        if response.status == 200:
            data = await response.json()
            print(f"Submitted task for {url}, task_id: {data['task_id']}")
            return data['task_id']
        else:
            print(f"Error submitting task for {url}: {response.status}")
            return None

async def poll_for_result(session, task_id, poll_interval=5):
    while True:
        async with session.get(f"{API_BASE_URL}/task/{task_id}", headers=HEADERS) as response:
            if response.status == 200:
                data = await response.json()
                status = data.get("status")
                print(f"Task {task_id} status: {status}")
                if status == "completed":
                    return data # 또는 data['result']
                elif status == "failed":
                    print(f"Task {task_id} failed: {data.get('error')}")
                    return None # 실패 처리
                # 'processing' or 'pending' -> continue polling
            else:
                print(f"Error checking status for task {task_id}: {response.status}")
                # 오류 처리 (예: 재시도 로직 추가 가능)

        await asyncio.sleep(poll_interval) # 일정 시간 대기 후 다시 확인

async def main():
    urls_to_crawl = [
        "https://example.com",
        "https://www.google.com",
        "https://www.github.com",
        # ... (총 10개 또는 그 이상)
    ]

    async with aiohttp.ClientSession() as session:
        # 1. 모든 작업 제출
        submission_tasks = [submit_crawl_task(session, url) for url in urls_to_crawl]
        task_ids = await asyncio.gather(*submission_tasks)
        valid_task_ids = [tid for tid in task_ids if tid is not None]

        # 2. 모든 작업 결과 폴링
        polling_tasks = [poll_for_result(session, tid) for tid in valid_task_ids]
        results = await asyncio.gather(*polling_tasks)

        # 3. 결과 처리
        for url, result in zip([urls_to_crawl[i] for i, tid in enumerate(task_ids) if tid is not None], results):
            if result and result.get('status') == 'completed':
                print(f"\n--- Result for {url} ---")
                # 결과 데이터 활용 (예: result['result']['markdown'] 등)
                print(f"Content length: {len(result.get('result', {}).get('markdown', ''))}")
            else:
                print(f"\n--- Failed or no result for {url} ---")

if __name__ == "__main__":
    start_time = time.time()
    asyncio.run(main())
    end_time = time.time()
    print(f"\nTotal execution time: {end_time - start_time:.2f} seconds")