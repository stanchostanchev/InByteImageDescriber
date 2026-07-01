package com.inbyte.imagedescriber.ui.description

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// Set to true to show the raw description text card in the bottom sheet.
// When false, a circular progress indicator is overlaid on the image instead
// while the description is being generated.
private const val SHOW_DESCRIPTION_CARD_IN_SHEET = false

/** Shown after tapping an image in the Images tab: runs the SmolVLM description/classification
 *  flow and lets the user generate a fairy tale, without navigating away from Images. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DescribeBottomSheet(
    selectedImageUri: String?,
    onDismiss: () -> Unit,
    onGenerateFairyTale: (String) -> Unit,
    viewModel: DescriptionViewModel,
    sheetState: SheetState,
) {
    if (selectedImageUri != null) {
        viewModel.setImageAndDescribe(selectedImageUri)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        DescriptionBody(
            viewModel = viewModel,
            onGenerateFairyTale = onGenerateFairyTale,
            showDescriptionCard = SHOW_DESCRIPTION_CARD_IN_SHEET,
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
        )
    }
}
