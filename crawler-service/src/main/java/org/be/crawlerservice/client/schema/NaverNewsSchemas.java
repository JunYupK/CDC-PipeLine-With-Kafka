package org.be.crawlerservice.client.schema;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 네이버 뉴스 크롤링을 위한 스키마 정의
 * Python main_crawler.py의 URL_LIST_SCHEMA, CONTENT_SCHEMA를 Java로 구현
 */
@Component
public class NaverNewsSchemas {

    /**
     * 네이버 뉴스 목록 페이지 크롤링 스키마
     * Python: URL_LIST_SCHEMA
     */
    public static Map<String, Object> getUrlListSchema() {
        return Map.of(
                "schema", Map.of(
                        "type", "dict",              // ✅ dict 래핑 추가
                        "value", Map.of(             // ✅ value 래핑 추가
                                "name", "Simple Links",
                                "baseSelector", "li",  // 링크 요소만 선택
                                "fields", List.of(
                                        Map.of(
                                                "name","title",
                                                "selector", "div.sa_text > a > strong, a.sa_text_title > strong.sa_text_strong",
                                                "type", "text"
                                        ),
                                        Map.of(
                                                "name","link",
                                                "selector", "div.sa_text > a, a.sa_text_title",
                                                "type", "attribute",
                                                "attribute", "href"
                                        )
                                )
                        )
                )

        );
    }

    /**
     * 네이버 뉴스 기사 내용 크롤링 스키마
     * Python: CONTENT_SCHEMA
     */
    public static Map<String, Object> getContentSchema() {
        return Map.of(
                "name", "Naver Article Content",
                "baseSelector", "body",
                "fields", List.of(
                        Map.of(
                                "name", "content",
                                "selector", "#dic_area",
                                "type", "text"
                        ),
                        Map.of(
                                "name", "image",
                                "selector", "#dic_area img",  // dic_area 내의 모든 img 태그
                                "type", "attribute",
                                "attribute", "src"
                        ),
                        Map.of(
                                "name", "image_alts", // 이미지 alt 텍스트도 필요하다면
                                "selector", "#dic_area img",
                                "type", "attribute",
                                "attribute", "alt"
                        )
                )
        );
    }


    /**
     * 단순 링크 추출 스키마
     */
    public static Map<String, Object> getSimpleLinkSchema() {
        return Map.of(

                "schema", Map.of(
                        "type", "dict",              // ✅ dict 래핑 추가
                        "value", Map.of(             // ✅ value 래핑 추가
                                "name", "Simple Links",
                                "baseSelector", "li",  // 링크 요소만 선택
                                "fields", List.of(
                                        Map.of(
                                                "name","title",
                                                "selector", "div.sa_text > a > strong, a.sa_text_title > strong.sa_text_strong",
                                                "type", "text"
                                        ),
                                        Map.of(
                                                "name","link",
                                                "selector", "div.sa_text > a, a.sa_text_title",
                                                "type", "attribute",
                                                "attribute", "href"
                                        )
                                )
                        )
                )

        );
    }



    /**
     * 카테고리별 URL 매핑
     * Python crawler_service.py의 URLS와 동일
     */
    public static Map<String, String> getCategoryUrls() {
        return Map.of(
                "정치", "https://news.naver.com/section/100",
                "경제", "https://news.naver.com/section/101",
                "사회", "https://news.naver.com/section/102",
                "생활문화", "https://news.naver.com/section/103",
                "세계", "https://news.naver.com/section/104",
                "IT과학", "https://news.naver.com/section/105"
        );
    }

    /**
     * 스포츠 카테고리별 URL 매핑
     * Python sports_crawler.py의 CATEGORY_MAPPING 참고
     */
    public static Map<String, String> getSportsCategoryUrls() {
        return Map.of(
                "야구", "https://sports.news.naver.com/kbaseball/news/index",
                "해외야구", "https://sports.news.naver.com/wbaseball/news/index",
                "축구", "https://sports.news.naver.com/kfootball/news/index",
                "해외축구", "https://sports.news.naver.com/wfootball/news/index",
                "농구", "https://sports.news.naver.com/basketball/news/index",
                "배구", "https://sports.news.naver.com/volleyball/news/index",
                "골프", "https://sports.news.naver.com/golf/news/index",
                "일반", "https://sports.news.naver.com/general/news/index"
        );
    }

    /**
     * URL에서 카테고리 타입 판단
     */
    public static boolean isSportsCategory(String url) {
        return url != null && url.contains("sports.news.naver.com");
    }

    /**
     * 카테고리명 정규화 (한글 → 영문 코드)
     */
    public static String normalizeCategoryName(String category) {
        if (category == null) return "all";

        return switch (category) {
            case "정치" -> "politics";
            case "경제" -> "economy";
            case "사회" -> "society";
            case "생활문화" -> "culture";
            case "세계" -> "world";
            case "IT과학" -> "tech";
            case "야구" -> "kbaseball";
            case "해외야구" -> "wbaseball";
            case "축구" -> "kfootball";
            case "해외축구" -> "wfootball";
            case "농구" -> "basketball";
            case "배구" -> "volleyball";
            case "골프" -> "golf";
            default -> category.toLowerCase();
        };
    }
    /**
     * 2025년 네이버 뉴스 목록 스키마 (실제 구조 기반)
     */

    public static Map<String, Object> getImprovedUrlListSchema() {
        return Map.of(
                "baseSelector", "body"
                );// 실제 뉴스 아이템 선택자

    }





}