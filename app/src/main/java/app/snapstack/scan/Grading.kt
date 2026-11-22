package app.snapstack.scan

import java.util.Locale

/** A graded copy: who graded it, the grade, the certificate number and any subgrades. */
data class Grading(
    val grader: String,
    val grade: String?,
    val cert: String? = null,
    val gradeLabel: String? = null,
    val subgrades: Map<String, String> = emptyMap(),
) {
    val display: String get() = listOfNotNull(grader, grade).joinToString(" ")

    /** Notes saved on the collection copy: "Cert #105442370 · Centering 9.5 · Corners 9.5 …". */
    val notes: String?
        get() = listOfNotNull(
            cert?.let { "Cert #$it" },
            gradeLabel?.takeIf { it.isNotBlank() },
            subgrades.takeIf { it.isNotEmpty() }?.entries?.joinToString(" · ") { (k, v) ->
                k.replaceFirstChar { c -> c.titlecase(Locale.US) } + " " + v
            },
        ).joinToString(" · ").ifEmpty { null }

    /** The grading company's own certificate check for this slab. */
    val verifyUrl: String?
        get() = when (grader.uppercase(Locale.US)) {
            "PSA" -> cert?.let { "https://www.psacard.com/cert/$it" } ?: "https://www.psacard.com/cert"
            "CGC" -> cert?.let { "https://www.cgccards.com/certlookup/$it/" } ?: "https://www.cgccards.com/certlookup/"
            "BGS" -> "https://www.beckett.com/grading/card-lookup"
            "TAG" -> "https://taggrading.com/pages/tagd"
            "SGC" -> "https://gosgc.com/cert-code-lookup"
            else -> null
        }

    companion object {
        fun from(slab: SlabInfo) = Grading(slab.grader, slab.grade, slab.cert, slab.gradeLabel, slab.subgrades)
    }
}
