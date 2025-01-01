KAFKA_TOPICS = {
    'raw_news': {
        'name': 'raw_news',
        'partitions': 6,  # 뉴스 카테고리 수만큼
        'replication_factor': 3,
        'configs': {
            'retention.ms': 604800000,  # 7일 보관
            'cleanup.policy': 'delete'
        }
    },
    'processed_news': {
        'name': 'processed_news',
        'partitions': 6,
        'replication_factor': 3,
        'configs': {
            'retention.ms': 604800000,
            'cleanup.policy': 'delete'
        }
    }
}