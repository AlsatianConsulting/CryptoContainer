package dev.alsatianconsulting.cryptocontainer.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.MutableLiveData

enum class ShareAction {
    AES_ENCRYPT,
    AES_DECRYPT,
    VERACRYPT_CONTAINER_FILE,
    VERACRYPT_IMPORT
}

class ShareViewModel : ViewModel() {
    val sharedUri: MutableLiveData<String?> = MutableLiveData(null)
    val sharedUris: MutableLiveData<List<String>> = MutableLiveData(emptyList())
    val shareAction: MutableLiveData<ShareAction?> = MutableLiveData(null)

    fun setSharedUris(uris: List<Uri>) {
        val normalized = uris.map(Uri::toString).distinct()
        sharedUris.value = normalized
        sharedUri.value = normalized.firstOrNull()
        shareAction.value = null
    }

    fun selectShareAction(action: ShareAction) {
        shareAction.value = action
    }

    fun clearShared() {
        sharedUris.value = emptyList()
        sharedUri.value = null
        shareAction.value = null
    }
}
