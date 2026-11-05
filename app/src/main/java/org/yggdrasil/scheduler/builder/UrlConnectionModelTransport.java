package org.yggdrasil.scheduler.builder;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Minimal HTTP transport with redirects disabled to prevent credential forwarding. */
public final class UrlConnectionModelTransport implements OpenAiCompatibleModelProvider.HttpTransport {
    @Override public OpenAiCompatibleModelProvider.HttpResult post(
            String endpoint, Map<String, String> headers, String body,
            int connectTimeoutMillis, int readTimeoutMillis) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(connectTimeoutMillis);
            connection.setReadTimeout(readTimeoutMillis);
            connection.setInstanceFollowRedirects(false);
            connection.setDoOutput(true);
            for (Map.Entry<String, String> header : headers.entrySet())
                connection.setRequestProperty(header.getKey(), header.getValue());
            byte[] request = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(request.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(request);
                output.flush();
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300)
                return new OpenAiCompatibleModelProvider.HttpResult(status, "");
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 2 * 1024 * 1024)
                        throw new java.io.IOException("Model response exceeds 2 MB limit");
                    output.write(buffer, 0, count);
                }
                return new OpenAiCompatibleModelProvider.HttpResult(status,
                        new String(output.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally {
            connection.disconnect();
        }
    }
}
