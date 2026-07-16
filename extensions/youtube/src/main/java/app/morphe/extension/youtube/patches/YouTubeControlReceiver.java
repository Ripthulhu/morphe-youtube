/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ResultReceiver;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;

/**
 * Resolves a text query to a video and opens it through YouTube's normal watch player.
 */
@SuppressWarnings({"deprecation", "unused"})
public final class YouTubeControlReceiver extends BroadcastReceiver {
    public static final String ACTION_MEDIA_START = "app.morphe.youtube.api.MEDIA_START";
    public static final String EXTRA_QUERY = "QUERY";
    public static final String EXTRA_NAME = "NAME";
    public static final String EXTRA_ARTIST = "ARTIST";
    public static final String EXTRA_ALBUM = "ALBUM";
    public static final String EXTRA_CALLBACK = "CALLBACK";
    public static final String RESULT_VIDEO_ID = "VIDEO_ID";
    public static final String RESULT_URI = "URI";
    public static final String RESULT_ERROR = "ERROR";

    private static final int CALLBACK_OK = 0;
    private static final int CALLBACK_ERROR = 1;
    private static final int CONNECT_TIMEOUT_MILLISECONDS = 4_000;
    private static final int READ_TIMEOUT_MILLISECONDS = 4_000;
    private static final int MAX_RESPONSE_CHARACTERS = 2_000_000;
    private static final int MAX_QUERY_CHARACTERS = 500;
    private static final String SEARCH_URL =
            "https://www.youtube.com/results?sp=EgIQAQ%3D%3D&search_query=";
    private static final String WATCH_URL = "https://www.youtube.com/watch?v=";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile(
            "\\\"watchEndpoint\\\"\\s*:\\s*\\{\\s*\\\"videoId\\\"\\s*:\\s*" +
                    "\\\"([A-Za-z0-9_-]{11})\\\""
    );
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "morphe-youtube-control");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_MEDIA_START.equals(intent.getAction())) return;

        String query = buildQuery(intent);
        ResultReceiver callback = getLegacyCallback(intent);
        boolean ordered = isOrderedBroadcast();
        if (query.isEmpty()) {
            Bundle result = errorResult("query_required");
            sendLegacyResult(callback, CALLBACK_ERROR, result);
            if (ordered) {
                setResultCode(CALLBACK_ERROR);
                setResultExtras(result);
            }
            return;
        }

        PendingResult pendingResult = goAsync();
        Context applicationContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                String videoId = resolveFirstVideoId(query);
                if (videoId == null) {
                    sendResult(callback, pendingResult, ordered, CALLBACK_ERROR,
                            errorResult("no_video_result"));
                    if (!ordered && callback == null) openSearch(applicationContext, query);
                    return;
                }

                String watchUri = WATCH_URL + videoId;
                if (ordered || callback != null) {
                    Bundle result = new Bundle();
                    result.putString(RESULT_VIDEO_ID, videoId);
                    result.putString(RESULT_URI, watchUri);
                    sendResult(callback, pendingResult, ordered, CALLBACK_OK, result);
                } else {
                    openUri(applicationContext, watchUri);
                }
                Logger.printDebug(() -> "YouTube control resolved query to: " + videoId);
            } catch (Exception exception) {
                sendResult(callback, pendingResult, ordered, CALLBACK_ERROR,
                        errorResult("resolve_failed"));
                if (!ordered && callback == null) openSearch(applicationContext, query);
                Logger.printDebug(() -> "YouTube control resolver unavailable: " +
                        exception.getClass().getSimpleName());
            } finally {
                pendingResult.finish();
            }
        });
    }

    @NonNull
    private static String buildQuery(@NonNull Intent intent) {
        String query = firstNonBlank(intent.getStringExtra(EXTRA_QUERY), intent.getStringExtra(EXTRA_NAME));
        if (query == null) return "";

        StringBuilder builder = new StringBuilder(query.trim());
        appendIfMissing(builder, intent.getStringExtra(EXTRA_ARTIST));
        appendIfMissing(builder, intent.getStringExtra(EXTRA_ALBUM));
        if (builder.length() > MAX_QUERY_CHARACTERS) builder.setLength(MAX_QUERY_CHARACTERS);
        return builder.toString();
    }

    private static void appendIfMissing(@NonNull StringBuilder query, @Nullable String value) {
        if (value == null || value.trim().isEmpty()) return;

        String trimmed = value.trim();
        if (query.toString().toLowerCase(Locale.ROOT).contains(trimmed.toLowerCase(Locale.ROOT))) return;
        query.append(' ').append(trimmed);
    }

    @Nullable
    private static String firstNonBlank(@Nullable String first, @Nullable String second) {
        if (first != null && !first.trim().isEmpty()) return first;
        return second != null && !second.trim().isEmpty() ? second : null;
    }

    @Nullable
    private static ResultReceiver getLegacyCallback(@NonNull Intent intent) {
        if (!intent.hasExtra(EXTRA_CALLBACK)) return null;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return intent.getParcelableExtra(EXTRA_CALLBACK, ResultReceiver.class);
            }
            return intent.getParcelableExtra(EXTRA_CALLBACK);
        } catch (RuntimeException exception) {
            Logger.printDebug(() -> "Ignoring unavailable legacy callback: " +
                    exception.getClass().getSimpleName());
            return null;
        }
    }

    @Nullable
    private static String resolveFirstVideoId(@NonNull String query) throws IOException {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(SEARCH_URL + Uri.encode(query));
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLISECONDS);
            connection.setReadTimeout(READ_TIMEOUT_MILLISECONDS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.8");
            connection.setRequestProperty("Cookie", "CONSENT=YES+cb.20210328-17-p0.en+FX+999");
            connection.setRequestProperty("User-Agent", USER_AGENT);

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("YouTube search returned HTTP " + status);

            String body = readBounded(connection.getInputStream());
            // Mobile search responses may place initial data inside a JavaScript string.
            body = body.replace("\\x22", "\"").replace("\\x7b", "{");
            Matcher matcher = VIDEO_ID_PATTERN.matcher(body);
            return matcher.find() ? matcher.group(1) : null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    @NonNull
    private static String readBounded(@NonNull InputStream stream) throws IOException {
        StringBuilder output = new StringBuilder(512_000);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[8_192];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                int remaining = MAX_RESPONSE_CHARACTERS - output.length();
                if (remaining <= 0) break;
                output.append(buffer, 0, Math.min(count, remaining));
            }
        }
        return output.toString();
    }

    @NonNull
    private static Bundle errorResult(@NonNull String error) {
        Bundle result = new Bundle();
        result.putString(RESULT_ERROR, error);
        return result;
    }

    private static void sendResult(
            @Nullable ResultReceiver callback,
            @NonNull PendingResult pendingResult,
            boolean ordered,
            int resultCode,
            @NonNull Bundle result
    ) {
        sendLegacyResult(callback, resultCode, result);
        if (ordered) {
            pendingResult.setResultCode(resultCode);
            pendingResult.setResultExtras(result);
        }
    }

    private static void sendLegacyResult(
            @Nullable ResultReceiver callback,
            int resultCode,
            @NonNull Bundle result
    ) {
        if (callback == null) return;

        try {
            callback.send(resultCode, result);
        } catch (RuntimeException exception) {
            Logger.printDebug(() -> "Ignoring unavailable legacy callback: " +
                    exception.getClass().getSimpleName());
        }
    }

    private static void openSearch(@NonNull Context context, @NonNull String query) {
        openUri(context, "https://www.youtube.com/results?search_query=" + Uri.encode(query));
    }

    private static void openUri(@NonNull Context context, @NonNull String uri) {
        Intent watchIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
        watchIntent.setPackage(context.getPackageName());
        watchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        context.startActivity(watchIntent);
    }
}
