package com.example.camera.network;

import retrofit2.Response;

/**
 * Retrofit 2.11 的 {@link Response} 无 {@code close()}；释放底层连接需 {@link okhttp3.Response#close()}。
 * Gson 等转换器读完体后可能已关闭，此处吞掉重复关闭等异常。
 */
public final class RetrofitResponses {

    private RetrofitResponses() {}

    public static void closeRawQuietly(Response<?> response) {
        if (response == null) {
            return;
        }
        try {
            response.raw().close();
        } catch (Exception ignored) {
            // 已释放或 MockWebServer 场景下偶发 IllegalStateException
        }
    }
}
