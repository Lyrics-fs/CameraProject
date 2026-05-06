package com.example.camera.network;

import com.google.gson.annotations.SerializedName;

/**
 * 阿里云 FC 等接口返回的通用 JSON（如 {@code code} / {@code message}）。
 */
public final class BaseResponse {

    @SerializedName("code")
    public int code = -1;

    @SerializedName("message")
    public String message;
}
