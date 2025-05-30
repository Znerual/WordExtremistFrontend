package com.laurenz.wordextremist.model

import com.google.gson.annotations.SerializedName

data class BackendToken(
    @SerializedName("access_token")
    val access_token: String,

    @SerializedName("token_type")
    val token_type: String,

    @SerializedName("user") // This annotation ensures Gson maps the "user" JSON key
    val user: UserPublic?   // Add the user field, make it nullable for safety
)
