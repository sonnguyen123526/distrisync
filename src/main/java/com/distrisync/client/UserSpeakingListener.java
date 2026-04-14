package com.distrisync.client;

/**
 * Invoked when remote PCM audio arrives on the UDP relay so the UI can
 * highlight the active speaker.
 */
@FunctionalInterface
public interface UserSpeakingListener {

    /**
     * @param clientId session-scoped id of the peer sending audio (from the
     *                 36-byte relay header); never {@code null}, may be empty
     *                 if the prefix could not be decoded
     */
    void onUserSpeaking(String clientId);
}
