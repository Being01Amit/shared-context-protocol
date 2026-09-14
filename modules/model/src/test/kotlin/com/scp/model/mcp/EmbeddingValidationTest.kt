package com.scp.model.mcp

import com.scp.model.ContextType
import com.scp.model.InvalidInputException
import kotlin.test.Test
import kotlin.test.assertFailsWith

class EmbeddingValidationTest {
    private fun entry(embedding: List<Float>?) = NewEntry("t", "c", ContextType.FEATURE, embedding = embedding)

    private fun update(vararg entries: NewEntry) =
        UpdateContextInput(projectName = "demo", toolName = "cli", entries = entries.toList())

    @Test
    fun `entries without embeddings, and consistent embeddings, are valid`() {
        McpValidations.updateContext.checkValid(update(entry(null), entry(listOf(0.1f, 0.2f)), entry(listOf(0.3f, 0.4f))))
    }

    @Test
    fun `an empty embedding is rejected`() {
        assertFailsWith<InvalidInputException> { McpValidations.updateContext.checkValid(update(entry(emptyList()))) }
    }

    @Test
    fun `an oversized embedding is rejected`() {
        assertFailsWith<InvalidInputException> {
            McpValidations.updateContext.checkValid(update(entry(List(8193) { 0.1f })))
        }
    }

    @Test
    fun `embeddings of different dimensions in one call are rejected`() {
        assertFailsWith<InvalidInputException> {
            McpValidations.updateContext.checkValid(update(entry(listOf(0.1f, 0.2f)), entry(listOf(0.1f, 0.2f, 0.3f))))
        }
    }

    @Test
    fun `hydrate query embedding is bounded too`() {
        McpValidations.hydrateContext.checkValid(HydrateContextInput("demo", embedding = listOf(0.5f)))
        assertFailsWith<InvalidInputException> {
            McpValidations.hydrateContext.checkValid(HydrateContextInput("demo", embedding = emptyList()))
        }
        assertFailsWith<InvalidInputException> {
            McpValidations.hydrateContext.checkValid(HydrateContextInput("demo", embedding = List(8193) { 0.1f }))
        }
    }
}
