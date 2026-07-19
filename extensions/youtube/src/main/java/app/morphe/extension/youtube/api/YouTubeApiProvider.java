/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.api;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.text.TextUtils;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.innertube.utils.AuthUtils;
import app.morphe.extension.youtube.patches.LoadVideoPatch;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.patches.utils.requests.GetSuggestionsRequest;
import app.morphe.extension.youtube.shared.PlayerType;
import app.morphe.extension.youtube.shared.VideoState;

/**
 * External playback bridge for Voice Assistant.
 *
 * <p>This exists because the sibling {@code YouTubeControlReceiver} broadcast API cannot be made
 * safe or honest. A {@link android.content.BroadcastReceiver} cannot identify its sender —
 * {@link Binder#getCallingUid()} inside {@code onReceive} returns the receiver's own uid — and a
 * broadcast send returns before anything has happened, so "success" only ever meant "the send did
 * not throw". A ContentProvider {@link #call} runs inside a real binder transaction, so the caller
 * uid is real, and it is synchronous, so every mutation here re-reads playback state and reports
 * what actually happened rather than what was requested.</p>
 *
 * <p>Only {@link #call} is implemented. Every URI operation throws.</p>
 */
@SuppressWarnings("unused")
public final class YouTubeApiProvider extends ContentProvider {
    private static final int API_VERSION = 1;

    /**
     * Total budget for any verification poll. Chosen to stay well under the binder transaction
     * timeout: a provider call that blocks forever blocks the caller's binder thread too.
     */
    private static final long VERIFY_TIMEOUT_MS = 2_000L;
    private static final long VERIFY_INTERVAL_MS = 50L;
    /** Opening a video tears down and rebuilds the player, so it is allowed a longer budget. */
    private static final long OPEN_TIMEOUT_MS = 6_000L;
    private static final long MAIN_THREAD_TIMEOUT_MS = 2_000L;
    /**
     * Budget for the one operation here that goes to the network. Same discipline as the others:
     * a provider call runs on the caller's binder thread, so this must return with an error rather
     * than block indefinitely on a slow or absent network.
     */
    private static final long SUGGESTIONS_TIMEOUT_MS = 5_000L;

    private static final int DEFAULT_SUGGESTION_LIMIT = 5;
    private static final int MAX_SUGGESTION_LIMIT = 20;

    private static final float MIN_SPEED = 0.05f;
    private static final float MAX_SPEED = 8.0f;

    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final Pattern PLAYLIST_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{2,64}");

    private static final Set<String> ALLOWED_PACKAGES = new HashSet<>(Arrays.asList(
            "app.ripthulhu.voiceassistant"));

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
                case "open_video":
                    result = openVideo(extras);
                    break;
                case "play":
                    result = play();
                    break;
                case "pause":
                    result = pause();
                    break;
                case "next":
                    result = skip(KeyEvent.KEYCODE_MEDIA_NEXT, "next");
                    break;
                case "previous":
                    result = skip(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "previous");
                    break;
                case "seek":
                    result = seek(extras);
                    break;
                case "set_speed":
                    result = setSpeed(extras);
                    break;
                case "volume":
                    result = volume(extras);
                    break;
                case "list_suggestions":
                    result = listSuggestions(extras);
                    break;
                default:
                    return failure("VALIDATION", "Unknown method.");
            }
            return success(result);
        } catch (ApiException exception) {
            return failure(exception.code, exception.getMessage());
        } catch (SecurityException exception) {
            return failure("FORBIDDEN", "YouTube rejected the request.");
        } catch (Exception exception) {
            Logger.printDebug(() -> "YouTube API operation failed: "
                    + exception.getClass().getSimpleName());
            // The class name goes in the reply, not just the log. Logger.printDebug only emits when
            // Morphe's debug logging is enabled, so an INTERNAL reaching the caller was previously
            // an opaque dead end — the caller could see that something failed and never what. The
            // caller is an allowlisted app, so there is nobody to leak this to.
            return failure("INTERNAL", "YouTube API operation failed: "
                    + exception.getClass().getSimpleName());
        }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }

    // region methods

    /**
     * Reports what this build can actually do. Fields that cannot be sourced are reported as
     * false rather than emitted as null or a plausible-looking empty string, because the caller
     * cannot see the screen and has no other way to tell an absent field from an empty one.
     */
    private JSONObject capabilities() throws Exception {
        JSONObject result = new JSONObject();
        result.put("api_version", API_VERSION);
        result.put("operations", new JSONArray(Arrays.asList(
                "capabilities", "state", "open_video", "play", "pause",
                "next", "previous", "seek", "set_speed", "volume", "list_suggestions")));

        JSONObject fields = new JSONObject();
        fields.put("video_id", true);
        // No hook in this extension captures the video title. VideoInformation.initialize reads one
        // into a local and discards it. Reporting false rather than inventing a scrape.
        fields.put("title", false);
        fields.put("channel_id", true);
        fields.put("channel_name", true);
        fields.put("playlist_id", true);
        fields.put("position_ms", true);
        fields.put("duration_ms", true);
        fields.put("speed", true);
        fields.put("playing", true);
        fields.put("at_end", true);
        fields.put("music_active", true);
        fields.put("video_state", true);
        fields.put("player_type", true);
        fields.put("volume", true);
        result.put("fields", fields);

        JSONObject notes = new JSONObject();
        // play/pause/next/previous have no bytecode hook; they go through the media session.
        notes.put("transport_via_media_key", true);
        notes.put("verified_mutations", true);
        notes.put("verify_timeout_ms", VERIFY_TIMEOUT_MS);
        notes.put("open_timeout_ms", OPEN_TIMEOUT_MS);
        // Every other operation here is a local read or a media-key dispatch. list_suggestions is
        // the exception: it performs a network call to InnerTube, so callers should expect latency
        // (a cold call typically takes a second or more) and must not treat it as instant.
        notes.put("list_suggestions_network", true);
        notes.put("list_suggestions_timeout_ms", SUGGESTIONS_TIMEOUT_MS);
        notes.put("list_suggestions_default_limit", DEFAULT_SUGGESTION_LIMIT);
        notes.put("list_suggestions_max_limit", MAX_SUGGESTION_LIMIT);
        result.put("notes", notes);
        return result;
    }

    /**
     * Reads every field independently, so one unreadable field cannot empty the whole reply.
     *
     * This was originally a straight sequence of puts, and any single throw turned the entire call
     * into INTERNAL. That is precisely backwards for a state read: the caller polls this to decide
     * whether the video it asked for is playing, and losing `video_id` because `speed` was
     * unreadable makes a working player indistinguishable from a broken one. Observed on device —
     * state succeeded while nothing was playing and failed once a video opened, which is the one
     * moment the answer matters.
     *
     * Fields that cannot be read are reported as null and named in `unavailable`, so a caller can
     * tell "not playing" from "could not tell".
     */
    private JSONObject state() throws Exception {
        JSONObject result = new JSONObject();
        JSONArray unavailable = new JSONArray();
        result.put("title", JSONObject.NULL); // Not available; see capabilities().

        putSafe(result, unavailable, "video_id", () -> nullIfEmpty(VideoInformation.getVideoId()));
        putSafe(result, unavailable, "channel_id", () -> nullIfEmpty(VideoInformation.getChannelId()));
        putSafe(result, unavailable, "channel_name", () -> nullIfEmpty(VideoInformation.getChannelName()));
        putSafe(result, unavailable, "playlist_id", () -> nullIfEmpty(VideoInformation.getPlaylistId()));
        putSafe(result, unavailable, "position_ms", () -> {
            long position = VideoInformation.getVideoTime();
            return position < 0 ? JSONObject.NULL : position;
        });
        putSafe(result, unavailable, "duration_ms", () -> {
            long duration = VideoInformation.getVideoLength();
            return duration > 0 ? duration : JSONObject.NULL;
        });
        putSafe(result, unavailable, "speed", () -> {
            // JSONObject rejects NaN and infinity outright, and an uninitialised speed can be
            // either, so this field alone could take the whole response down.
            float speed = VideoInformation.getPlaybackSpeed();
            return (Float.isNaN(speed) || Float.isInfinite(speed)) ? JSONObject.NULL : (double) speed;
        });
        putSafe(result, unavailable, "video_state", () -> {
            VideoState videoState = VideoState.getCurrent();
            return videoState == null ? JSONObject.NULL : videoState.name();
        });
        putSafe(result, unavailable, "playing", () -> VideoState.getCurrent() == VideoState.PLAYING);
        putSafe(result, unavailable, "at_end", VideoInformation::isAtEndOfVideo);
        putSafe(result, unavailable, "music_active", this::isMusicActive);
        putSafe(result, unavailable, "is_short", VideoInformation::lastVideoIdIsShort);
        putSafe(result, unavailable, "player_type", () -> {
            PlayerType playerType = PlayerType.getCurrent();
            return playerType == null ? JSONObject.NULL : playerType.name();
        });
        putSafe(result, unavailable, "volume", this::volumePercent);

        result.put("unavailable", unavailable);
        return result;
    }

    private interface FieldReader {
        Object read() throws Exception;
    }

    /** Puts one field, degrading to null and a note in [unavailable] rather than failing the call. */
    private void putSafe(JSONObject result, JSONArray unavailable, String name, FieldReader reader) {
        putSafeNamed(result, unavailable, name, name, reader);
    }

    /**
     * As {@link #putSafe}, but the name recorded in [unavailable] can differ from the JSON key.
     * Needed for list elements, where three suggestions each losing a "title" would otherwise be
     * reported as an indistinguishable "title" three times.
     */
    private void putSafeNamed(JSONObject result, JSONArray unavailable, String key,
                              String reportedName, FieldReader reader) {
        Object value;
        try {
            value = reader.read();
        } catch (Throwable error) {
            Logger.printDebug(() -> "YouTube API field '" + reportedName + "' unreadable: " + error);
            unavailable.put(reportedName);
            value = JSONObject.NULL;
        }
        try {
            result.put(key, value);
        } catch (JSONException error) {
            unavailable.put(reportedName);
        }
    }

    private JSONObject openVideo(Bundle extras) throws Exception {
        final String videoId = requiredString(extras, "video_id");
        if (!VIDEO_ID_PATTERN.matcher(videoId).matches()) throw validation("Invalid video_id.");

        String playlistId = optionalString(extras, "playlist_id", null);
        if (playlistId != null) {
            if (!PLAYLIST_ID_PATTERN.matcher(playlistId).matches()) throw validation("Invalid playlist_id.");
        }
        Integer startSeconds = optionalInt(extras, "start_seconds");
        if (startSeconds != null && (startSeconds < 0 || startSeconds > 86_400)) {
            throw validation("Invalid start_seconds.");
        }

        StringBuilder parameters = new StringBuilder(videoId);
        if (playlistId != null) parameters.append("&list=").append(playlistId);
        if (startSeconds != null && startSeconds > 0) parameters.append("&t=").append(startSeconds).append('s');
        final String videoIdWithParams = parameters.toString();

        final String previousVideoId = VideoInformation.getVideoId();
        onMainThread(() -> {
            LoadVideoPatch.openVideoWithInternalIntent(videoIdWithParams);
            return null;
        });

        final boolean opened = await(() -> videoId.equals(VideoInformation.getVideoId()), OPEN_TIMEOUT_MS);
        JSONObject result = state();
        result.put("requested_video_id", videoId);
        result.put("previous_video_id", nullIfEmpty(previousVideoId));
        result.put("changed", opened);
        if (!opened) {
            throw unavailable("Open was dispatched but the player is still on "
                    + describe(VideoInformation.getVideoId()) + " after "
                    + OPEN_TIMEOUT_MS + "ms; the video did not open.");
        }
        return result;
    }

    private JSONObject play() throws Exception {
        if (alreadyPlaying()) {
            JSONObject result = state();
            result.put("changed", false);
            result.put("already_in_state", true);
            return result;
        }
        dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY);
        final boolean reached = await(this::alreadyPlaying, VERIFY_TIMEOUT_MS);
        if (!reached) {
            throw unavailable("Play key was dispatched but playback did not start within "
                    + VERIFY_TIMEOUT_MS + "ms (video_state="
                    + describeState() + ", music_active=" + isMusicActive() + ").");
        }
        JSONObject result = state();
        result.put("changed", true);
        return result;
    }

    private JSONObject pause() throws Exception {
        if (alreadyPaused()) {
            JSONObject result = state();
            result.put("changed", false);
            result.put("already_in_state", true);
            return result;
        }
        dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE);
        final boolean reached = await(this::alreadyPaused, VERIFY_TIMEOUT_MS);
        if (!reached) {
            throw unavailable("Pause key was dispatched but playback did not stop within "
                    + VERIFY_TIMEOUT_MS + "ms (video_state="
                    + describeState() + ", music_active=" + isMusicActive() + ").");
        }
        JSONObject result = state();
        result.put("changed", true);
        return result;
    }

    private JSONObject skip(int keyCode, String name) throws Exception {
        final String previousVideoId = VideoInformation.getVideoId();
        dispatchMediaKey(keyCode);
        final boolean changed = await(
                () -> !previousVideoId.equals(VideoInformation.getVideoId()), VERIFY_TIMEOUT_MS);
        if (!changed) {
            throw unavailable("The " + name + " key was dispatched but the video is still "
                    + describe(previousVideoId) + " after " + VERIFY_TIMEOUT_MS
                    + "ms; there may be no queue, or the player did not respond.");
        }
        JSONObject result = state();
        result.put("previous_video_id", nullIfEmpty(previousVideoId));
        result.put("changed", true);
        return result;
    }

    private JSONObject seek(Bundle extras) throws Exception {
        final boolean hasPosition = has(extras, "position_ms");
        final boolean hasDelta = has(extras, "delta_ms");
        if (hasPosition == hasDelta) throw validation("Supply exactly one of position_ms or delta_ms.");

        final long before = VideoInformation.getVideoTime();
        if (before < 0) throw unavailable("No player is available to seek.");
        final long duration = VideoInformation.getVideoLength();

        final long target;
        if (hasPosition) {
            final long position = requiredLong(extras, "position_ms");
            if (position < 0) throw validation("Invalid position_ms.");
            if (duration > 0 && position > duration) throw validation("position_ms is past the end of the video.");
            target = position;
            Boolean seeked = onMainThread(() -> VideoInformation.seekTo(position));
            if (seeked == null || !seeked) {
                // seekTo returns false for a real refusal (no controller, or a seek inside the
                // final 250ms which it deliberately ignores). Do not paper over it.
                throw unavailable("seekTo refused the request; playback is still at "
                        + VideoInformation.getVideoTime() + "ms.");
            }
        } else {
            final long delta = requiredLong(extras, "delta_ms");
            if (delta < -86_400_000L || delta > 86_400_000L) throw validation("Invalid delta_ms.");
            target = Math.max(0, before + delta);
            onMainThread(() -> {
                VideoInformation.seekToRelative(delta);
                return null;
            });
        }

        // A tolerance is required: the player snaps to keyframes and seekTo clamps near the end.
        final long tolerance = 1_500L;
        final boolean moved = await(() -> {
            final long now = VideoInformation.getVideoTime();
            return now >= 0 && Math.abs(now - target) <= tolerance;
        }, VERIFY_TIMEOUT_MS);

        final long after = VideoInformation.getVideoTime();
        if (!moved) {
            throw unavailable("Seek was dispatched but playback is at " + after
                    + "ms, not the requested " + target + "ms (tolerance " + tolerance + "ms).");
        }
        JSONObject result = state();
        result.put("requested_position_ms", target);
        result.put("previous_position_ms", before);
        result.put("changed", after != before);
        return result;
    }

    private JSONObject setSpeed(Bundle extras) throws Exception {
        final float speed = requiredFloat(extras, "speed");
        if (!(speed >= MIN_SPEED) || speed > MAX_SPEED) throw validation("Invalid speed.");

        final float before = VideoInformation.getPlaybackSpeed();
        onMainThread(() -> {
            VideoInformation.overridePlaybackSpeed(speed);
            return null;
        });

        final boolean applied = await(
                () -> Math.abs(VideoInformation.getPlaybackSpeed() - speed) < 0.01f, VERIFY_TIMEOUT_MS);
        final float after = VideoInformation.getPlaybackSpeed();
        if (!applied) {
            throw unavailable("Speed override was dispatched but playback speed is still "
                    + after + ", not the requested " + speed + ".");
        }
        JSONObject result = state();
        result.put("requested_speed", (double) speed);
        result.put("previous_speed", (double) before);
        result.put("changed", Math.abs(after - before) >= 0.01f);
        return result;
    }

    private JSONObject volume(Bundle extras) throws Exception {
        AudioManager audioManager = audioManager();
        if (audioManager == null) throw unavailable("AudioManager is unavailable.");
        final int maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if (maximum <= 0) throw unavailable("The music stream reports no volume range.");

        if (!has(extras, "level")) {
            // Read-only form.
            JSONObject result = new JSONObject();
            result.put("level", volumePercent());
            result.put("index", audioManager.getStreamVolume(AudioManager.STREAM_MUSIC));
            result.put("max_index", maximum);
            result.put("muted", audioManager.isStreamMute(AudioManager.STREAM_MUSIC));
            result.put("changed", false);
            return result;
        }

        final int level = requiredInt(extras, "level");
        if (level < 0 || level > 100) throw validation("Invalid level.");
        final int previousIndex = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        final int targetIndex = Math.round(maximum * (level / 100.0f));
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetIndex, 0);

        final boolean reached = await(
                () -> audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == targetIndex,
                VERIFY_TIMEOUT_MS);
        final int actualIndex = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        if (!reached) {
            throw unavailable("Volume was set to index " + targetIndex + " of " + maximum
                    + " but the stream reports " + actualIndex
                    + "; a volume policy or fixed-volume device may have rejected it.");
        }
        JSONObject result = new JSONObject();
        result.put("requested_level", level);
        result.put("level", Math.round(actualIndex * 100.0f / maximum));
        result.put("index", actualIndex);
        result.put("max_index", maximum);
        result.put("muted", audioManager.isStreamMute(AudioManager.STREAM_MUSIC));
        result.put("changed", actualIndex != previousIndex);
        return result;
    }

    /**
     * Related videos for the current (or an explicitly named) video.
     *
     * <p>This is the only operation that touches the network, so unlike the local reads it is
     * bounded by {@link #SUGGESTIONS_TIMEOUT_MS} and reports a timeout as UNAVAILABLE rather than
     * as an empty list — "there is nothing to suggest" and "we could not find out" are different
     * answers, and a voice caller reading the second aloud as the first would be lying.</p>
     *
     * <p>Fields degrade individually via {@link #putSafe}: the title/channel split in the upstream
     * response is positional and unnamed, so a missing channel must cost that field only.</p>
     */
    private JSONObject listSuggestions(Bundle extras) throws Exception {
        String requested = optionalString(extras, "video_id", null);
        if (requested != null && !VIDEO_ID_PATTERN.matcher(requested).matches()) {
            throw validation("Invalid video_id.");
        }

        final String sourceVideoId;
        if (requested != null) {
            sourceVideoId = requested;
        } else {
            String current = VideoInformation.getVideoId();
            if (current == null || current.isEmpty()) {
                throw validation("No video is playing; supply video_id to list suggestions for a "
                        + "specific video.");
            }
            sourceVideoId = current;
        }

        int limit = DEFAULT_SUGGESTION_LIMIT;
        if (has(extras, "limit")) {
            limit = requiredInt(extras, "limit");
            // A voice caller reads these aloud, so an unbounded list is unusable rather than
            // merely large.
            if (limit < 1 || limit > MAX_SUGGESTION_LIMIT) {
                throw validation("Invalid limit; expected 1 to " + MAX_SUGGESTION_LIMIT + ".");
            }
        }

        // Sent anonymously, deliberately. AuthUtils holds credentials issued for the ANDROID client
        // this app runs as, but the suggestions request has to use a WEB client context — the
        // ANDROID context returns the related shelf as opaque Litho element blobs with no video ids
        // in them, measured as 87 elementRenderer and zero parseable renderers. Presenting ANDROID
        // credentials against a WEB context is what the endpoint rejected with HTTP 400 on device;
        // the identical request without them returns 200.
        //
        // The cost is that suggestions are not personalised. That is acceptable here because this
        // list was never going to match the on-screen one anyway — it is a separate query from the
        // one the app rendered with — so the caller is expected to read its list aloud and let the
        // user choose from what they heard, rather than implying screen correspondence.
        List<GetSuggestionsRequest.Suggestion> suggestions =
                GetSuggestionsRequest.fetchRequestIfNeeded(sourceVideoId, Collections.emptyMap())
                        .getSuggestions(SUGGESTIONS_TIMEOUT_MS);
        if (suggestions == null) {
            // The failed request is cached by video id, so drop it rather than pinning the failure
            // for the lifetime of the process.
            GetSuggestionsRequest.clear();
            throw unavailable("Suggestions for " + sourceVideoId + " could not be fetched within "
                    + SUGGESTIONS_TIMEOUT_MS + "ms; the request failed or timed out.");
        }

        JSONObject result = new JSONObject();
        JSONArray unavailable = new JSONArray();
        result.put("video_id", sourceVideoId);

        JSONArray array = new JSONArray();
        final int count = Math.min(limit, suggestions.size());
        for (int i = 0; i < count; i++) {
            GetSuggestionsRequest.Suggestion suggestion = suggestions.get(i);
            JSONObject entry = new JSONObject();
            // 1-based: this is read aloud, and people count from one.
            entry.put("index", i + 1);
            entry.put("video_id", suggestion.videoId);
            // Named by position so the caller can tell WHICH suggestion lost a field; a bare
            // "title" repeated three times would be useless.
            final String prefix = "suggestions[" + (i + 1) + "].";
            // A null here is a parse miss, not a legitimate "no value", so it is reported as
            // unavailable rather than silently emitted as null.
            putSafeNamed(entry, unavailable, "title", prefix + "title",
                    () -> parsed(suggestion.title, "title"));
            putSafeNamed(entry, unavailable, "channel", prefix + "channel",
                    () -> parsed(suggestion.channel, "channel"));
            putSafeNamed(entry, unavailable, "duration", prefix + "duration",
                    () -> parsed(suggestion.duration, "duration"));
            array.put(entry);
        }
        result.put("suggestions", array);
        result.put("total_available", suggestions.size());
        result.put("unavailable", unavailable);
        return result;
    }

    // endregion

    // region playback plumbing

    /**
     * No bytecode hook exists for transport control, so play/pause/next/previous go through the
     * media session that YouTube already owns. {@code AudioManager.dispatchMediaKeyEvent} routes to
     * whichever session is active; it is used rather than a broadcast to a hardcoded
     * MediaButtonReceiver class name because YouTube does not ship the androidx receiver that
     * YouTubeMusicControlReceiver targets.
     */
    private void dispatchMediaKey(int keyCode) throws ApiException {
        AudioManager audioManager = audioManager();
        if (audioManager == null) throw unavailable("AudioManager is unavailable.");
        try {
            audioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
            audioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
        } catch (RuntimeException exception) {
            throw unavailable("Media key dispatch failed: " + exception.getClass().getSimpleName());
        }
    }

    private boolean alreadyPlaying() {
        return VideoState.getCurrent() == VideoState.PLAYING || isMusicActive();
    }

    private boolean alreadyPaused() {
        VideoState videoState = VideoState.getCurrent();
        return (videoState == VideoState.PAUSED || videoState == VideoState.ENDED) && !isMusicActive();
    }

    private boolean isMusicActive() {
        AudioManager audioManager = audioManager();
        return audioManager != null && audioManager.isMusicActive();
    }

    private Object volumePercent() {
        AudioManager audioManager = audioManager();
        if (audioManager == null) return JSONObject.NULL;
        final int maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if (maximum <= 0) return JSONObject.NULL;
        return Math.round(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100.0f / maximum);
    }

    private AudioManager audioManager() {
        Context context = getContext();
        return context == null ? null : (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    private String describeState() {
        VideoState videoState = VideoState.getCurrent();
        return videoState == null ? "unknown" : videoState.name();
    }

    private static String describe(String videoId) {
        return videoId == null || videoId.isEmpty() ? "no video" : videoId;
    }

    /**
     * Polls a condition on a fixed budget. Every mutation above uses this instead of trusting the
     * dispatch, and every caller treats a timeout as a failure rather than as success.
     */
    private static boolean await(Condition condition, long timeoutMs) {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            try {
                if (condition.test()) return true;
            } catch (RuntimeException ignored) {
                // Treat a transient read failure as "not yet".
            }
            if (System.currentTimeMillis() >= deadline) return false;
            try {
                Thread.sleep(VERIFY_INTERVAL_MS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /**
     * VideoInformation.seekTo/seekToRelative/overridePlaybackSpeed and the activity launch in
     * LoadVideoPatch all require the main thread, but call() arrives on a binder thread. The wait
     * is bounded so a wedged main thread returns an error instead of holding the caller's binder
     * thread open.
     */
    private static <T> T onMainThread(Callable<T> work) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return work.call();

        final ArrayBlockingQueue<Object[]> queue = new ArrayBlockingQueue<>(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            Object[] outcome;
            try {
                outcome = new Object[]{work.call(), null};
            } catch (Throwable throwable) {
                outcome = new Object[]{null, throwable};
            }
            queue.offer(outcome);
        });

        Object[] outcome = queue.poll(MAIN_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        if (outcome == null) {
            throw new ApiException("UNAVAILABLE", "The player did not respond within "
                    + MAIN_THREAD_TIMEOUT_MS + "ms.");
        }
        if (outcome[1] != null) {
            Throwable cause = (Throwable) outcome[1];
            if (cause instanceof Exception) throw (Exception) cause;
            throw new ApiException("INTERNAL", "Player call failed: " + cause.getClass().getSimpleName());
        }
        @SuppressWarnings("unchecked") T value = (T) outcome[0];
        return value;
    }

    private interface Condition {
        boolean test();
    }

    // endregion

    /**
     * The entire access control for this provider. It is declared exported="true" with no
     * android:permission, and call() consults this first; the URI operations are unreachable
     * because they throw unconditionally.
     *
     * This is the reason the provider exists at all. The equivalent check is impossible in the
     * broadcast API this supersedes: Binder.getCallingUid() inside BroadcastReceiver.onReceive()
     * returns the receiver's own uid, so a receiver cannot tell who sent to it. A provider call()
     * runs inside a real binder transaction, so the uid below is the caller's.
     *
     * Matching is by package name against a compile-time set, the same trade-off as
     * KeepApiProvider: adequate while the real owner is installed, but signature pinning would be
     * stronger once the caller has a stable release key.
     *
     * Refusals are logged with the refused package, otherwise a stale allowlist is
     * indistinguishable from a broken provider — every method returns the same FORBIDDEN.
     */
    private boolean isAllowedCaller() {
        final int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) return true;
        Context context = getContext();
        if (context == null) return false;
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        if (packages == null) {
            Logger.printDebug(() -> "YouTube API refused uid " + uid + ": no packages for uid");
            return false;
        }
        for (String packageName : packages) if (ALLOWED_PACKAGES.contains(packageName)) return true;
        final String refused = TextUtils.join(",", packages);
        Logger.printDebug(() -> "YouTube API refused " + refused
                + "; allowed: " + TextUtils.join(",", ALLOWED_PACKAGES));
        return false;
    }

    // region extras

    private static boolean has(Bundle extras, String key) {
        return extras != null && extras.containsKey(key);
    }

    private static String requiredString(Bundle extras, String key) throws ApiException {
        String value = optionalString(extras, key, null);
        if (value == null) throw validation("Missing " + key + ".");
        return value;
    }

    private static String optionalString(Bundle extras, String key, String fallback) throws ApiException {
        if (!has(extras, key)) return fallback;
        Object value = extras.get(key);
        if (!(value instanceof String)) throw validation("Invalid " + key + ".");
        return (String) value;
    }

    private static Integer optionalInt(Bundle extras, String key) throws ApiException {
        if (!has(extras, key)) return null;
        return requiredInt(extras, key);
    }

    private static int requiredInt(Bundle extras, String key) throws ApiException {
        Object value = extras == null ? null : extras.get(key);
        if (!(value instanceof Integer)) throw validation("Invalid " + key + ".");
        return (Integer) value;
    }

    private static long requiredLong(Bundle extras, String key) throws ApiException {
        Object value = extras == null ? null : extras.get(key);
        if (value instanceof Long) return (Long) value;
        if (value instanceof Integer) return ((Integer) value).longValue();
        throw validation("Invalid " + key + ".");
    }

    private static float requiredFloat(Bundle extras, String key) throws ApiException {
        Object value = extras == null ? null : extras.get(key);
        if (value instanceof Float) return (Float) value;
        if (value instanceof Double) return ((Double) value).floatValue();
        if (value instanceof Integer) return ((Integer) value).floatValue();
        throw validation("Invalid " + key + ".");
    }

    /**
     * Asserts a field was actually parsed out of the upstream response. Throwing here routes the
     * miss through {@link #putSafeNamed}, so the field lands as null AND is named in `unavailable`.
     */
    private static Object parsed(String value, String field) {
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("no " + field + " in lockup");
        }
        return value;
    }

    private static Object nullIfEmpty(String value) {
        return value == null || value.isEmpty() ? JSONObject.NULL : value;
    }

    // endregion

    private static ApiException validation(String message) { return new ApiException("VALIDATION", message); }
    private static ApiException unavailable(String message) { return new ApiException("UNAVAILABLE", message); }

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

        ApiException(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
