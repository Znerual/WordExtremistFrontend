package com.laurenz.wordextremist.model

import com.google.gson.annotations.SerializedName

data class UserOptionalInfoUpdateRequest(
    val country: String?,
    @SerializedName("mother_tongue")
    val motherTongue: String?,
    @SerializedName("preferred_language")
    val preferredLanguage: String?,
    val birthday: String?,
    val gender: String?,
    @SerializedName("language_level")
    val languageLevel: String?
)
