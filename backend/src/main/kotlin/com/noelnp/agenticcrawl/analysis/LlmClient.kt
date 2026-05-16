package com.noelnp.agenticcrawl.analysis

import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.core.io.ByteArrayResource
import org.springframework.stereotype.Component
import org.springframework.util.MimeTypeUtils

@Component
class LlmClient(builder: ChatClient.Builder) {

    private val client = builder.build()
    private val deterministic: OpenAiChatOptions =
        OpenAiChatOptions.builder().temperature(0.0).build()

    fun <T : Any> json(prompt: String, type: Class<T>): T? =
        client.prompt()
            .options(deterministic)
            .user(prompt)
            .call()
            .entity(type)

    fun <T : Any> jsonWithImage(prompt: String, image: ByteArray, type: Class<T>): T? =
        client.prompt()
            .options(deterministic)
            .user { spec ->
                spec.text(prompt)
                    .media(MimeTypeUtils.IMAGE_PNG, ByteArrayResource(image))
            }
            .call()
            .entity(type)
}
