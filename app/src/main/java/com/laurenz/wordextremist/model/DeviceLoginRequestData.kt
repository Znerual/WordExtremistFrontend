package com.laurenz.wordextremist.model

import com.google.gson.annotations.SerializedName

data class DeviceLoginRequestData(
    @SerializedName("client_provided_id")
    val clientProvidedId: String,

    @SerializedName("client_generated_password")
    val clientGeneratedPassword: String
)