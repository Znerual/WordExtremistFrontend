package com.laurenz.wordextremist.model

import com.google.gson.annotations.SerializedName // For mapping JSON keys if different

data class UserPublic(
    val id: Int, // Your internal DB ID
    @SerializedName("google_id") // If JSON key is google_id
    val googleId: String,
    val email: String,
    val username: String?,
    @SerializedName("profile_pic_url")
    val profilePicUrl: String?,
    @SerializedName("is_active")
    val isActive: Boolean,
    @SerializedName("created_at")
    val createdAt: String, // Or use a Date/DateTime type with a custom Gson adapter
    @SerializedName("last_login_at")
    val lastLoginAt: String?
)
