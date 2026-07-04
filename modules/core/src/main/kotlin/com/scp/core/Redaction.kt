package com.scp.core

/**
 * Secret scrubbing (docs/03 §5): a pure function run over every context entry title/content,
 * session summary, decision/todo text, and file summary BEFORE anything is persisted.
 * The database and markdown mirrors never see the raw secret.
 */
public data class RedactionPattern(
    val name: String,
    val regex: Regex,
    /** When true, group 1 (e.g. the env var name) is kept and only the value is redacted. */
    val keepFirstGroup: Boolean = false,
)

public object Redaction {
    /** Built-ins, always active. Config `secretRedactionPatterns` extends, never replaces. */
    public val defaultPatterns: List<RedactionPattern> =
        listOf(
            RedactionPattern("aws-access-key", Regex("""\bAKIA[0-9A-Z]{16}\b""")),
            RedactionPattern("github-token", Regex("""\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{36,}\b""")),
            RedactionPattern("github-pat", Regex("""\bgithub_pat_[A-Za-z0-9_]{22,}\b""")),
            RedactionPattern("anthropic-key", Regex("""\bsk-ant-[A-Za-z0-9_-]{20,}\b""")),
            RedactionPattern("generic-sk-key", Regex("""\bsk-[A-Za-z0-9]{20,}\b""")),
            RedactionPattern("jwt", Regex("""\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{5,}\b""")),
            RedactionPattern("bearer-token", Regex("""(?i)\bbearer\s+[A-Za-z0-9\-._~+/]{20,}=*""")),
            RedactionPattern(
                "private-key-block",
                Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----"""),
            ),
            RedactionPattern(
                "env-secret",
                Regex("""(?im)^([ \t]*[A-Z0-9_]*(?:SECRET|TOKEN|PASSWORD|PASSWD|API_KEY|APIKEY|PRIVATE_KEY)[A-Z0-9_]*)[ \t]*=[ \t]*\S+"""),
                keepFirstGroup = true,
            ),
        )

    public fun compile(extraPatterns: List<String>): List<RedactionPattern> =
        defaultPatterns +
            extraPatterns.mapIndexed { index, source ->
                RedactionPattern("custom-$index", Regex(source))
            }

    public fun redact(text: String, patterns: List<RedactionPattern> = defaultPatterns): String =
        patterns.fold(text) { acc, pattern ->
            pattern.regex.replace(acc) { match ->
                if (pattern.keepFirstGroup && match.groupValues.size > 1) {
                    "${match.groupValues[1]}=[REDACTED:${pattern.name}]"
                } else {
                    "[REDACTED:${pattern.name}]"
                }
            }
        }
}
