package org.be.crawlerservice.repository;

import org.be.crawlerservice.entity.Media;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MediaRepository extends JpaRepository<Media, Long> {

    /**
     * 기사 ID로 미디어 파일 조회
     */
    List<Media> findByArticleId(Long articleId);

    /**
     * 미디어 타입별 조회
     */
    List<Media> findByType(String type);

    /**
     * 기사 ID로 미디어 삭제
     */
    void deleteByArticleId(Long articleId);
}
