package com.laurenz.wordextremist.model

import com.google.gson.annotations.SerializedName

data class CancelMatchmakingRequestData(
    @SerializedName("user_id")
    val userId: Int
)