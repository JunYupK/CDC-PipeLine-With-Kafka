package org.be.crawlerservice.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.be.crawlerservice.entity.base.TimestampEntity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "articles", indexes = {
        @Index(name = "idx_category", columnList = "category"),
//        @Index(name = "idx_stored_date", columnList = "stored_date"),
        @Index(name = "idx_created_at", columnList = "created_at"),
        @Index(name = "idx_link", columnList = "link", unique = true)
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Article extends TimestampEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String link;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category categoryEntity;

    @Column(length = 100)
    private String category;

    @Column(length = 100)
    private String source;

    @Column(length = 100)
    private String author;

    @Column
    private LocalDateTime publishedAt;

    @Column(nullable = false, length = 8)
    private String storedDate;

    @Column
    @Builder.Default
    private Integer viewsCount = 0;

    @Column
    private Double sentimentScore;

    @Column
    private Integer articleTextLength;

    @Column(columnDefinition = "TEXT")
    private String keywords;

    @Column
    @Builder.Default
    private Integer version = 1;

    @Column
    @Builder.Default
    private Boolean isDeleted = false;

    @OneToMany(mappedBy = "article", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Media> media;
}