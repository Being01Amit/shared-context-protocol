package com.scp.markdown

import com.scp.model.port.MarkdownStore
import com.scp.model.port.SessionMarkdown

/**
 * The mirror used when at-rest encryption is enabled: writing a plaintext `.md` mirror would
 * leak on disk exactly what the encrypted database protects, so no file is written. Returns an
 * empty path to signal "no mirror produced". The encrypted database is the single source of
 * truth in this mode.
 */
public class NoOpMarkdownStore : MarkdownStore {
    override fun write(sessionMarkdown: SessionMarkdown): String = ""
}
