package com.arbelonson.ozen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arbelonson.ozen.core.WordFix
import com.arbelonson.ozen.core.WordFixes
import com.arbelonson.ozen.core.tr
import com.arbelonson.ozen.ui.GroupFootnote
import com.arbelonson.ozen.ui.OzenSpacing
import com.arbelonson.ozen.ui.TonalButton
import com.arbelonson.ozen.ui.SectionHeader as GroupHeader

@Composable
internal fun WordFixesSection(fixes: List<WordFix>, onChange: (List<WordFix>) -> Unit) {
    var heard by rememberSaveable { mutableStateOf("") }
    var written by rememberSaveable { mutableStateOf("") }
    val added = WordFixes.normalized(listOf(WordFix(heard, written)) + fixes)
    val canAdd = added.size > fixes.size && fixes.size < WordFixes.MAXIMUM_FIXES
    Row(verticalAlignment = Alignment.Bottom) {
        GroupHeader(tr("תיקוני מילים", "Word fixes"), modifier = Modifier.weight(1f))
        Text("${fixes.size} / ${WordFixes.MAXIMUM_FIXES}", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 16.dp))
    }
    GroupFootnote(tr("מילה ששמעו לא נכון תיכתב תמיד כמו שתכתבו כאן.", "A word heard wrongly is always written the way you set here."))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OzenSpacing.Unit)) {
        OutlinedTextField(heard, { heard = it.take(WordFixes.MAXIMUM_LENGTH) }, placeholder = { Text(tr("מה נשמע", "Heard")) }, singleLine = true, modifier = Modifier.weight(1f))
        OutlinedTextField(written, { written = it.take(WordFixes.MAXIMUM_LENGTH) }, placeholder = { Text(tr("מה לכתוב", "Written")) }, singleLine = true, modifier = Modifier.weight(1f))
    }
    TonalButton(tr("הוספת תיקון", "Add fix"), onClick = { onChange(added); heard = ""; written = "" }, enabled = canAdd)
    if (fixes.isNotEmpty()) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
            LazyColumn(Modifier.heightIn(max = 240.dp)) {
                itemsIndexed(fixes, key = { _, fix -> fix.heard }) { at, fix ->
                    if (at > 0) HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${fix.heard} → ${fix.written}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onChange(fixes - fix) }, modifier = Modifier.padding(0.dp)) {
                            Icon(Icons.Rounded.Close, contentDescription = tr("מחיקת התיקון %1", "Delete the fix %1", listOf("${fix.heard} → ${fix.written}")))
                        }
                    }
                }
            }
        }
    }
}
