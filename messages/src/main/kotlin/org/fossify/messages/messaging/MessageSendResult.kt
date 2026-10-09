package org.fossify.messages.messaging

/** ACCEPTED means persisted and handed to SMS transport; MMS completion arrives asynchronously. */
enum class MessageSendResult { ACCEPTED, PENDING, FAILED }
