package com.ameer.appradiore.feature.livelog.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ameer.appradiore.core.logging.ProtocolType
import com.ameer.appradiore.ui.theme.AppRadioRETheme

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LogControlBar(
    selectedFilter: ProtocolType?,
    searchQuery: String,
    autoScroll: Boolean,
    totalCount: Int,
    onFilterSelected: (ProtocolType?) -> Unit,
    onSearchChanged: (String) -> Unit,
    onToggleAutoScroll: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.Center
        ) {
            FilterChip(
                selected = selectedFilter == null,
                onClick = { onFilterSelected(null) },
                label = { Text("ALL ($totalCount)", fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors()
            )
            FilterChip(
                selected = selectedFilter == ProtocolType.SAC,
                onClick = { onFilterSelected(ProtocolType.SAC) },
                label = { Text("SAC", fontSize = 11.sp) }
            )
            FilterChip(
                selected = selectedFilter == ProtocolType.WEBLINK,
                onClick = { onFilterSelected(ProtocolType.WEBLINK) },
                label = { Text("WebLink", fontSize = 11.sp) }
            )
            FilterChip(
                selected = selectedFilter == ProtocolType.USB,
                onClick = { onFilterSelected(ProtocolType.USB) },
                label = { Text("USB", fontSize = 11.sp) }
            )
            FilterChip(
                selected = selectedFilter == ProtocolType.SYSTEM,
                onClick = { onFilterSelected(ProtocolType.SYSTEM) },
                label = { Text("SYS", fontSize = 11.sp) }
            )
            FilterChip(
                selected = autoScroll,
                onClick = { onToggleAutoScroll(!autoScroll) },
                leadingIcon = {
                    Icon(
                        Icons.Default.VerticalAlignBottom,
                        contentDescription = "Toggle auto-scroll",
                        modifier = Modifier.size(14.dp)
                    )
                },
                label = { Text("Auto-Scroll", fontSize = 11.sp) }
            )
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChanged,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            placeholder = { Text("Filter logs by text or hex...", fontSize = 12.sp) },
            leadingIcon = {
                Icon(
                    Icons.Default.Search,
                    contentDescription = "Search logs",
                    modifier = Modifier.size(18.dp)
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChanged("") }) {
                        Icon(
                            Icons.Default.Clear,
                            contentDescription = "Clear search query",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            },
            singleLine = true
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun LogControlBarPreview() {
    AppRadioRETheme {
        LogControlBar(
            selectedFilter = ProtocolType.SAC,
            searchQuery = "",
            autoScroll = true,
            totalCount = 42,
            onFilterSelected = {},
            onSearchChanged = {},
            onToggleAutoScroll = {}
        )
    }
}
