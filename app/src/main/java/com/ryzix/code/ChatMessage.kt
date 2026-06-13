package com.ryzix.code

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val diffChange: AIClient.PendingChange? = null,
    var diffState: DiffState = DiffState.NONE,
    var diffCallback: ((Boolean) -> Unit)? = null
) {
    enum class DiffState { NONE, PENDING, ACCEPTED, REJECTED }
}
