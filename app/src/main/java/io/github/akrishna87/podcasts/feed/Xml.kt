package io.github.akrishna87.podcasts.feed

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/** Common named HTML entities. Anything else unknown becomes a space so XML parsing never fails. */
internal val ENTITIES = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
    "nbsp" to " ", "mdash" to "—", "ndash" to "–", "hellip" to "…", "bull" to "•",
    "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "laquo" to "«", "raquo" to "»",
    "copy" to "©", "reg" to "®", "trade" to "™", "eacute" to "é", "egrave" to "è", "agrave" to "à", "aacute" to "á",
    "iacute" to "í", "oacute" to "ó", "uacute" to "ú", "ntilde" to "ñ", "ouml" to "ö", "uuml" to "ü", "auml" to "ä",
    "ccedil" to "ç", "szlig" to "ß", "middot" to "·", "times" to "×", "euro" to "€", "pound" to "£",
)

internal val ENTITY = Regex("&(#[xX][0-9a-fA-F]+|#[0-9]+|[a-zA-Z][a-zA-Z0-9]*);")

/** Decodes HTML entities in plain text. */
fun decodeEntities(s: String): String = ENTITY.replace(s) { m ->
    val e = m.groupValues[1]
    when {
        e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let { codePoint(it) } ?: m.value
        e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { codePoint(it) } ?: m.value
        else -> ENTITIES[e] ?: m.value
    }
}

private fun codePoint(i: Int): String = if (Character.isValidCodePoint(i)) String(Character.toChars(i)) else " "

/**
 * Parses XML (RSS, Atom, OPML) leniently: drops the DOCTYPE (so nothing is fetched), replaces
 * HTML-only entities and stray ampersands, which are the usual reasons strict parsers give up.
 */
fun parseXml(text: String): Document {
    val cleaned = text
        .replace(Regex("^\\s*﻿?\\s*"), "")
        .replace(Regex("<!DOCTYPE[^>\\[]*(\\[[^\\]]*\\])?\\s*>", RegexOption.IGNORE_CASE), "")
        .replace(ENTITY) { m ->
            val e = m.groupValues[1]
            when {
                e.startsWith("#") || e in setOf("amp", "lt", "gt", "quot", "apos") -> m.value
                else -> ENTITIES[e]?.let { v -> if (v == "&") "&amp;" else v } ?: " "
            }
        }
        // A bare "&" that starts no entity ("Q&A") is a common feed mistake.
        .replace(Regex("&(?!(#[xX][0-9a-fA-F]+|#[0-9]+|[a-zA-Z][a-zA-Z0-9]*);)"), "&amp;")
        // Control characters other than tab and newlines are not allowed in XML 1.0.
        .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]"), "")
    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = true
    factory.isValidating = false
    try {
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    } catch (e: Exception) {
        // Not every parser knows this feature; with the DOCTYPE gone it doesn't matter.
    }
    return factory.newDocumentBuilder().parse(InputSource(StringReader(cleaned)))
}

private fun Node.isNamed(localName: String): Boolean =
    this.localName == localName || nodeName == localName || nodeName.endsWith(":$localName")

/** Elements with the given local name, at any depth. */
fun Node.descendants(localName: String): List<Element> {
    val out = ArrayList<Element>()
    fun walk(n: Node) {
        val kids = n.childNodes
        for (i in 0 until kids.length) {
            val k = kids.item(i)
            if (k is Element) {
                if (k.isNamed(localName)) out += k
                walk(k)
            }
        }
    }
    walk(this)
    return out
}

/** Direct child elements with the given local name. */
fun Node.children(localName: String): List<Element> {
    val out = ArrayList<Element>()
    val kids = childNodes
    for (i in 0 until kids.length) {
        val k = kids.item(i)
        if (k is Element && k.isNamed(localName)) out += k
    }
    return out
}

/** Direct child elements with the given local name in a namespace (by its URI, or by prefix if undeclared). */
fun Node.childrenNs(namespaces: Set<String>, prefix: String, localName: String): List<Element> {
    val out = ArrayList<Element>()
    val kids = childNodes
    for (i in 0 until kids.length) {
        val k = kids.item(i)
        if (k !is Element) continue
        val ns = k.namespaceURI
        val matches = if (ns != null) ns in namespaces && k.localName == localName else k.nodeName == "$prefix:$localName"
        if (matches) out += k
    }
    return out
}

/** Direct child elements in no namespace (plain RSS) with the given name. */
fun Node.plainChildren(localName: String): List<Element> {
    val out = ArrayList<Element>()
    val kids = childNodes
    for (i in 0 until kids.length) {
        val k = kids.item(i)
        if (k is Element && k.namespaceURI.isNullOrEmpty() && (k.localName ?: k.nodeName) == localName) out += k
    }
    return out
}

fun Element.child(localName: String): Element? = children(localName).firstOrNull()

/** The element's text with whitespace tidied. */
val Node.text: String get() = (textContent ?: "").replace(Regex("\\s+"), " ").trim()

/** The element's text as written (for HTML show notes). */
val Node.rawText: String get() = (textContent ?: "").trim()

/** An attribute by local name, whatever its namespace prefix. */
fun Element.attr(localName: String): String {
    if (hasAttribute(localName)) return getAttribute(localName)
    val attrs = attributes
    for (i in 0 until attrs.length) {
        val a = attrs.item(i)
        if (a.localName == localName || a.nodeName.endsWith(":$localName")) return a.nodeValue ?: ""
    }
    return ""
}
