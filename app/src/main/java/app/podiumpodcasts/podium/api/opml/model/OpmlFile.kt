package app.podiumpodcasts.podium.api.opml.model

import app.podiumpodcasts.podium.utils.xml
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("opml")
data class OpmlFile(
    val version: String = "2.0",
    @XmlElement(true) val head: OpmlHead? = null,
    @XmlElement(true) val body: OpmlBody
) {
    companion object {
        fun parse(xml: String): OpmlFile {
            return app.podiumpodcasts.podium.utils.xml.decodeFromString<OpmlFile>(xml)
        }
    }

    override fun toString(): String {
        return xml.encodeToString(this)
    }
}

@Serializable
@XmlSerialName("head")
data class OpmlHead(
    @XmlElement(true) val title: String? = null,
    @XmlElement(true) val dateCreated: String? = null
)

@Serializable
@XmlSerialName("body")
data class OpmlBody(
    @XmlSerialName("outline")
    var outlines: List<OpmlOutline> = emptyList()
) {
    fun flattenedOutlines(): List<OpmlOutline> {
        val seenUrls = HashSet<String>()
        val result = ArrayList<OpmlOutline>()

        fun visit(list: List<OpmlOutline>) {
            for(outline in list) {
                val url = outline.xmlUrl?.trim()?.takeIf { it.isNotBlank() }
                if(url != null && seenUrls.add(url)) {
                    result.add(outline)
                }
                if(outline.outlines.isNotEmpty()) {
                    visit(outline.outlines)
                }
            }
        }

        visit(outlines)
        return result
    }
}

@Serializable
@XmlSerialName("outline")
data class OpmlOutline(
    val text: String? = null,
    val title: String? = null,
    val type: String? = null,
    val xmlUrl: String? = null,
    val htmlUrl: String? = null,
    @XmlSerialName("outline")
    val outlines: List<OpmlOutline> = emptyList()
) {
    val displayTitle: String
        get() = text?.takeIf { it.isNotBlank() }
            ?: title?.takeIf { it.isNotBlank() }
            ?: xmlUrl.orEmpty()
}