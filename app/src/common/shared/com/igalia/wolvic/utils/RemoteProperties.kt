package com.igalia.wolvic.utils

data class Environment(
        val value: String,
        val title: String,
        val thumbnail: String,
        val payload: String,
        val source: String? = null
) {
    // Commercial-use license filter: the shared wolvic/props.json feed mixes CC0,
    // CC-BY, CC BY-NC-SA, and CC BY-NC-ND entries. NC (NonCommercial) and ND
    // (NoDerivatives) terms forbid use in a paid app, so they're filtered out at
    // the consumption boundary. Unknown/unlabeled entries are dropped as well
    // (opaque license = unsafe).
    val isPermittedForCommercialUse: Boolean
        get() {
            val normalized = title.uppercase()
            if (normalized.contains("-NC") || normalized.contains(" NC-")) return false
            if (normalized.contains("-ND") || normalized.contains(" ND-")) return false
            if (normalized.contains("(CC0)")) return true
            if (normalized.contains("(CC-BY)") || normalized.contains("(CC BY)")) return true
            return false
        }

    // Parsed creator name, e.g. "Alexander Scholten" from
    // "Rural Evening Road by Alexander Scholten (CC0)".
    val author: String?
        get() {
            val byIdx = title.indexOf(" by ")
            if (byIdx < 0) return null
            val afterBy = title.substring(byIdx + 4)
            val parenIdx = afterBy.indexOf(" (")
            return if (parenIdx > 0) afterBy.substring(0, parenIdx).trim() else afterBy.trim()
        }

    // Parsed license tag, e.g. "CC0" or "CC-BY".
    val license: String?
        get() {
            val open = title.lastIndexOf('(')
            val close = title.lastIndexOf(')')
            return if (open in 0 until close) title.substring(open + 1, close).trim() else null
        }

    // Parsed work title, e.g. "Rural Evening Road" from
    // "Rural Evening Road by Alexander Scholten (CC0)".
    val workTitle: String
        get() {
            val byIdx = title.indexOf(" by ")
            return if (byIdx > 0) title.substring(0, byIdx).trim() else {
                val parenIdx = title.indexOf(" (")
                if (parenIdx > 0) title.substring(0, parenIdx).trim() else title
            }
        }
}

data class Dictionary(
        val lang: String,
        val payload: String
)

data class RemoteProperties(
        val whatsNewUrl: String,
        val environments: Array<Environment>?,
        val dictionaries: Array<Dictionary>?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RemoteProperties

        if (whatsNewUrl != other.whatsNewUrl) return false
        if (environments != null) {
            if (other.environments == null) return false
            if (!environments.contentEquals(other.environments)) return false
        } else if (other.environments != null) return false

        if (dictionaries != null) {
            if (other.dictionaries == null) return false
            if (!dictionaries.contentEquals(other.dictionaries)) return false
        } else if (other.dictionaries != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = whatsNewUrl.hashCode()
        result = 31 * result + (environments?.contentHashCode() ?: 0)
        result = 8 * result + (dictionaries?.contentHashCode() ?: 0)
        return result
    }

}
