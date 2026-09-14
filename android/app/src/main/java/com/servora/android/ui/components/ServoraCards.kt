package com.servora.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.servora.android.R

/*
 * The card and section primitives the Servora screens are built from.
 *
 * They live here rather than in one feature package because more than one feature renders them: the
 * customer screens and the manager home both present a titled section over bordered cards, and a
 * second copy of the same metrics would let them drift apart (`dev.md` §1).
 */

/**
 * A section's own label, with the number of rows it holds when it has one.
 *
 * The label is not all-caps in the resource: the casing follows the language, and a translated
 * string must never be cased by the code that draws it (`BR-028`).
 */
@Composable
internal fun SectionLabel(label: String, count: Int?) {
    Text(
        text = if (count == null) label else stringResource(R.string.section_count_format, label, count),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
}

/** The bordered surface a section's rows sit on. */
@Composable
internal fun InfoCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}
