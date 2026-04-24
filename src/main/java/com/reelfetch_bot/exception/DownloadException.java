package com.reelfetch_bot.exception;

public class DownloadException extends Exception {
    public DownloadException(String message) { super(message); }
    public DownloadException(String message, Throwable cause) { super(message, cause); }
}