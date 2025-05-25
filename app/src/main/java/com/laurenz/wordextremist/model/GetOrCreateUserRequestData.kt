package com.laurenz.wordextremist.model

import com.google.gson.annotations.SerializedName

data class GetOrCreateUserRequestData(
    @SerializedName("client_provided_id")
    val clientProvidedId: String,

    @SerializedName("username")
    val username: String? = null // Optional
)