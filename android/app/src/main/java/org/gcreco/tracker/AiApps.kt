package org.gcreco.tracker

/**
 * Supported AI apps, matched by Android package name.
 * Verify/extend these against the Play Store listings before release.
 */
object AiApps {
    val supported: Map<String, String> = mapOf(
        "com.openai.chatgpt" to "ChatGPT",
        "com.google.android.apps.bard" to "Google Gemini",
        "com.anthropic.claude" to "Claude",
        "com.microsoft.copilot" to "Microsoft Copilot",
        "ai.perplexity.app.android" to "Perplexity",
        "com.deepseek.chat" to "DeepSeek",
        "ai.x.grok" to "Grok",
        "ai.character.app" to "Character.AI"
    )

    fun isSupported(pkg: String) = supported.containsKey(pkg)
    fun nameOf(pkg: String) = supported[pkg] ?: pkg
}
