package org.frostbox.app.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import org.frostbox.app.R
import org.frostbox.app.data.*
import org.frostbox.app.runtime.RuntimeFileSharing

@Composable fun StorageScreen() {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var report by remember {mutableStateOf<StorageInventory?>(null)}
    var working by remember {mutableStateOf(false)};var error by remember {mutableStateOf("")}
    var sharing by remember {mutableStateOf<RuntimeFileSharing.Plan?>(null)}
    var confirm by remember {mutableStateOf(false)}
    fun refresh(){if(working)return;working=true;scope.launch {try {report=inspectStorage(context)}catch(e: Exception){error=e.message ?: "IO error"}finally{working=false}}}
    LaunchedEffect(Unit){refresh()}
    Text(stringResource(R.string.nav_storage),style=MaterialTheme.typography.headlineMedium)
    Text(stringResource(R.string.storage_explanation))
    if(working)LinearProgressIndicator()
    report?.let {r ->
        Text(stringResource(R.string.storage_allocated_total,android.text.format.Formatter.formatFileSize(context,r.bytes.values.sum())))
        for((key,label) in listOf("games" to R.string.storage_games,"prefixes" to R.string.storage_prefixes,"shader" to R.string.storage_shader,"logs" to R.string.storage_logs,"covers" to R.string.storage_covers,"runtime" to R.string.storage_runtime,"temporary" to R.string.storage_temporary,"other" to R.string.storage_other))
            Text(stringResource(label)+": "+android.text.format.Formatter.formatFileSize(context,r.bytes[key] ?: 0))
        Text(stringResource(R.string.storage_free,android.text.format.Formatter.formatFileSize(context,r.availableBytes)))
        if(r.unreadable>0)Text(stringResource(R.string.storage_unreadable,r.unreadable))
    }
    if(error.isNotEmpty())Text(error,color=MaterialTheme.colorScheme.error)
    OutlinedButton(onClick={refresh()},enabled=!working){Text(stringResource(R.string.storage_refresh))}
    OutlinedButton(onClick={
        working=true;error=""
        scope.launch { try { sharing=RuntimeFileSharing.inspect(context) }
            catch(e: Exception){error=e.message ?: "Runtime storage error"}finally{working=false} }
    },enabled=!working){Text(stringResource(R.string.storage_analyze_sharing))}
    sharing?.let { plan ->
        Text(stringResource(R.string.storage_sharing_estimate,android.text.format.Formatter.formatFileSize(context,plan.expectedReclaim),plan.replacements.size))
        Button(onClick={confirm=true},enabled=!working && (plan.replacements.isNotEmpty() || plan.referenceTemps.isNotEmpty())){Text(stringResource(R.string.storage_share_files))}
        if(confirm)AlertDialog(onDismissRequest={confirm=false},title={Text(stringResource(R.string.storage_share_files))},
            text={Text(stringResource(R.string.storage_sharing_policy))},
            confirmButton={TextButton(onClick={confirm=false;working=true;error="";scope.launch {
                try { RuntimeFileSharing.apply(context,plan);sharing=null;report=inspectStorage(context) }
                catch(e: Exception){error=e.message ?: "Runtime storage error"}finally{working=false}
            }}){Text(stringResource(R.string.storage_share_files))}},
            dismissButton={TextButton(onClick={confirm=false}){Text(stringResource(R.string.library_cancel))}})
    }
}
