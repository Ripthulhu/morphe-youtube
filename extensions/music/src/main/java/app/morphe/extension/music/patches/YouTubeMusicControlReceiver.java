/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 section 7(b) and 7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.view.KeyEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;

/**
 * Provides a stable external playback API for YouTube Music.
 *
 * <p>The request extras intentionally match Symfonium's broadcast API. Search requests are
 * resolved to an exact YouTube Music watch URI so callers do not depend on the app's incomplete
 * MEDIA_PLAY_FROM_SEARCH implementation.</p>
 */
@SuppressWarnings({"deprecation", "unused"})
public final class YouTubeMusicControlReceiver extends BroadcastReceiver {
    public static final String ACTION_MEDIA_START =
            "app.morphe.youtube.music.api.MEDIA_START";
    public static final String ACTION_MEDIA_COMMAND =
            "app.morphe.youtube.music.api.MEDIA_COMMAND";

    public static final String EXTRA_MEDIA_TYPE = "MEDIA_TYPE";
    public static final String EXTRA_QUERY = "QUERY";
    public static final String EXTRA_NAME = "NAME";
    public static final String EXTRA_ARTIST = "ARTIST";
    public static final String EXTRA_ALBUM = "ALBUM";
    public static final String EXTRA_ID = "ID";
    public static final String EXTRA_FILE = "FILE";
    public static final String EXTRA_RESUME = "RESUME";
    public static final String EXTRA_SHUFFLE = "SHUFFLE";
    public static final String EXTRA_QUEUE = "QUEUE";
    public static final String EXTRA_COMMAND = "COMMAND";
    public static final String EXTRA_INT_PARAMETER = "INT_PARAMETER";
    public static final String EXTRA_CALLBACK = "CALLBACK";

    public static final String RESULT_VIDEO_ID = "VIDEO_ID";
    public static final String RESULT_URI = "URI";
    public static final String RESULT_ERROR = "ERROR";
    public static final String RESULT_UNSUPPORTED_EXTRAS = "UNSUPPORTED_EXTRAS";

    private static final int CALLBACK_OK = 0;
    private static final int CALLBACK_ERROR = 1;
    private static final int CONNECT_TIMEOUT_MILLISECONDS = 4_000;
    private static final int READ_TIMEOUT_MILLISECONDS = 4_000;
    private static final int MAX_RESPONSE_CHARACTERS = 2_000_000;
    private static final int MAX_QUERY_CHARACTERS = 500;
    private static final int NO_INT_PARAMETER = Integer.MIN_VALUE;
    private static final String SEARCH_URL = "https://music.youtube.com/search?q=";
    private static final String WATCH_URL = "https://music.youtube.com/watch?v=";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final String MEDIA_BUTTON_RECEIVER =
            "androidx.media.session.MediaButtonReceiver";
    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile(
            "\\\"watchEndpoint\\\"\\s*:\\s*\\{\\s*\\\"videoId\\\"\\s*:\\s*" +
                    "\\\"([A-Za-z0-9_-]{11})\\\""
    );
    private static final Set<String> MEDIA_TYPES = new HashSet<>(Arrays.asList(
            "playlist",
            "artist",
            "album",
            "genre",
            "song",
            "song_mix",
            "album_mix",
            "internet_radio"
    ));
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "morphe-youtube-music-control");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        String action = intent.getAction();
        if (ACTION_MEDIA_START.equals(action)) {
            handleMediaStart(context, intent);
        } else if (ACTION_MEDIA_COMMAND.equals(action)) {
            handleMediaCommand(context, intent);
        }
    }

    private void handleMediaStart(@NonNull Context context, @NonNull Intent intent) {
        ResultReceiver callback = getLegacyCallback(intent);
        boolean ordered = isOrderedBroadcast();
        String mediaType = normalized(intent.getStringExtra(EXTRA_MEDIA_TYPE));
        if (mediaType != null && !MEDIA_TYPES.contains(mediaType)) {
            sendImmediateResult(callback, ordered, CALLBACK_ERROR,
                    errorResult("unsupported_media_type"));
            return;
        }

        String query = buildQuery(intent);
        if (query.isEmpty()) {
            sendImmediateResult(callback, ordered, CALLBACK_ERROR, errorResult("query_required"));
            return;
        }

        String unsupportedExtras = unsupportedStartExtras(intent);
        PendingResult pendingResult = goAsync();
        Context applicationContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                String videoId = resolveFirstVideoId(query);
                if (videoId == null) {
                    sendResult(callback, pendingResult, ordered, CALLBACK_ERROR,
                            errorResult("no_music_result"));
                    if (!ordered && callback == null) openSearch(applicationContext, query);
                    return;
                }

                String watchUri = WATCH_URL + videoId;
                Bundle result = new Bundle();
                result.putString(RESULT_VIDEO_ID, videoId);
                result.putString(RESULT_URI, watchUri);
                if (!unsupportedExtras.isEmpty()) {
                    result.putString(RESULT_UNSUPPORTED_EXTRAS, unsupportedExtras);
                }

                if (ordered || callback != null) {
                    sendResult(callback, pendingResult, ordered, CALLBACK_OK, result);
                } else {
                    openUri(applicationContext, watchUri);
                }
                Logger.printDebug(() -> "YouTube Music control resolved query to: " + videoId);
            } catch (Exception exception) {
                sendResult(callback, pendingResult, ordered, CALLBACK_ERROR,
                        errorResult("resolve_failed"));
                if (!ordered && callback == null) openSearch(applicationContext, query);
                Logger.printDebug(() -> "YouTube Music control resolver unavailable: " +
                        exception.getClass().getSimpleName());
            } finally {
                pendingResult.finish();
            }
        });
    }

    private void handleMediaCommand(@NonNull Context context, @NonNull Intent intent) {
        ResultReceiver callback = getLegacyCallback(intent);
        boolean ordered = isOrderedBroadcast();
        String command = normalized(intent.getStringExtra(EXTRA_COMMAND));
        if (command == null) {
            sendImmediateResult(callback, ordered, CALLBACK_ERROR,
                    errorResult("command_required"));
            return;
        }

        boolean handled;
        switch (command) {
            case "play":
                handled = dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY);
                break;
            case "pause":
                handled = dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PAUSE);
                break;
            case "stop":
                handled = dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_STOP);
                break;
            case "next":
                handled = dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT);
                break;
            case "previous":
                handled = dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                break;
            case "mute":
                handled = adjustMute(context, true);
                break;
            case "togglemute":
                handled = toggleMute(context);
                break;
            case "volume":
                handled = setVolume(context,
                        intent.getIntExtra(EXTRA_INT_PARAMETER, NO_INT_PARAMETER));
                break;
            default:
                sendImmediateResult(callback, ordered, CALLBACK_ERROR,
                        errorResult("unsupported_command"));
                return;
        }

        if (!handled) {
            sendImmediateResult(callback, ordered, CALLBACK_ERROR,
                    errorResult("command_failed"));
            return;
        }

        Bundle result = new Bundle();
        result.putString(EXTRA_COMMAND, command);
        sendImmediateResult(callback, ordered, CALLBACK_OK, result);
    }

    @NonNull
    private static String buildQuery(@NonNull Intent intent) {
        String query = firstNonBlank(
                intent.getStringExtra(EXTRA_QUERY),
                intent.getStringExtra(EXTRA_NAME)
        );
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
        if (query.toString().toLowerCase(Locale.ROOT).contains(trimmed.toLowerCase(Locale.ROOT))) {
            return;
        }
        query.append(' ').append(trimmed);
    }

    @Nullable
    private static String firstNonBlank(@Nullable String first, @Nullable String second) {
        if (first != null && !first.trim().isEmpty()) return first;
        return second != null && !second.trim().isEmpty() ? second : null;
    }

    @Nullable
    private static String normalized(@Nullable String value) {
        if (value == null || value.trim().isEmpty()) return null;
        return value.trim().toLowerCase(Locale.ROOT);
    }

    @NonNull
    private static String unsupportedStartExtras(@NonNull Intent intent) {
        StringBuilder unsupported = new StringBuilder();
        appendUnsupported(unsupported, intent, EXTRA_ID);
        appendUnsupported(unsupported, intent, EXTRA_FILE);
        appendUnsupported(unsupported, intent, EXTRA_RESUME);
        appendUnsupported(unsupported, intent, EXTRA_SHUFFLE);
        appendUnsupported(unsupported, intent, EXTRA_QUEUE);
        return unsupported.toString();
    }

    private static void appendUnsupported(
            @NonNull StringBuilder output,
            @NonNull Intent intent,
            @NonNull String extra
    ) {
        if (!intent.hasExtra(extra)) return;
        if (output.length() > 0) output.append(',');
        output.append(extra);
    }

    private static boolean dispatchMediaKey(@NonNull Context context, int keyCode) {
        try {
            sendMediaKey(context, KeyEvent.ACTION_DOWN, keyCode);
            sendMediaKey(context, KeyEvent.ACTION_UP, keyCode);
            return true;
        } catch (RuntimeException exception) {
            Logger.printDebug(() -> "YouTube Music media command failed: " +
                    exception.getClass().getSimpleName());
            return false;
        }
    }

    private static void sendMediaKey(@NonNull Context context, int action, int keyCode) {
        Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        mediaButtonIntent.setComponent(new ComponentName(
                context.getPackageName(),
                MEDIA_BUTTON_RECEIVER
        ));
        mediaButtonIntent.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(action, keyCode));
        context.sendBroadcast(mediaButtonIntent);
    }

    private static boolean adjustMute(@NonNull Context context, boolean mute) {
        AudioManager audioManager = context.getSystemService(AudioManager.class);
        if (audioManager == null) return false;
        audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                mute ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE,
                0
        );
        return true;
    }

    private static boolean toggleMute(@NonNull Context context) {
        AudioManager audioManager = context.getSystemService(AudioManager.class);
        if (audioManager == null) return false;
        return adjustMute(context, !audioManager.isStreamMute(AudioManager.STREAM_MUSIC));
    }

    private static boolean setVolume(@NonNull Context context, int percent) {
        if (percent == NO_INT_PARAMETER || percent < 0 || percent > 100) return false;
        AudioManager audioManager = context.getSystemService(AudioManager.class);
        if (audioManager == null) return false;

        int maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int volume = Math.round(maximum * (percent / 100.0f));
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0);
        return true;
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
            connection.setRequestProperty(
                    "Cookie",
                    "CONSENT=YES+cb.20210328-17-p0.en+FX+999; SOCS=CAI"
            );
            connection.setRequestProperty("User-Agent", USER_AGENT);

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("YouTube Music search returned HTTP " + status);
            }

            String body = readBounded(connection.getInputStream())
                    .replace("\\x22", "\"")
                    .replace("\\x7b", "{");
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

    private void sendImmediateResult(
            @Nullable ResultReceiver callback,
            boolean ordered,
            int resultCode,
            @NonNull Bundle result
    ) {
        sendLegacyResult(callback, resultCode, result);
        if (ordered) {
            setResultCode(resultCode);
            setResultExtras(result);
        }
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
        openUri(context, SEARCH_URL + Uri.encode(query));
    }

    private static void openUri(@NonNull Context context, @NonNull String uri) {
        try {
            Intent watchIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
            watchIntent.setPackage(context.getPackageName());
            watchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(watchIntent);
        } catch (RuntimeException exception) {
            Logger.printDebug(() -> "YouTube Music activity launch unavailable: " +
                    exception.getClass().getSimpleName());
        }
    }
}
