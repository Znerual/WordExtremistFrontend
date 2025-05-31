package com.laurenz.wordextremist.model

// Matches the backend enum app/services/game_service.py > RoundEndReason
// Ensure the string values here EXACTLY match the .value of the backend enum members.
enum class RoundEndReason(val value: String) {
    REPEATED_WORD_MAX_MISTAKES("repeated_word_max_mistakes"),
    INVALID_WORD_MAX_MISTAKES("invalid_word_max_mistakes"),
    TIMEOUT_MAX_MISTAKES("timeout_max_mistakes"),
    DOUBLE_TIMEOUT("double_timeout"),
    OPPONENT_DISCONNECTED("opponent_disconnected"),
    MAX_ROUNDS_REACHED_OR_SCORE_LIMIT("max_rounds_reached_or_score_limit"),
    UNKNOWN("unknown"); // Fallback for unexpected reasons

    companion object {
        fun fromValue(value: String?): RoundEndReason {
            return entries.find { it.value == value } ?: UNKNOWN
        }
    }
}