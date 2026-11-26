package com.notifyring.app.data

data class AlertCallLogEntry(
    val id: String,
    val timestampMillis: Long,
    val packageName: String,
    val appLabel: String,
    val sender: String,
    val messagePreview: String,
    val answered: Boolean,
    val disconnectReason: String
)
