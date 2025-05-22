package org.be.crawlerservice.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.be.crawlerservice.entity.base.TimestampEntity;

import jakarta.persistence.*;

@Entity
@Table(name = "media")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Media extends TimestampEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "article_id", nullable = false)
    private Article article;

    @Column(length = 8)
    private String storedDate;

    @Column(nullable = false, length = 20)
    private String type;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String url;

    @Column(columnDefinition = "TEXT")
    private String caption;
}