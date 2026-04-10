package dev.unityinflow.kore.core

/** A single message in the agent's conversation history. */
data class ConversationMessage(
    val role: Role,
    val content: String,
    val toolCallId: String? = null,
) {
    /** The role of the message sender in the conversation. */
    sealed class Role {
        data object User : Role()

        data object Assistant : Role()

        data object System : Role()

        data object Tool : Role()
    }
}
