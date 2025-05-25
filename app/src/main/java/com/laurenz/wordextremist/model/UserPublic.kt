package com.laurenz.wordextremist.model

import com.google.gson.annotations.SerializedName // For mapping JSON keys if different

data class UserPublic(
    @SerializedName("id")
    val id: Int, // Your internal DB ID

    @SerializedName("username")
    val username: String?,

    @SerializedName("email")
    val email: String?,

    @SerializedName("client_provided_id") // If backend includes this in UserPublic response
    val clientProvidedId: String?,

    @SerializedName("play_games_player_id")
    val playGamesPlayerId: String?,

    @SerializedName("google_id") // If JSON key is google_id
    val googleId: String,

    @SerializedName("profile_pic_url")
    val profilePicUrl: String?,

    @SerializedName("is_active")
    val isActive: Boolean,

    @SerializedName("created_at")
    val createdAt: String, // Or use a Date/DateTime type with a custom Gson adapter

    @SerializedName("last_login_at")
    val lastLoginAt: String?
)
