package com.constructioncost.nativeapp.platform

import com.constructioncost.nativeapp.domain.AttachmentInfo
import com.constructioncost.nativeapp.domain.DocumentImportDetails

data class UiActions(
    val importDocument: (String) -> Unit,
    val viewDocument: (AttachmentInfo) -> Unit,
    val shareDocument: (String) -> Unit,
    val exportDocument: (AttachmentInfo) -> Unit,
    val renameDocument: (AttachmentInfo, String) -> Unit,
    val newRevision: (AttachmentInfo) -> Unit,
    val deleteDocument: (AttachmentInfo) -> Unit,
    val createBackup: (String?) -> Unit,
    val restoreBackup: (String?) -> Unit,
    val importLegacy: (String) -> Unit,
    val exportCsv: (String) -> Unit,
    val exportPdf: (String) -> Unit,
    val notifyMessage: (String) -> Unit,
    val setReminders: (Boolean) -> Unit,
    val capturePhoto: ((String) -> Unit)? = null,
    val importDetailedDocuments: ((String, DocumentImportDetails) -> Unit)? = null,
    val manageRecoveryCopies: (() -> Unit)? = null,
    val manageCapturedPhotos: ((String) -> Unit)? = null,
    val exportProject: (String) -> Unit = {},
    val deleteProject: (String) -> Unit = {}
)
