package com.example.lagoudownload.utils;

import cn.hutool.http.Header;
import cn.hutool.http.HttpRequest;

import java.io.File;

public class HttpUtils {
    public static byte[] getContent(String url) {
        return HttpRequest.get(url).execute().bodyBytes();
    }

    public static void download(String url, File saveTo) {
        HttpRequest.get(url).execute().writeBody(saveTo);
    }

    public static byte[] getContentWithCookie(String url, String cookie) {
        return HttpRequest.get(url).header(Header.COOKIE, cookie).execute().bodyBytes();
    }

    public static HttpRequest get(String url, String cookie) {
        return HttpRequest.get(url).timeout(5000).header(Header.COOKIE, cookie);
    }
}
