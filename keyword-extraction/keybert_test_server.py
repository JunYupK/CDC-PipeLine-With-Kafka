from fastapi import FastAPI
from pydantic import BaseModel
from keybert import KeyBERT
from typing import List, Dict, Any

app = FastAPI(title="KeyBERT 키워드 추출 테스트 서버")

kw_model = KeyBERT()

class ArticleRequest(BaseModel):
    title: str
    content: str
    top_n: int = 5

class KeywordResponse(BaseModel):
    keywords: List[Dict[str, Any]]  # 더 유연한 타입으로 변경

@app.post("/extract", response_model=KeywordResponse)
async def extract_keywords(req: ArticleRequest):
    # title + content 합쳐서 키워드 추출
    text = req.title.strip() + "\n" + req.content.strip()
    keywords = kw_model.extract_keywords(text, top_n=req.top_n, stop_words='english')
    
    # 키워드와 점수를 딕셔너리 리스트로 변환
    keyword_list = []
    for kw, score in keywords:
        try:
            score_float = float(str(score))
            keyword_list.append({"keyword": kw, "score": score_float})
        except (ValueError, TypeError):
            keyword_list.append({"keyword": kw, "score": 0.0})
    return {"keywords": keyword_list}

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8010, reload=True) 