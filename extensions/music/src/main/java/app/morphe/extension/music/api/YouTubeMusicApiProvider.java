/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 section 7(b) and 7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.api;

import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.text.TextUtils;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.Logger;

/**
 * External control bridge for Voice Assistant.
 *
 * <p>This exists because {@code YouTubeMusicControlReceiver} cannot authenticate its callers:
 * inside {@code BroadcastReceiver.onReceive} the binder calling identity has already been reset,
 * so {@code Binder.getCallingUid()} returns this app's own uid rather than the sender's.
 * {@link #call} runs inside a real binder transaction, so the caller identity is genuine and
 * {@link #isAllowedCaller()} can actually enforce an allowlist.</p>
 *
 * <p>The second reason is honesty. The receiver reports success when {@code sendBroadcast} did not
 * throw, which says nothing about whether playback changed. Every mutating method here dispatches,
 * then re-reads observable state within a bounded window and reports what was actually reached.
 * Where a change genuinely cannot be observed (track skips without the VideoInformation hook) the
 * result carries {@code verified:false} instead of pretending.</p>
 *
 * <p>Only {@link #call} is implemented; URI operations are deliberately unsupported.</p>
 */
@SuppressWarnings("unused")
public final class YouTubeMusicApiProvider extends ContentProvider {
    private static final int API_VERSION = 1;

    private static final Set<String> ALLOWED_PACKAGES = new HashSet<>(Arrays.asList(
            "app.ripthulhu.voiceassistant"));

    private static final String MEDIA_BUTTON_RECEIVER =
            "androidx.media.session.MediaButtonReceiver";

    /** Total time spent waiting for a dispatched command to become observable. */
    private static final long VERIFY_TIMEOUT_MILLISECONDS = 2_000L;
    /** Poll interval while waiting. Bounded so the binder thread is never held open. */
    private static final long VERIFY_INTERVAL_MILLISECONDS = 75L;

    @Override
    public boolean onCreate() {
        return getContext() != null;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!isAllowedCaller()) return failure("FORBIDDEN", "Caller is not allowed.");
        if (method == null || method.length() > 64) return failure("VALIDATION", "Invalid method.");
        try {
            JSONObject result;
            switch (method) {
                case "capabilities":
                    result = capabilities();
                    break;
                case "state":
                    result = state();
                    break;
                case "play":
                    return transport("play", KeyEvent.KEYCODE_MEDIA_PLAY, Boolean.TRUE);
                case "pause":
                    return transport("pause", KeyEvent.KEYCODE_MEDIA_PAUSE, Boolean.FALSE);
                case "stop":
                    return transport("stop", KeyEvent.KEYCODE_MEDIA_STOP, Boolean.FALSE);
                case "next":
                    return skip("next", KeyEvent.KEYCODE_MEDIA_NEXT);
                case "previous":
                    return skip("previous", KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                case "volume":
                    return volume(extras);
                case "mute":
                    return mute(true);
                case "unmute":
                    return mute(false);
                default:
                    return failure("VALIDATION", "Unknown method.");
            }
            return success(result);
        } catch (ApiException exception) {
            return failure(exception.code, exception.getMessage());
        } catch (SecurityException exception) {
            return failure("FORBIDDEN", "YouTube Music rejected the request.");
        } catch (Exception exception) {
            Logger.printDebug(() -> "YouTube Music API failed: "
                    + exception.getClass().getSimpleName());
            return failure("INTERNAL", "YouTube Music API operation failed.");
        }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }

    // region methods

    /**
     * The availability map is deliberately narrow. YouTube Music has no bytecode hook exposing
     * player metadata to this module, so track title, artist, album, queue and explicit
     * playing/paused state are simply not obtainable and are reported false rather than guessed.
     *
     * <p>{@code video_id}, {@code position} and {@code duration} come from
     * {@link VideoInformation}, whose fields are populated by a separate patch's injection points.
     * Their availability is therefore probed at call time rather than asserted: an unpatched or
     * not-yet-playing app reports false.</p>
     */
    private JSONObject capabilities() throws Exception {
        JSONObject result = new JSONObject();
        result.put("api_version", API_VERSION);
        result.put("operations", new JSONArray(Arrays.asList(
                "capabilities", "state", "play", "pause", "next", "previous", "stop",
                "volume", "mute", "unmute")));

        JSONObject fields = new JSONObject();
        fields.put("audio_active", audioManager() != null);
        fields.put("volume", audioManager() != null);
        fields.put("muted", audioManager() != null);
        fields.put("video_id", videoId() != null);
        fields.put("position_ms", VideoInformation.getVideoTime() >= 0);
        fields.put("duration_ms", VideoInformation.getVideoLength() > 0);
        // No source exists in this module for any of the following.
        fields.put("track_title", false);
        fields.put("artist", false);
        fields.put("album", false);
        fields.put("playback_state", false);
        fields.put("queue", false);
        fields.put("shuffle", false);
        fields.put("repeat", false);
        result.put("capabilities", fields);

        JSONObject verification = new JSONObject();
        verification.put("play", "audio_active");
        verification.put("pause", "audio_active");
        verification.put("stop", "audio_active");
        verification.put("next", "video_id");
        verification.put("previous", "video_id");
        verification.put("volume", "read_back");
        verification.put("mute", "read_back");
        verification.put("unmute", "read_back");
        result.put("verified_by", verification);
        return result;
    }

    /**
     * {@code audio_active} is {@link AudioManager#isMusicActive()}: it reports whether anything on
     * the device is playing on the music stream, not necessarily YouTube Music. That is a real
     * limitation of the only unhooked signal available, and it is named in the payload so a caller
     * does not mistake it for a per-app state.
     */
    private JSONObject state() throws Exception {
        AudioManager audio = requireAudioManager();
        JSONObject result = new JSONObject();
        result.put("audio_active", audio.isMusicActive());
        result.put("audio_active_scope", "device_music_stream");
        result.put("volume", volumePercent(audio));
        result.put("volume_raw", audio.getStreamVolume(AudioManager.STREAM_MUSIC));
        result.put("volume_max", audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        result.put("muted", audio.isStreamMute(AudioManager.STREAM_MUSIC));

        String videoId = videoId();
        if (videoId != null) result.put("video_id", videoId);
        long position = VideoInformation.getVideoTime();
        if (position >= 0) result.put("position_ms", position);
        long duration = VideoInformation.getVideoLength();
        if (duration > 0) result.put("duration_ms", duration);
        return result;
    }

    /**
     * play / pause / stop. Dispatches the media key then waits for
     * {@link AudioManager#isMusicActive()} to reach {@code expected}. If the desired state already
     * holds the command is still dispatched (the caller asked for it), but the reply says nothing
     * changed rather than implying it did.
     */
    private Bundle transport(String command, int keyCode, Boolean expected) throws Exception {
        AudioManager audio = requireAudioManager();
        boolean before = audio.isMusicActive();
        dispatchMediaKey(keyCode);
        boolean reached = awaitAudioActive(audio, expected);
        boolean after = audio.isMusicActive();

        JSONObject result = new JSONObject();
        result.put("command", command);
        result.put("verified", true);
        result.put("audio_active_before", before);
        result.put("audio_active", after);
        result.put("changed", before != after);
        result.put("already_in_state", before == expected);
        if (!reached) {
            return failure("UNAVAILABLE", "Dispatched " + command
                    + " but the music stream is still "
                    + (after ? "active" : "inactive")
                    + " after " + VERIFY_TIMEOUT_MILLISECONDS + "ms; playback did not change.");
        }
        return success(result);
    }

    /**
     * next / previous. A track change is only observable when the VideoInformation hook is
     * populating the current video id. Without it there is no signal at all, so the reply reports
     * {@code verified:false} and says why — an unverifiable dispatch is not reported as a
     * confirmed skip.
     */
    private Bundle skip(String command, int keyCode) throws Exception {
        AudioManager audio = requireAudioManager();
        String before = videoId();
        dispatchMediaKey(keyCode);

        JSONObject result = new JSONObject();
        result.put("command", command);
        if (before == null) {
            result.put("verified", false);
            result.put("reason", "No current video id is available, so a track change cannot be "
                    + "observed. The command was dispatched but its effect is unconfirmed.");
            result.put("audio_active", audio.isMusicActive());
            return success(result);
        }

        String after = awaitVideoIdChange(before);
        result.put("video_id_before", before);
        result.put("audio_active", audio.isMusicActive());
        if (after == null) {
            result.put("verified", false);
            result.put("video_id", before);
            result.put("reason", "The current video id did not change within "
                    + VERIFY_TIMEOUT_MILLISECONDS + "ms. The command was dispatched but its "
                    + "effect is unconfirmed.");
            return success(result);
        }
        result.put("verified", true);
        result.put("video_id", after);
        return success(result);
    }

    /** Sets STREAM_MUSIC volume from a 0-100 level, or reads it back when {@code level} is absent. */
    private Bundle volume(Bundle extras) throws Exception {
        AudioManager audio = requireAudioManager();
        if (!has(extras, "level")) {
            JSONObject result = new JSONObject();
            result.put("volume", volumePercent(audio));
            result.put("volume_raw", audio.getStreamVolume(AudioManager.STREAM_MUSIC));
            result.put("volume_max", audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            result.put("muted", audio.isStreamMute(AudioManager.STREAM_MUSIC));
            result.put("verified", true);
            return success(result);
        }

        Object raw = extras.get("level");
        if (!(raw instanceof Integer)) return failure("VALIDATION", "level must be an int.");
        int level = (Integer) raw;
        if (level < 0 || level > 100) return failure("VALIDATION", "level must be 0-100.");

        int maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int target = Math.round(maximum * (level / 100.0f));
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
        } catch (SecurityException exception) {
            // Set by a Do Not Disturb policy restriction rather than by a caller problem.
            return failure("UNAVAILABLE", "The system refused the volume change.");
        }

        int actual = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        JSONObject result = new JSONObject();
        result.put("requested", level);
        result.put("volume", percent(actual, maximum));
        result.put("volume_raw", actual);
        result.put("volume_max", maximum);
        result.put("muted", audio.isStreamMute(AudioManager.STREAM_MUSIC));
        result.put("verified", true);
        if (actual != target) {
            return failure("UNAVAILABLE", "Requested volume " + level + "% (raw " + target
                    + ") but the stream is at raw " + actual + " of " + maximum + ".");
        }
        return success(result);
    }

    /** mute / unmute, both explicit rather than a toggle, and both verified by read-back. */
    private Bundle mute(boolean muted) throws Exception {
        AudioManager audio = requireAudioManager();
        boolean before = audio.isStreamMute(AudioManager.STREAM_MUSIC);
        try {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    muted ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, 0);
        } catch (SecurityException exception) {
            return failure("UNAVAILABLE", "The system refused the mute change.");
        }

        boolean reached = awaitMute(audio, muted);
        boolean after = audio.isStreamMute(AudioManager.STREAM_MUSIC);
        if (!reached) {
            return failure("UNAVAILABLE", "Requested " + (muted ? "mute" : "unmute")
                    + " but the music stream is still " + (after ? "muted" : "unmuted") + ".");
        }
        JSONObject result = new JSONObject();
        result.put("muted", after);
        result.put("muted_before", before);
        result.put("changed", before != after);
        result.put("volume", volumePercent(audio));
        result.put("verified", true);
        return success(result);
    }

    // endregion

    // region verification

    private boolean awaitAudioActive(AudioManager audio, boolean expected) {
        return await(() -> audio.isMusicActive() == expected);
    }

    private boolean awaitMute(AudioManager audio, boolean expected) {
        return await(() -> audio.isStreamMute(AudioManager.STREAM_MUSIC) == expected);
    }

    /** Returns the new video id, or null if it did not change within the window. */
    private String awaitVideoIdChange(String before) {
        final String[] observed = new String[1];
        await(() -> {
            String current = videoId();
            if (current == null || current.equals(before)) return false;
            observed[0] = current;
            return true;
        });
        return observed[0];
    }

    /**
     * Bounded polling. The binder thread is held for at most {@link #VERIFY_TIMEOUT_MILLISECONDS};
     * an interrupt ends the wait immediately rather than swallowing it.
     */
    private static boolean await(Condition condition) {
        long deadline = System.currentTimeMillis() + VERIFY_TIMEOUT_MILLISECONDS;
        while (true) {
            if (condition.test()) return true;
            if (System.currentTimeMillis() >= deadline) return false;
            try {
                Thread.sleep(VERIFY_INTERVAL_MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return condition.test();
            }
        }
    }

    private interface Condition { boolean test(); }

    // endregion

    // region helpers

    private void dispatchMediaKey(int keyCode) {
        sendMediaKey(KeyEvent.ACTION_DOWN, keyCode);
        sendMediaKey(KeyEvent.ACTION_UP, keyCode);
    }

    private void sendMediaKey(int action, int keyCode) {
        Context context = getContext();
        Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        mediaButtonIntent.setComponent(
                new ComponentName(context.getPackageName(), MEDIA_BUTTON_RECEIVER));
        mediaButtonIntent.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(action, keyCode));
        context.sendBroadcast(mediaButtonIntent);
    }

    private AudioManager audioManager() {
        Context context = getContext();
        return context == null ? null : context.getSystemService(AudioManager.class);
    }

    private AudioManager requireAudioManager() throws ApiException {
        AudioManager audio = audioManager();
        if (audio == null) throw new ApiException("UNAVAILABLE", "AudioManager is unavailable.");
        return audio;
    }

    /** Null when the VideoInformation hook has not populated an id, never an empty string. */
    private static String videoId() {
        try {
            String videoId = VideoInformation.getVideoId();
            return videoId == null || videoId.isEmpty() ? null : videoId;
        } catch (Throwable throwable) {
            return null;
        }
    }

    private static int volumePercent(AudioManager audio) {
        return percent(audio.getStreamVolume(AudioManager.STREAM_MUSIC),
                audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
    }

    private static int percent(int value, int maximum) {
        return maximum <= 0 ? 0 : Math.round(value * 100.0f / maximum);
    }

    private static boolean has(Bundle extras, String key) {
        return extras != null && extras.containsKey(key);
    }

    /**
     * The entire access control for this provider. It is declared exported="true" with no
     * android:permission, and every method routes through call(), which consults this first.
     *
     * Unlike the broadcast receiver this replaces, the identity here is real: call() executes in a
     * binder transaction, so Binder.getCallingUid() is the caller's uid and not this app's.
     *
     * Matching is by package name against a compile-time set, which is an identity only while the
     * real owner is installed. Signature pinning would close that at the cost of binding this APK
     * to a specific signing key — worth doing once the caller has a stable release key.
     *
     * The rejection is logged with the package that was refused. Without it a stale allowlist is
     * indistinguishable from a broken provider from the caller's side.
     */
    private boolean isAllowedCaller() {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) return true;
        Context context = getContext();
        if (context == null) return false;
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        if (packages == null) {
            Logger.printDebug(() -> "YouTube Music API refused uid " + uid + ": no packages for uid");
            return false;
        }
        for (String packageName : packages) if (ALLOWED_PACKAGES.contains(packageName)) return true;
        final String refused = TextUtils.join(",", packages);
        Logger.printDebug(() -> "YouTube Music API refused " + refused
                + "; allowed: " + TextUtils.join(",", ALLOWED_PACKAGES));
        return false;
    }

    private static Bundle success(JSONObject result) {
        Bundle bundle = new Bundle();
        bundle.putBoolean("ok", true);
        bundle.putInt("api_version", API_VERSION);
        bundle.putString("result_json", result.toString());
        return bundle;
    }

    private static Bundle failure(String code, String message) {
        Bundle bundle = new Bundle();
        bundle.putBoolean("ok", false);
        bundle.putInt("api_version", API_VERSION);
        bundle.putString("error_code", code);
        bundle.putString("error_message", message);
        return bundle;
    }

    private static final class ApiException extends Exception {
        final String code;
        ApiException(String code, String message) { super(message); this.code = code; }
    }

    // endregion
}
