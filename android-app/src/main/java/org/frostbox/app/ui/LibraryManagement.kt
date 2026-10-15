package org.frostbox.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import org.frostbox.app.R

/** Shared by Library/details and isolated device QA; all callbacks edit records only. */
@Composable fun GameRecordMenu(id: String, busy: Boolean, remove: () -> Unit,details: (() -> Unit)?=null,settings: (() -> Unit)?=null,cover: (() -> Unit)?=null) {
    var expanded by rememberSaveable(id) { mutableStateOf(false) }
    Box {
        TextButton(onClick={expanded=true},enabled=!busy,modifier=Modifier.testTag("game-actions-$id")) {
            Text(stringResource(R.string.library_game_actions))
        }
        DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
            details?.let {DropdownMenuItem(text={Text("Об игре")},onClick={expanded=false;it()})}
            settings?.let {DropdownMenuItem(text={Text("Настройки профиля")},onClick={expanded=false;it()})}
            cover?.let {DropdownMenuItem(text={Text("Изменить обложку")},onClick={expanded=false;it()})}
            DropdownMenuItem(text={Text(stringResource(R.string.library_remove_action),color=MaterialTheme.colorScheme.error)},
                onClick={expanded=false;remove()},modifier=Modifier.testTag("remove-entry-$id"))
        }
    }
}

@Composable fun LibraryListControls(count: Int,busy: Boolean,add: () -> Unit,clear: () -> Unit) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
    Button(onClick=add,enabled=!busy,modifier=Modifier.testTag("add-game")) { Text(stringResource(R.string.library_add_game)) }
    if(count>0) TextButton(onClick=clear,enabled=!busy,modifier=Modifier.testTag("clear-library")) {Text(stringResource(R.string.library_clear_action),color=MaterialTheme.colorScheme.error)}
    }
    if(count==0) {
        Text(stringResource(R.string.library_empty_title),style=MaterialTheme.typography.titleLarge,modifier=Modifier.testTag("library-empty"))
        Text(stringResource(R.string.library_empty_message))
    }
}

@Composable fun LibraryRemovalDialog(clear: Boolean,count: Int,active: Boolean,busy: Boolean,confirm: () -> Unit,dismiss: () -> Unit) {
    AlertDialog(onDismissRequest=dismiss,
        title={Text(stringResource(if(active) R.string.library_stop_title else if(clear) R.string.library_clear_title else R.string.library_remove_title))},
        text={Text(if(active) stringResource(R.string.library_stop_first) else if(clear) stringResource(R.string.library_clear_message,count) else stringResource(R.string.library_remove_message))},
        confirmButton={if(!active) TextButton(onClick=confirm,enabled=!busy && (!clear || count>0),modifier=Modifier.testTag("library-removal-confirm")) {
            Text(stringResource(if(clear) R.string.library_clear_action else R.string.library_delete))
        }},
        dismissButton={TextButton(onClick=dismiss,modifier=Modifier.testTag("library-removal-cancel")) {
            Text(stringResource(if(active) R.string.library_ok else R.string.library_cancel))
        }})
}
