package com.example.camera.network

/** [CloudRepository.uploadLookupTableBlocking] 与 [com.example.camera.upload.UploadLookupTableWorker] 共用。 */
sealed class LookupTableUploadWorkResult {
    object Success : LookupTableUploadWorkResult()
    object SkippedNoSecret : LookupTableUploadWorkResult()
    object SkippedInvalidTable : LookupTableUploadWorkResult()
    data class Forbidden(val message: String) : LookupTableUploadWorkResult()
    data class BusinessError(val message: String) : LookupTableUploadWorkResult()
    data class UnparsedResponse(val httpCode: Int, val snippet: String) : LookupTableUploadWorkResult()
    data class NetworkError(val message: String) : LookupTableUploadWorkResult()
}
