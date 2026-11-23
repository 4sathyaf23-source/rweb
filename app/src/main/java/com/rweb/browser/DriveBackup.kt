package com.rweb.browser

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.IntentSenderRequest
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Backs diddu's settings, bookmarks and reading list up to the person's own Google Drive, in a
 * hidden folder only this app can see (the "drive.appdata" scope). There is no server of ours
 * involved anywhere in this flow: Drive is the storage, and Google's own consent screen is what
 * grants access, not anything this class decides on its own.
 *
 * Two separate steps, on Google's own recommendation (see
 * https://developer.android.com/identity/authorization): sign-in (who is this) uses Credential
 * Manager, and authorization (can this app touch their Drive) uses AuthorizationClient, asked for
 * only right before a backup or restore rather than at sign-in time.
 *
 * SETUP NEEDED BEFORE THIS WORKS: paste your own OAuth "Web application" client ID from Google
 * Cloud Console into WEB_CLIENT_ID below. Until then, sign-in will fail with a clear message.
 */
object DriveBackup {

    // TODO: replace with the OAuth client ID from Google Cloud Console, e.g. "123-abc.apps.googleusercontent.com"
    const val WEB_CLIENT_ID = "YOUR_OAUTH_CLIENT_ID.apps.googleusercontent.com"

    private const val SCOPE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
    private const val BACKUP_FILE_NAME = "diddu_backup.json"
    private const val FILES_URL = "https://www.googleapis.com/drive/v3/files"
    private const val UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"

    data class Account(val name: String, val email: String, val photoUrl: String?)

    sealed class AuthOutcome {
        data class Token(val accessToken: String) : AuthOutcome()
        data class NeedsConsent(val request: IntentSenderRequest) : AuthOutcome()
        object Failed : AuthOutcome()
    }

    private val http = OkHttpClient()

    fun isConfigured(): Boolean = !WEB_CLIENT_ID.startsWith("YOUR_")

    /** Step 1: shows the system account picker. Returns null if cancelled or not yet configured. */
    suspend fun signIn(context: Context): Account? {
        if (!isConfigured()) return null
        val option = GetSignInWithGoogleOption.Builder(WEB_CLIENT_ID).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        return try {
            val result = CredentialManager.create(context).getCredential(context, request)
            val cred = GoogleIdTokenCredential.createFrom(result.credential.data)
            Account(cred.displayName ?: cred.id, cred.id, cred.profilePictureUri?.toString())
        } catch (_: GetCredentialException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun signOut(context: Context) {
        try {
            CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
        } catch (_: Exception) {
        }
    }

    /**
     * Step 2: ask for access to the app's own Drive folder. Call this right before a backup or
     * restore action, not at sign-in time (Google's own guidance on separating the two). If
     * consent is needed, launch [AuthOutcome.NeedsConsent.request] and feed the returned intent
     * to [accessTokenFromIntent]; otherwise the token is already in [AuthOutcome.Token].
     */
    suspend fun requestAccessToken(activity: Activity): AuthOutcome = suspendCancellableCoroutine { cont ->
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(SCOPE_APPDATA)))
            .build()
        Identity.getAuthorizationClient(activity)
            .authorize(request)
            .addOnSuccessListener { result ->
                val pi = result.pendingIntent
                if (result.hasResolution() && pi != null) {
                    cont.resume(AuthOutcome.NeedsConsent(IntentSenderRequest.Builder(pi.intentSender).build()))
                } else {
                    val token = result.accessToken
                    cont.resume(if (token != null) AuthOutcome.Token(token) else AuthOutcome.Failed)
                }
            }
            .addOnFailureListener { cont.resume(AuthOutcome.Failed) }
    }

    /** Call with the data Intent your launcher receives after the consent screen returns. */
    fun accessTokenFromIntent(context: Context, data: Intent?): String? {
        if (data == null) return null
        return try {
            Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data).accessToken
        } catch (_: ApiException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun authHeader(token: String) = "Bearer $token"

    /** Finds the backup file's Drive-internal id, if one already exists in the app folder. */
    private fun findBackupFileId(token: String): String? {
        val url = FILES_URL.toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("spaces", "appDataFolder")
            ?.addQueryParameter("q", "name = '$BACKUP_FILE_NAME' and trashed = false")
            ?.addQueryParameter("fields", "files(id)")
            ?.build() ?: return null
        val req = Request.Builder().url(url).header("Authorization", authHeader(token)).get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val files = JSONObject(body).optJSONArray("files") ?: return null
            if (files.length() == 0) return null
            return files.optJSONObject(0)?.optString("id")
        }
    }

    /** Uploads (or updates) the backup JSON. Returns true on success. */
    suspend fun uploadBackup(token: String, json: String): Boolean = try {
        val existingId = findBackupFileId(token)
        val mediaType = "application/json; charset=utf-8".toMediaType()
        if (existingId != null) {
            val url = "$UPLOAD_URL/$existingId?uploadType=media"
            val req = Request.Builder()
                .url(url)
                .header("Authorization", authHeader(token))
                .patch(json.toRequestBody(mediaType))
                .build()
            http.newCall(req).execute().use { it.isSuccessful }
        } else {
            val metadata = JSONObject().put("name", BACKUP_FILE_NAME).put("parents", listOf("appDataFolder")).toString()
            val metadataBody = metadata.toRequestBody("application/json; charset=utf-8".toMediaType())
            val contentBody = json.toRequestBody(mediaType)
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addPart(MultipartBody.Part.create(metadataBody))
                .addPart(MultipartBody.Part.create(contentBody))
                .build()
            val req = Request.Builder()
                .url("$UPLOAD_URL?uploadType=multipart")
                .header("Authorization", authHeader(token))
                .post(multipart)
                .build()
            http.newCall(req).execute().use { it.isSuccessful }
        }
    } catch (_: Exception) {
        false
    }

    /** Downloads the backup JSON, or null if there isn't one yet or the request failed. */
    suspend fun downloadBackup(token: String): String? {
        return try {
            val id = findBackupFileId(token) ?: return null
            val req = Request.Builder()
                .url("$FILES_URL/$id?alt=media")
                .header("Authorization", authHeader(token))
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        } catch (_: Exception) {
            null
        }
    }
}
