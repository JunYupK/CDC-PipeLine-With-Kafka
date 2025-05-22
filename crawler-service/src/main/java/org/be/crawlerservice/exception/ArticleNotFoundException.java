package org.be.crawlerservice.exception;

public class ArticleNotFoundException extends RuntimeException {

    public ArticleNotFoundException(String message) {
        super(message);
    }

    public ArticleNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}