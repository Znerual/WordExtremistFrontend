package com.laurenz.wordextremist.model

import com.google.gson.annotations.SerializedName

data class BackendToken(
    @SerializedName("access_token")
    val accessToken: String,

    @SerializedName("token_type")
    val tokenType: String,

    @SerializedName("user") // This annotation ensures Gson maps the "user" JSON key
    val user: UserPublic?,   // Add the user field, make it nullable for safety

    @SerializedName("expires_in")
    val expiresIn: Int?
)
