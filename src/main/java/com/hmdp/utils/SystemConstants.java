package com.hmdp.utils;

public class SystemConstants {
    public static final String IMAGE_UPLOAD_DIR = "C:\\document\\javacode\\nginx-1.18.0\\html\\hmdp\\imgs\\";
    public static final String USER_NICK_NAME_PREFIX = "user_";
    public static final int DEFAULT_PAGE_SIZE = 5;
    public static final int MAX_PAGE_SIZE = 10;

    // 新增: HTTP请求头中长短令牌的字段名
    public static final String HEADER_LONG_TOKEN = "X-Long-Token";
    public static final String HEADER_SHORT_TOKEN = "X-Short-Token";
    // 新增: HTTP响应头中，用于下发刷新后的短令牌的字段名
    public static final String RESPONSE_HEADER_REFRESHED_SHORT_TOKEN = "X-Refreshed-Short-Token";
}
