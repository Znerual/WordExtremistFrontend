package com.laurenz.wordextremist

import android.app.Activity
import android.app.DatePickerDialog
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.laurenz.wordextremist.R
import com.laurenz.wordextremist.auth.AuthManager
import com.laurenz.wordextremist.databinding.ActivityEditProfileBinding
import com.laurenz.wordextremist.network.ApiClient
import com.laurenz.wordextremist.model.UserOptionalInfoUpdateRequest
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.FileOutputStream
import java.util.Calendar
import android.widget.ArrayAdapter
import java.util.Locale

data class CountryItem(val code: String, val name: String) {
    // This will be displayed in the dropdown
    override fun toString(): String {
        return "${getCountryFlagEmoji(code)} $name"
    }
}

data class LanguageItem(val code: String, val name: String) {
    override fun toString(): String = name
}

// Helper function to convert 2-letter country code to flag emoji
fun getCountryFlagEmoji(countryCode: String): String {
    if (countryCode.length != 2) return "🏳️"
    val firstLetter = Character.codePointAt(countryCode, 0) - 0x41 + 0x1F1E6
    val secondLetter = Character.codePointAt(countryCode, 1) - 0x41 + 0x1F1E6
    return String(Character.toChars(firstLetter)) + String(Character.toChars(secondLetter))
}

class EditProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditProfileBinding
    private lateinit var imagePickerLauncher: ActivityResultLauncher<String>

    private var originalUsername: String? = null
    private var originalBirthday: String? = null
    private var originalGender: String? = null
    private var originalCountryCode: String? = null
    private var originalMotherTongueCode: String? = null
    private var originalPreferredLanguageCode: String? = null
    private var originalLanguageLevel: String? = null

    private var newProfilePicUri: Uri? = null
    private var selectedCountryCode: String? = null
    private var selectedMotherTongueCode: String? = null
    private var selectedPreferredLanguageCode: String? = null
    private var selectedLanguageLevel: String? = null

    private lateinit var countries: List<CountryItem>
    private lateinit var languages: List<LanguageItem>



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
        initImagePicker()
        setupDatePicker()
        setupDropdowns()
        loadUserProfile()
    }

    private fun setupDropdowns() {
        // --- Populate Country Dropdown ---
        countries = getCountries()
        val countryAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, countries)
        binding.autoCompleteCountry.setAdapter(countryAdapter)
        binding.autoCompleteCountry.setOnItemClickListener { _, _, position, _ ->
            selectedCountryCode = countries[position].code
        }

        // --- Populate Language Dropdowns ---
        languages = getLanguages()
        val languageAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, languages)
        binding.autoCompleteMotherTongue.setAdapter(languageAdapter)
        binding.autoCompletePreferredLanguage.setAdapter(languageAdapter)

        binding.autoCompleteMotherTongue.setOnItemClickListener { _, _, position, _ ->
            selectedMotherTongueCode = languages[position].code
        }
        binding.autoCompletePreferredLanguage.setOnItemClickListener { _, _, position, _ ->
            selectedPreferredLanguageCode = languages[position].code
        }

        // --- Populate Language Level Dropdown ---
        val levels = resources.getStringArray(R.array.language_levels)
        val levelAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, levels)
        binding.autoCompleteLanguageLevel.setAdapter(levelAdapter)
        binding.autoCompleteLanguageLevel.setOnItemClickListener { _, _, position, _ ->
            selectedLanguageLevel = levels[position]
        }
    }


    private fun setupListeners() {
        binding.toolbarEditProfile.setNavigationOnClickListener {
            finish()
        }

        binding.buttonChangePicture.setOnClickListener {
            // Launch the image picker
            imagePickerLauncher.launch("image/*")
        }

        binding.buttonSaveChanges.setOnClickListener {
            // Validate input before saving
            val newUsername = binding.textInputEditTextUsername.text.toString().trim()
            if (newUsername.isEmpty()) {
                binding.textInputLayoutUsername.error = "Username cannot be empty"
                return@setOnClickListener
            } else {
                binding.textInputLayoutUsername.error = null
            }
            saveChanges()
        }
    }

    private fun initImagePicker() {
        imagePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let {
                Log.d("EditProfileActivity", "Image selected: $it")
                newProfilePicUri = it
                // Display the newly selected image
                Glide.with(this)
                    .load(it)
                    .placeholder(R.drawable.ic_avatar_placeholder)
                    .into(binding.imageViewProfilePic)
            }
        }
    }
    private fun setupDatePicker() {
        binding.textInputEditTextBirthday.setOnClickListener {
            val calendar = Calendar.getInstance()
            // Try to parse existing date to set the dialog's initial date
            if (!originalBirthday.isNullOrBlank()) {
                try {
                    val parts = originalBirthday!!.split("-") // We can use !! because we already checked for null/blank
                    if (parts.size == 3) {
                        // Set the calendar to the user's existing birthday
                        calendar.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
                    }
                } catch (e: Exception) {
                    // Log if parsing fails, but don't crash. The dialog will just show the current date.
                    Log.w("EditProfile", "Could not parse original birthday: $originalBirthday", e)
                }
            }

            val datePickerDialog = DatePickerDialog(
                this,
                { _, year, month, dayOfMonth ->
                    // Format to YYYY-MM-DD which matches the backend's Date format
                    val selectedDate = String.format("%04d-%02d-%02d", year, month + 1, dayOfMonth)
                    binding.textInputEditTextBirthday.setText(selectedDate)
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            )
            // Prevent selecting a future date
            datePickerDialog.datePicker.maxDate = System.currentTimeMillis()
            datePickerDialog.show()
        }
    }


    private fun loadUserProfile() {
        showLoading(true)
        lifecycleScope.launch {
            if (!AuthManager.ensureValidToken(this@EditProfileActivity)) {
                Toast.makeText(this@EditProfileActivity, "Authentication failed. Please restart.", Toast.LENGTH_LONG).show()
                showLoading(false)
                finish()
                return@launch
            }

            try {
                val response = ApiClient.instance.getMyProfile()
                if (response.isSuccessful && response.body() != null) {
                    val user = response.body()!!
                    originalUsername = user.username
                    originalBirthday = user.birthday
                    originalGender = user.gender
                    originalCountryCode = user.country
                    originalMotherTongueCode = user.motherTongue
                    originalPreferredLanguageCode = user.preferredLanguage
                    originalLanguageLevel = user.languageLevel

                    selectedCountryCode = originalCountryCode
                    selectedMotherTongueCode = originalMotherTongueCode
                    selectedPreferredLanguageCode = originalPreferredLanguageCode
                    selectedLanguageLevel = originalLanguageLevel

                    binding.textInputEditTextUsername.setText(originalUsername)
                    binding.textInputEditTextBirthday.setText(originalBirthday)

                    when (originalGender?.lowercase()) {
                        "male" -> binding.toggleGroupGender.check(R.id.buttonMale)
                        "female" -> binding.toggleGroupGender.check(R.id.buttonFemale)
                        "other" -> binding.toggleGroupGender.check(R.id.buttonOther)
                        else -> binding.toggleGroupGender.clearChecked() // No gender selected
                    }
                    val countryText = countries.find { it.code == selectedCountryCode }?.toString()
                    binding.autoCompleteCountry.setText(countryText, false)

                    val motherTongueText = languages.find { it.code == selectedMotherTongueCode }?.toString()
                    binding.autoCompleteMotherTongue.setText(motherTongueText, false)

                    val preferredLangText = languages.find { it.code == selectedPreferredLanguageCode }?.toString()
                    binding.autoCompletePreferredLanguage.setText(preferredLangText, false)

                    binding.autoCompleteLanguageLevel.setText(selectedLanguageLevel, false)

                    Glide.with(this@EditProfileActivity)
                        .load(user.profilePicUrl)
                        .placeholder(R.drawable.ic_avatar_placeholder)
                        .error(R.drawable.ic_avatar_placeholder) // Show placeholder on error too
                        .into(binding.imageViewProfilePic)

                } else {
                    Toast.makeText(this@EditProfileActivity, "Failed to load profile.", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("EditProfileActivity", "Error loading profile", e)
                Toast.makeText(this@EditProfileActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                showLoading(false)
            }
        }
    }

    private fun saveChanges() {
        val newUsername = binding.textInputEditTextUsername.text.toString().trim()
        val newBirthday = binding.textInputEditTextBirthday.text.toString().trim()
        val newGender = when (binding.toggleGroupGender.checkedButtonId) {
            R.id.buttonMale -> "male"
            R.id.buttonFemale -> "female"
            R.id.buttonOther -> "other"
            else -> null // Send null if nothing is selected
        }


        val usernameChanged = newUsername != (originalUsername ?: "") && newUsername.isNotEmpty()
        val pictureChanged = newProfilePicUri != null
        val optionalInfoChanged = newBirthday != (originalBirthday ?: "") ||
                newGender != originalGender ||
                selectedCountryCode != originalCountryCode ||
                selectedMotherTongueCode != originalMotherTongueCode ||
                selectedPreferredLanguageCode != originalPreferredLanguageCode ||
                selectedLanguageLevel != originalLanguageLevel


        if (!usernameChanged && !pictureChanged && !optionalInfoChanged) {
            Toast.makeText(this, "No changes to save", Toast.LENGTH_SHORT).show()
            return
        }

        showLoading(true)
        lifecycleScope.launch {
            var allUpdatesSuccessful = true
            var errorMessage = "An unknown error occurred."
            // --- Step 1: Update Username and/or Profile Picture (if changed) ---
            if (usernameChanged || pictureChanged) {
                try {
                    val usernamePart = if (usernameChanged) {
                        MultipartBody.Part.createFormData("username", newUsername)
                    } else null

                    val imagePart = if (pictureChanged) {
                        createPartFromUri(this@EditProfileActivity, newProfilePicUri!!, "profile_picture")
                    } else null

                    val response = ApiClient.instance.updateUserProfile(usernamePart, imagePart)
                    if (!response.isSuccessful) {
                        allUpdatesSuccessful = false
                        errorMessage = response.errorBody()?.string() ?: "Failed to update profile."
                    }
                } catch (e: Exception) {
                    allUpdatesSuccessful = false
                    errorMessage = e.message ?: "Exception during profile update."
                    Log.e("EditProfileActivity", "Exception while updating user/pic", e)
                }
            }

            // --- Step 2: Update Optional Info (if changed and Step 1 was successful) ---
            if (allUpdatesSuccessful && optionalInfoChanged) {
                try {
                    val updateRequest = UserOptionalInfoUpdateRequest(
                        country = selectedCountryCode,
                        motherTongue = selectedMotherTongueCode,
                        preferredLanguage = selectedPreferredLanguageCode,
                        birthday = newBirthday.ifEmpty { null },
                        gender = newGender,
                        languageLevel = selectedLanguageLevel
                    )
                    val response = ApiClient.instance.updateUserOptionalInfo(updateRequest)
                    if (!response.isSuccessful) {
                        allUpdatesSuccessful = false
                        errorMessage = response.errorBody()?.string() ?: "Failed to update optional info."
                    }
                } catch (e: Exception) {
                    allUpdatesSuccessful = false
                    errorMessage = e.message ?: "Exception during optional info update."
                    Log.e("EditProfileActivity", "Exception while updating optional info", e)
                }
            }

            // --- Final Step: Show result to user ---
            showLoading(false)
            if (allUpdatesSuccessful) {
                Toast.makeText(this@EditProfileActivity, "Profile updated successfully!", Toast.LENGTH_SHORT).show()
                setResult(Activity.RESULT_OK)
                finish()
            } else {
                Log.e("EditProfileActivity", "Failed to update: $errorMessage")
                Toast.makeText(this@EditProfileActivity, "Update failed: $errorMessage", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showLoading(isLoading: Boolean) {
        binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        binding.buttonSaveChanges.isEnabled = !isLoading
        binding.contentGroup.alpha = if (isLoading) 0.5f else 1.0f
    }

    // Helper function to convert a URI to a MultipartBody.Part
    private fun createPartFromUri(context: Context, uri: Uri, partName: String): MultipartBody.Part? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val fileType = context.contentResolver.getType(uri)

            // We need a file name, we can try to get it from the URI
            val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    cursor.getString(nameIndex)
                } else {
                    "profile_image.jpg" // Fallback name
                }
            } ?: "profile_image.jpg"

            val requestBody = inputStream.readBytes().toRequestBody(fileType?.toMediaTypeOrNull())
            inputStream.close()
            MultipartBody.Part.createFormData(partName, fileName, requestBody)
        } catch (e: Exception) {
            Log.e("EditProfileActivity", "Failed to create multipart from URI", e)
            null
        }
    }

    private fun getCountries(): List<CountryItem> {
        return Locale.getISOCountries().map { code ->
            val locale = Locale("", code)
            CountryItem(code, locale.displayCountry)
        }.sortedBy { it.name }
    }

    private fun getLanguages(): List<LanguageItem> {
        val languageSet = mutableSetOf<LanguageItem>()
        Locale.getAvailableLocales().forEach { locale ->
            // Use 2-letter language code and ensure it and the name are not empty
            if (locale.language.length == 2 && locale.displayLanguage.isNotEmpty()) {
                languageSet.add(LanguageItem(locale.language, locale.displayLanguage.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }))
            }
        }
        return languageSet.sortedBy { it.name }
    }
}