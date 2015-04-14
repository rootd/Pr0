package com.pr0gramm.app.services;

import android.util.Log;

import com.google.common.base.Charsets;
import com.google.common.io.BaseEncoding;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;

import java.io.IOException;

import static com.google.common.base.MoreObjects.firstNonNull;

/**
 */
@Singleton
public class SimpleProxyService extends NanoHttpServer {
    private OkHttpClient okHttpClient;

    @Inject
    public SimpleProxyService(OkHttpClient okHttpClient) {
        super(getRandomPort());
        this.okHttpClient = okHttpClient;

        Log.i("Proxy", "Open simple proxy on port " + getListeningPort());
    }

    /**
     * Tries to get a random free port.
     *
     * @return A random free port number
     */
    private static int getRandomPort() {
        return (int) (20000 + (Math.random() * 40000));
    }

    public String getProxyUrl(String url) {
        String encoded = BaseEncoding.base64Url().encode(url.getBytes(Charsets.UTF_8));
        return "http://127.0.0.1:" + getListeningPort() + "/" + encoded;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String path = session.getUri();
        Log.i("Proxy", "Request " + path);

        String url = new String(
                BaseEncoding.base64Url().decode(path.substring(1)),
                Charsets.UTF_8).trim();

        Log.i("Proxy", "Range: " + session.getHeaders().get("Range"));

        com.squareup.okhttp.Response response;
        try {
            Request request = new Request.Builder().url(url).build();
            response = okHttpClient.newCall(request).execute();

            Response.IStatus status = translateStatus(response.code(), response.message());
            String contentType = response.header("Content-Type", "application/octet");
            Response result = new Response(status, contentType, response.body().byteStream());
            result.setChunkedTransfer(true);
            int length = Integer.parseInt(response.header("Content-Length", "-1"));
            if(length > 0)
                result.addHeader("Content-Length", String.valueOf(length));

            result.addHeader("Accept-Range", "bytes");
            return result;

        } catch (IOException e) {
            Log.e("Proxy", "Could not proxy for url " + url, e);
            return new Response(Response.Status.INTERNAL_ERROR, "text/plain", e.toString());
        }
    }

    private static Response.IStatus translateStatus(int code, String description) {
        return new Response.IStatus() {
            @Override
            public int getRequestStatus() {
                return code;
            }

            @Override
            public String getDescription() {
                return code + " " + firstNonNull(description, "unknown");
            }
        };
    }
}