# ETL 파이프라인 구축하기 + Naver 기사 크롤링
ETL 파이프 라인 구축과 이를 Kafka + pyspark를 활용하여 실시간 스트리밍 데이터 처리를 해보기 위한 프로젝트입니다.

기본적인 ETL 파이프라인 구축 후 Kafka와 결합하여 실시간 스트리밍 데이터 수집을 할 예정입니다.
어떤 데이터를 실시간 처리할지 추후 결정 후 연결 할 예정입니다.

[현재 프로젝트의 아키텍쳐]
## 🏗️ Architecture

```mermaid
graph LR;
    subgraph CrawlingLayer[🤖 Crawling Layer]
        C[(Crawl4AI)]
        subgraph CrawlData[Crawled Data]
            CD1[📰 News Articles]
            CD2[🖼️ Media Files]
            CD3[📑 Categories]
        end
    end

    subgraph SourceDB[📊 Source Database Layer]
        subgraph PostgreSQL[PostgreSQL Database]
            PG[(PostgreSQL)]
            subgraph Tables[Tables]
                T1[articles]
                T2[media]
                T3[categories]
            end
            subgraph CDC[CDC Mechanism]
                CT[article_changes]
                TG[Triggers]
                WAL[Write-Ahead Logs]
            end
        end
    end

    subgraph StreamingLayer[🔄 Streaming Layer]
        subgraph Debezium[Debezium]
            DC[Connector]
            DS[Serializer]
        end
        subgraph Kafka[Apache Kafka]
            KB[Kafka Broker]
            KT1[news.public.articles]
            KT2[news.public.media]
        end
    end

    subgraph TargetDB[💾 Target Database Layer]
        subgraph MySQL[MySQL Database]
            MS[(MySQL)]
            MT1[articles]
            MT2[media]
            MT3[categories]
        end
    end

    C --> CD1 & CD2 & CD3
    CD1 & CD2 & CD3 --> PG
    T1 & T2 & T3 --> CT
    T1 & T2 & T3 --> WAL
    WAL --> DC
    DC --> DS
    DS --> KB
    KB --> KT1 & KT2
    KT1 --> MT1
    KT2 --> MT2


```


<br>
[Issues]

* 대규모 데이터 스트리밍에 더 적합한 방식은 크롤링 서버 > Kafka > ETL 파이프라인 (Postgresql => Pyspark => MySQL)라고 생각되어 해당 방식으로 아키텍처 변경 예정

* Crwal4AI를 활용하여 웹 크롤링을 진행 예정 더 유연한 LLM 연결, 동적 크롤링 등 더 많은 이점이 있는 것으로 판단됨

* Crwal4AI의 schema 추출법을 명시하는 것이지 결국 Result에는 해당 페이지의 전문 HTML을 가져오며, 후에 extracted_content를 실행할때 해당 데이터 추출 스키마에 따라 데이터 추출이 가능하다, 한 result에 여러 스키마를 정의하여 데이터       추출이 가능한지는 추후 알아봐야 할 것


## Todo

* IT 관련 뉴스가 아닌 뉴스 탭의 모든 기사 크롤링 => O
* 이미지 데이터도 추가 수집
* 불필요한 HTML 태그 필터링 및 데이터 필터링
* 비동기 + 멀티스레드 방식 크롤링으로 인한 크롤링 시간 단축 => 멀티 스레드 적용 시 캐시 정합성 문제로 싱글 스레드인 Redis 도입 검토
* 기사 제목 해싱 작업 및 해당 해시로 캐시로 중복 크롤링 삭제
* Kafka 연결을 통한 실시간 데이터 스트리밍
  * 초기에는 크롤링 결과를 JSON 파일로 저장 후 이를 Kafka에 저장하는 방식
  * 이후에는 크롤링 결과값을 바로 전처리 하여 kafka streams를 활용하여 실시간으로 처리하는 방식으로 변경 예정 => 멀티스레드와 결합시 전처리 방법에 대한 고민은 한번 더 필요
* 로그 Kafka 저장 및 일별 평균 크롤링 시간 기록
* ETL 작업 시행
