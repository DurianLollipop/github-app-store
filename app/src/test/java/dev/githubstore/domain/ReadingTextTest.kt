package dev.githubstore.domain

import dev.githubstore.ui.readingParagraphs
import org.junit.Assert.*
import org.junit.Test

class ReadingTextTest {
    private fun compact(text: String) = text.filterNot(Char::isWhitespace)

    @Test fun `long Chinese paragraphs retain all content in bounded focus targets`() {
        val input = "一段很长的应用介绍，包含功能说明。".repeat(80)
        val parts = readingParagraphs(input)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.length in 1..180 })
        assertEquals(compact(input), compact(parts.joinToString("")))
    }

    @Test fun `English words and existing paragraph boundaries are preserved`() {
        val first = "A useful open source application with settings and file sharing. ".repeat(12)
        val parts = readingParagraphs(first + "\n\nAnother paragraph about installation.")
        assertEquals("Another paragraph about installation.", parts.last())
        assertEquals(compact(first + "Another paragraph about installation."), compact(parts.joinToString("")))
        val word = Regex("[A-Za-z]+")
        assertEquals(word.findAll(first).map { it.value }.toList(),
            parts.dropLast(1).flatMap { part -> word.findAll(part).map { it.value }.toList() })
    }

    @Test fun `emoji surrogate pairs are never split between reading targets`() {
        val input = "电视📺应用🚀".repeat(100)
        val parts = readingParagraphs(input)
        assertEquals(input, parts.joinToString(""))
        assertTrue(parts.none { Character.isHighSurrogate(it.last()) || Character.isLowSurrogate(it.first()) })
    }

    @Test fun `empty paragraphs do not create invisible remote focus stops`() {
        assertEquals(emptyList<String>(), readingParagraphs(" \n\n\t\n\n "))
        assertEquals(listOf("第一段", "第二段"), readingParagraphs("\n\n第一段\n\n\n第二段\n\n"))
    }
}
