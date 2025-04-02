# crawl4ai_helper.py
import asyncio
import aiohttp
import time
from typing import Dict, Any, Optional

async def _call_crawl4ai_api(
    session: aiohttp.ClientSession,
    api_url_base: str,
    api_token: str,
    payload: Dict[str, Any],
    poll_interval: int = 3,
    timeout: int = 180  # 전체 타임아웃 (초)
) -> Optional[Dict[str, Any]]:
    """
    Crawl4AI Docker API에 작업을 제출하고 완료될 때까지 폴링하여 결과를 반환하는 비동기 함수.

    Args:
        session: aiohttp 클라이언트 세션.
        api_url_base: Crawl4AI API 기본 URL (예: "http://crawl4ai-server:11235").
        api_token: API 인증 토큰.
        payload: /crawl 엔드포인트에 보낼 JSON 페이로드.
        poll_interval: 상태 확인 간격 (초).
        timeout: 작업 완료를 기다리는 최대 시간 (초).

    Returns:
        성공 시 작업 결과 딕셔너리, 실패 또는 타임아웃 시 None.
    """
    headers = {"Authorization": f"Bearer {api_token}"}
    crawl_url = f"{api_url_base}/crawl"
    task_id = None
    start_time = time.time()

    try:
        # 1. 작업 제출
        print(f"Submitting task for URL(s): {payload.get('urls')}")
        async with session.post(crawl_url, json=payload, headers=headers, timeout=30) as response:
            if response.status == 200:
                data = await response.json()
                task_id = data.get("task_id")
                if not task_id:
                    print(f"Error: No task_id received for {payload.get('urls')}")
                    return None
                print(f"Task submitted. Task ID: {task_id}")
            else:
                error_text = await response.text()
                print(f"Error submitting task ({response.status}): {error_text}")
                return None

        # 2. 결과 폴링
        status_url = f"{api_url_base}/task/{task_id}"
        while True:
            # 타임아웃 확인
            if time.time() - start_time > timeout:
                print(f"Error: Task {task_id} timed out after {timeout} seconds.")
                # 필요하다면 여기서 타임아웃된 작업 취소 요청 API 호출
                return None

            await asyncio.sleep(poll_interval)

            try:
                async with session.get(status_url, headers=headers, timeout=20) as status_response:
                    if status_response.status == 200:
                        status_data = await status_response.json()
                        status = status_data.get("status")
                        print(f"Task {task_id} status: {status}")

                        if status == "completed":
                            print(f"Task {task_id} completed.")
                            return status_data.get("result") # 결과 데이터 반환
                        elif status == "failed":
                            error_msg = status_data.get('error', 'Unknown error')
                            print(f"Error: Task {task_id} failed: {error_msg}")
                            return None
                        # 'processing', 'pending' 상태면 계속 폴링
                    else:
                        error_text = await status_response.text()
                        print(f"Error checking status for task {task_id} ({status_response.status}): {error_text}")
                        # 상태 확인 실패 시 잠시 후 재시도 (네트워크 일시 오류 등 고려)
                        await asyncio.sleep(poll_interval * 2)

            except asyncio.TimeoutError:
                print(f"Warning: Timeout checking status for task {task_id}. Retrying...")
            except aiohttp.ClientError as e:
                print(f"Warning: Network error checking status for task {task_id}: {e}. Retrying...")
                await asyncio.sleep(poll_interval * 2)

    except asyncio.TimeoutError:
        print(f"Error: Timeout submitting task for {payload.get('urls')}")
        return None
    except aiohttp.ClientError as e:
        print(f"Error: Network error submitting task for {payload.get('urls')}: {e}")
        return None
    except Exception as e:
        print(f"An unexpected error occurred for task {task_id or 'unknown'}: {e}")
        return None