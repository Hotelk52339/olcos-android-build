package io.olcos.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.olcos.app.R
import io.olcos.app.core.Maintenance

/**
 * The one room field, wherever a room is taken: the add-protocol dialog, the
 * room dialog and the install form. It says what a room of this protocol
 * looks like (its placeholder and its hint) and offers "Make a room" where
 * the app can make one.
 */
@Composable
fun CarrierRoomInput(
    carrier: String,
    room: String,
    onRoomChange: (String) -> Unit,
    onMakeRoom: () -> Unit,
    makeBusy: Boolean = false,
) {
    val p = OlcosTheme.palette
    Column {
        OutlinedTextField(
            value = room, onValueChange = onRoomChange, singleLine = true,
            label = { Text(stringResource(R.string.add_server_room)) },
            placeholder = { Text(stringResource(Maintenance.roomPlaceholderFor(carrier))) },
            colors = roomFieldColors(p),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Text(stringResource(Maintenance.roomHintFor(carrier)), style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
        if (Maintenance.canMakeRoom(carrier)) {
            Spacer(Modifier.height(8.dp))
            OlcButton(stringResource(R.string.action_make_room), onMakeRoom, fillWidth = true, busy = makeBusy)
        }
    }
}

@Composable
private fun roomFieldColors(p: OlcosPalette) = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = p.accentTint, unfocusedBorderColor = p.separator,
    focusedLabelColor = p.accentTint, unfocusedLabelColor = p.textSecondary,
    focusedTextColor = p.textPrimary, unfocusedTextColor = p.textPrimary, cursorColor = p.accentTint,
)
