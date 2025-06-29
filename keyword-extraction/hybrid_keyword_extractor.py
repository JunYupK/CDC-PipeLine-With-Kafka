import asyncio
import openai
from typing import List, Dict, Optional
import re
import logging
from keybert import KeyBERT
from sentence_transformers import SentenceTransformer

logger = logging.getLogger(__name__)

class HybridKeywordExtractor:
    def __init__(self, openai_api_key: Optional[str] = None):
        self.keybert_model = None
        self.openai_client = None
        if openai_api_key:
            self.openai_client = openai.AsyncOpenAI(api_key=openai_api_key)
        
        # 중요 기사 판정 임계값
        self.important_thresholds = {
            "views_count": 1000,
            "hot_keywords": ["긴급", "속보", "발표", "사고", "선거", "투자", "상장"]
        }
        
    async def initialize(self):
        """모델 초기화"""
        sentence_model = SentenceTransformer('sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2')
        self.keybert_model = KeyBERT(model=sentence_model)
        logger.info("✅ 하이브리드 키워드 추출기 초기화 완료")
    
    async def extract_keywords(self, title: str, content: str, article_metadata: Dict = None) -> List[str]:
        """하이브리드 키워드 추출"""
        # 1단계: 빠른 NLP 키워드 추출
        basic_keywords = await self._extract_basic_keywords(title, content)
        
        # 2단계: 중요 기사 판정
        if self._is_important_article(article_metadata, basic_keywords):
            if self.openai_client:
                # 3단계: LLM으로 정제
                refined_keywords = await self._refine_with_llm(title, content, basic_keywords)
                return self._merge_keywords(basic_keywords, refined_keywords)
            else:
                logger.warning("OpenAI 클라이언트 없음, 기본 키워드만 반환")
        
        return basic_keywords
    
    async def _extract_basic_keywords(self, title: str, content: str, max_keywords: int = 8) -> List[str]:
        """기본 NLP 키워드 추출 (빠른 처리)"""
        text = f"{title} {title} {content}"
        text = re.sub(r'[^\w\s가-힣]', ' ', text)
        text = re.sub(r'\s+', ' ', text).strip()
        
        if len(text) > 2000:
            text = text[:2000]
        
        stopwords = {
            '기자', '이번', '다음', '지난', '당시', '현재', '오늘', '내일', '어제',
            '그러나', '하지만', '또한', '그리고', '때문', '통해', '위해', '따라'
        }
        
        try:
            keywords = self.keybert_model.extract_keywords(
                text,
                keyphrase_ngram_range=(1, 3),
                stop_words=list(stopwords),
                use_mmr=True,
                diversity=0.7,
                top_k=max_keywords * 2
            )
            
            valid_keywords = []
            for keyword, score in keywords:
                keyword = keyword.strip()
                if (2 <= len(keyword) <= 15 and 
                    not keyword.isdigit() and
                    keyword.lower() not in stopwords):
                    valid_keywords.append(keyword)
                    
                if len(valid_keywords) >= max_keywords:
                    break
            
            return valid_keywords
            
        except Exception as e:
            logger.error(f"기본 키워드 추출 오류: {e}")
            return []
    
    def _is_important_article(self, metadata: Dict, basic_keywords: List[str]) -> bool:
        """중요 기사 판정"""
        if not metadata:
            return False
        
        # 조회수 기준
        views = metadata.get('views_count', 0)
        if views > self.important_thresholds["views_count"]:
            return True
        
        # 핫 키워드 포함 여부
        text_to_check = ' '.join(basic_keywords).lower()
        for hot_keyword in self.important_thresholds["hot_keywords"]:
            if hot_keyword in text_to_check:
                return True
        
        # 카테고리별 중요도 (정치, 경제는 더 민감하게)
        category = metadata.get('category', '')
        if category in ['정치', '경제'] and len(basic_keywords) >= 4:
            return True
        
        return False
    
    async def _refine_with_llm(self, title: str, content: str, basic_keywords: List[str]) -> List[str]:
        """LLM으로 키워드 정제"""
        try:
            # 내용 길이 제한
            content_truncated = content[:1500] if len(content) > 1500 else content
            
            prompt = f"""
다음 뉴스 기사에서 가장 중요한 키워드 5개를 추출해주세요.
기존 키워드: {', '.join(basic_keywords)}

제목: {title}
내용: {content_truncated}

조건:
1. 기존 키워드를 개선하거나 새로운 핵심 키워드 발견
2. 2-15자 길이
3. 명사 또는 명사구 위주
4. 한국어로 된 키워드만
5. 쉼표로 구분하여 나열

키워드:
"""
            
            response = await self.openai_client.chat.completions.create(
                model="gpt-3.5-turbo",
                messages=[{"role": "user", "content": prompt}],
                max_tokens=150,
                temperature=0.3
            )
            
            refined_text = response.choices[0].message.content.strip()
            refined_keywords = [kw.strip() for kw in refined_text.split(',') if kw.strip()]
            
            # 유효성 검사
            valid_refined = []
            for kw in refined_keywords:
                if 2 <= len(kw) <= 15 and not kw.isdigit():
                    valid_refined.append(kw)
            
            logger.info(f"LLM 정제 완료: {basic_keywords} → {valid_refined}")
            return valid_refined[:5]
            
        except Exception as e:
            logger.error(f"LLM 키워드 정제 오류: {e}")
            return []
    
    def _merge_keywords(self, basic_keywords: List[str], refined_keywords: List[str]) -> List[str]:
        """기본 키워드와 정제된 키워드 병합"""
        merged = []
        seen = set()
        
        # 정제된 키워드 우선 (LLM이 더 정확)
        for kw in refined_keywords:
            kw_lower = kw.lower()
            if kw_lower not in seen:
                merged.append(kw)
                seen.add(kw_lower)
        
        # 기본 키워드 중 중복되지 않은 것 추가
        for kw in basic_keywords:
            kw_lower = kw.lower()
            if kw_lower not in seen and len(merged) < 8:
                merged.append(kw)
                seen.add(kw_lower)
        
        return merged
    
    def get_extraction_stats(self) -> Dict:
        """추출 통계 (모니터링용)"""
        return {
            "basic_only_count": getattr(self, '_basic_only_count', 0),
            "llm_refined_count": getattr(self, '_llm_refined_count', 0),
            "total_processed": getattr(self, '_total_processed', 0)
        }