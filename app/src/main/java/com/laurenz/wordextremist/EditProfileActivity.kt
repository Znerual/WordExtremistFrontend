package com.laurenz.wordextremist

import android.app.Activity
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
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.FileOutputStream

class EditProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditProfileBinding
    private lateinit var imagePickerLauncher: ActivityResultLauncher<String>

    private var originalUsername: String? = null
    private var newProfilePicUri: Uri? = null

    companion object {
        const val PROFILE_UPDATED = "profile_updated"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
        initImagePicker()
        loadUserProfile()
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
                    binding.textInputEditTextUsername.setText(user.username)

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
        val usernameChanged = newUsername != originalUsername && newUsername.isNotEmpty()
        val pictureChanged = newProfilePicUri != null

        if (!usernameChanged && !pictureChanged) {
            Toast.makeText(this, "No changes to save", Toast.LENGTH_SHORT).show()
            return
        }

        showLoading(true)

        lifecycleScope.launch {
            try {
                val usernamePart = if (usernameChanged) {
                    MultipartBody.Part.createFormData("username", newUsername)
                } else {
                    null
                }

                val imagePart = if (pictureChanged) {
                    createPartFromUri(this@EditProfileActivity, newProfilePicUri!!, "profile_picture")
                } else {
                    null
                }

                if (imagePart == null && pictureChanged) {
                    Toast.makeText(this@EditProfileActivity, "Failed to process image file.", Toast.LENGTH_LONG).show()
                    showLoading(false)
                    return@launch
                }

                val response = ApiClient.instance.updateUserProfile(usernamePart, imagePart)

                if (response.isSuccessful) {
                    Toast.makeText(this@EditProfileActivity, "Profile updated successfully!", Toast.LENGTH_SHORT).show()
                    // Set result so LauncherActivity knows to refresh
                    setResult(Activity.RESULT_OK)
                    finish()
                } else {
                    val error = response.errorBody()?.string() ?: "Unknown error"
                    Log.e("EditProfileActivity", "Failed to update profile: ${response.code()} - $error")
                    Toast.makeText(this@EditProfileActivity, "Update failed: $error", Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                Log.e("EditProfileActivity", "Exception while updating profile", e)
                Toast.makeText(this@EditProfileActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                showLoading(false)
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
}