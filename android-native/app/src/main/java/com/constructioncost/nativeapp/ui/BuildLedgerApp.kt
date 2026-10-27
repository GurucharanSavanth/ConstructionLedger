package com.constructioncost.nativeapp.ui

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.compose.*
import com.constructioncost.nativeapp.data.local.LedgerRecoveryStateStore
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.domain.*
import com.constructioncost.nativeapp.platform.UiActions

internal val webDestinations = listOf("home" to "Home", "entry" to "Add Entry", "activity" to "Activity", "documents" to "Files", "explore" to "Explore")

internal fun NavController.navigateIfGraphReady(route: String, optionsBuilder: NavOptionsBuilder.() -> Unit): Boolean {
    if (currentDestination == null) return false
    navigate(route, optionsBuilder)
    return true
}

@Composable
fun BuildLedgerApp(repository: LedgerRepository, actions: UiActions, preferences: DataStore<Preferences>? = null,
    onRecoveryDiagnostic: () -> Unit = {}, onRecoveryRetry: (() -> Unit)? = null) {
    val context = LocalContext.current.applicationContext
    val vm: LedgerViewModel = viewModel(factory = remember(repository) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                LedgerViewModel(context, repository, extras.createSavedStateHandle(), preferences ?: context.ledgerPreferences) as T
        }
    })
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(repository) {
        if (vm.state.value.recoveryRequired && !LedgerRecoveryStateStore(context).isRecoveryRequired()) vm.retryInitialization()
    }
    val projects = state.projects
    val nav = rememberNavController()
    val route = nav.currentBackStackEntryAsState().value?.destination?.route ?: "home"
    var lastTab by rememberSaveable { mutableStateOf("home") }
    LaunchedEffect(route) { if(route in webDestinations.map { it.first }) lastTab=route }
    val activeTab = when { route in listOf("entry-form","allocate") -> "entry"; route == "calendar" -> "explore"; route in webDestinations.map { it.first } -> route; else -> lastTab }
    val snackbar = remember { SnackbarHostState() }
    var activityFilter by rememberSaveable(state.projectId, stateSaver = listSaver(
        save = { listOf(it.query, it.fromDate, it.toDate, it.classificationId.orEmpty(), it.vendorId.orEmpty(), it.status, it.sort, it.directClassification.toString(), it.materialName.orEmpty(), it.missingVendor.toString(), it.postedOnly.toString(),it.costNature.orEmpty()) },
        restore = { ExpenseFilter(it[0],it[1],it[2],it[3].ifBlank { null },it[4].ifBlank { null },it[5],it[6],it.getOrNull(7)=="true",it.getOrNull(8)?.ifBlank { null },it.getOrNull(9)=="true",it.getOrNull(10)=="true",it.getOrNull(11)?.ifBlank{null}) }
    )) { mutableStateOf(ExpenseFilter()) }
    fun navigate(target: String) {
        if (!state.ready || state.recoveryRequired) return
        val destination = if (!state.hasActiveProject && target !in listOf("projects", "backup", "settings")) "projects" else target
        nav.navigateIfGraphReady(destination) {
        if(destination in webDestinations.map{it.first}) {popUpTo("home"){saveState=true};restoreState=true}
        launchSingleTop = true
    } }
    @Composable fun ProjectContent(content: @Composable () -> Unit) {
        if (state.hasActiveProject) content() else ProjectsScreen(vm, actions)
    }
    fun activity(filter: ExpenseFilter = ExpenseFilter()) { activityFilter = filter; navigate("activity") }
    fun detail(id: String, type: String) { navigate("detail/$type/${Uri.encode(id)}") }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.dismissMessage() } }
    WebTheme {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.White)) {
            val wide = maxWidth >= 760.dp
            val railWidth = if(maxWidth<1100.dp) 190.dp else 222.dp
            Row(Modifier.fillMaxSize()) {
                if(wide) Surface(Modifier.width(railWidth).fillMaxHeight(), color=Color.White, border=BorderStroke(1.dp,WebColors.line)) {
                    Column(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(horizontal=13.dp,vertical=24.dp)) {
                        Row(Modifier.padding(11.dp).padding(bottom=24.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(11.dp)) {
                            WebIcon("home",Modifier.size(34.dp))
                            Column { Text("BuildLedger",fontSize=20.sp,fontWeight=FontWeight.ExtraBold);Text("Construction Manager",fontSize=11.sp,color=WebColors.muted) }
                        }
                        webDestinations.forEach { (target,label) -> WebNavItem(target,label,activeTab==target,false) { navigate(target) } }
                        Spacer(Modifier.weight(1f))
                        Text("●  Stored on this device",color=WebColors.teal,fontSize=12.sp,fontWeight=FontWeight.SemiBold)
                        Text("Private, offline-first\nBack up your project regularly.",Modifier.padding(top=9.dp),fontSize=12.sp,color=WebColors.muted)
                    }
                }
                Scaffold(Modifier.weight(1f),containerColor=WebColors.background,
                    snackbarHost={ SnackbarHost(snackbar) },
                    topBar={
                        Surface(color=WebColors.header) {
                            Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
                                Row(Modifier.fillMaxWidth().heightIn(min=76.dp).padding(horizontal=if(wide) 40.dp else 16.dp,vertical=14.dp),
                                    verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    if(route in listOf("entry-form","allocate","payments","vendors","projects")) IconButton({ nav.popBackStack() }) { WebIcon("back",Modifier.semanticsLabel("Back")) }
                                    val projectName = projects.firstOrNull { it.id==state.projectId && !it.archived }?.name ?: if (state.ready) "No project selected" else "Loading project"
                                    TextButton({ navigate("projects") }, Modifier.weight(1f).semantics { contentDescription = "Select project. Current project: $projectName" }) {
                                        Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                                            Text("PROJECT",fontSize=10.sp,letterSpacing=1.3.sp,fontWeight=FontWeight.Bold,color=WebColors.muted)
                                            Text(projectName,fontSize=14.sp,fontWeight=FontWeight.SemiBold)
                                        }
                                    }
                                    WebHeaderButton("backup","Backup and restore") { navigate("backup") }
                                    WebHeaderButton("settings","Project settings") { navigate("settings") }
                                }
                                HorizontalDivider(color=WebColors.line)
                            }
                        }
                    },
                    bottomBar={ if(!wide) Surface(color=WebColors.header) {
                        Column {
                            HorizontalDivider(color=WebColors.line)
                            Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal=3.dp,vertical=6.dp)) {
                                webDestinations.forEach { (target,label) -> Box(Modifier.weight(1f)) { WebNavItem(target,label,activeTab==target,true) { navigate(target) } } }
                            }
                        }
                    } }) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        if (state.recoveryState == com.constructioncost.nativeapp.data.local.LedgerRecoveryState.PREFERENCES_UNAVAILABLE) {
                            Text(state.error ?: "Preferences are unavailable. Unsaved drafts and settings may be lost when the app closes.",
                                Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error)
                            TextButton(vm::retryPreferences, Modifier.padding(horizontal = 8.dp)) { Text("Retry saving preferences") }
                        }
                        Box(Modifier.weight(1f)) {
                        when {
                            state.recoveryRequired -> RecoveryScreen(LedgerRecoveryStateStore(context).state(),state.error,onRecoveryRetry ?: vm::retryInitialization,actions.restoreBackup,onRecoveryDiagnostic)
                            !state.ready -> StartupScreen()
                            else -> NavHost(nav,startDestination="home") {
                                composable("home") { ProjectContent { key(state.projectId) { WebHomeScreen(vm,::activity,::detail,::navigate) } } }
                                composable("entry") { ProjectContent { EntryChoices(vm) { kind -> if(kind=="Allocate advance") navigate("allocate") else navigate("entry-form") } } }
                                composable("allocate") { ProjectContent { PaymentsScreen(vm,{navigate("entry-form")},::detail,allocationOnly=true) } }
                                composable("entry-form") { ProjectContent { EntryScreen(vm,actions,{ activity() },{ navigate("payments") }) } }
                                composable("activity") { ProjectContent { key(state.projectId) { WebActivityScreen(vm,activityFilter,{ activityFilter=it },::detail,actions) } } }
                                composable("documents") { ProjectContent { key(state.projectId) { DocumentsScreen(vm,actions,::detail) { navigate("backup") } } } }
                                composable("explore") { ProjectContent { key(state.projectId) { WebExploreScreen(vm,::activity,::detail,::navigate,enableReminders={actions.setReminders(true)}) } } }
                                composable("payments") { ProjectContent { PaymentsScreen(vm,{ navigate("entry-form") },::detail) } }
                                composable("calendar") { ProjectContent { WebExploreScreen(vm,::activity,::detail,::navigate,"calendar") {actions.setReminders(true)} } }
                                dialog("categories",dialogProperties=DialogProperties(usePlatformDefaultWidth=false)) { NativeModal("Categories",{nav.popBackStack()}) { ProjectContent { CategoriesScreen(vm) } } }
                                composable("vendors") { ProjectContent { key(state.projectId) { VendorsScreen(vm,::activity,::detail) } } }
                                composable("projects") { ProjectsScreen(vm,actions) }
                                dialog("backup",dialogProperties=DialogProperties(usePlatformDefaultWidth=false)) { NativeModal("Backup & Restore",{nav.popBackStack()}) {
                                    BackupScreen(vm,actions.copy(restoreBackup={ password -> nav.popBackStack(); actions.restoreBackup(password) }))
                                } }
                                dialog("settings",dialogProperties=DialogProperties(usePlatformDefaultWidth=false)) { key(state.projectId) { NativeModal("Project settings",{nav.popBackStack()}) {
                                    SettingsScreen(vm,actions,{navigate("projects")},{navigate("vendors")})
                                } } }
                                dialog("detail/{type}/{id}",dialogProperties=DialogProperties(usePlatformDefaultWidth=false)) { entry -> ProjectContent { key(state.projectId) {
                                    NativeModal(when(entry.arguments?.getString("type")){"DOCUMENT"->"Document details";"EXPENSE"->"Bill details";"PAYMENT"->"Payment details";"INSTALMENT"->"Planned payment";"COMMITMENT"->"Contract details";else->"Entry details"},{nav.popBackStack()}) {
                                        DetailScreen(vm,entry.arguments?.getString("id").orEmpty(),entry.arguments?.getString("type").orEmpty(),actions,{ navigate("entry-form") },::detail,showTitle=false,onDeleted={nav.popBackStack()})
                                    }
                                } } }
                            }
                        }
                        }
                    }
                }
            }
        }
        if (state.pendingDraftReplacement) AlertDialog(onDismissRequest = vm::cancelDraftReplacement,
            title = { Text("Discard current draft?") },
            text = { Text("The current entry has unsaved changes. Start another entry?") },
            confirmButton = { TextButton(vm::confirmDraftReplacement) { Text("Discard current draft") } },
            dismissButton = { TextButton(vm::cancelDraftReplacement) { Text("Cancel") } })
        state.actionError?.let { message -> AlertDialog(onDismissRequest = vm::dismissActionError,
            title = { Text("Action failed") }, text = { Text(message) },
            confirmButton = { TextButton(vm::dismissActionError) { Text("OK") } }) }
    }
}

@Composable
private fun NativeModal(title:String,close:()->Unit,content:@Composable ()->Unit) {
    Surface(Modifier.fillMaxWidth(.94f).widthIn(max=980.dp).fillMaxHeight(.91f).imePadding(),shape=RoundedCornerShape(14.dp),color=Color.White) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start=20.dp,end=8.dp,top=8.dp,bottom=4.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(title,Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                IconButton(close,Modifier.semanticsLabel("Close")) {Text("×",fontSize=28.sp,color=WebColors.muted)}
            }
            Box(Modifier.weight(1f)) {content()}
        }
    }
}

private fun Modifier.semanticsLabel(label: String) = this.then(Modifier.semantics { contentDescription=label })

@Composable
private fun WebHeaderButton(icon: String,label: String,click: () -> Unit) {
    Surface(onClick=click,modifier=Modifier.size(43.dp).semanticsLabel(label),shape=RoundedCornerShape(12.dp),color=Color.White,border=BorderStroke(1.dp,Color(0xFFD1DFEF))) {
        Box(contentAlignment=Alignment.Center) { WebIcon(icon,tint=WebColors.ink) }
    }
}

@Composable
private fun WebNavItem(target: String,label: String,selected: Boolean,compact: Boolean,click: () -> Unit) {
    Surface(onClick=click,modifier=Modifier.fillMaxWidth().padding(1.dp),shape=RoundedCornerShape(if(compact) 8.dp else 12.dp),
        color=if(selected) WebColors.blueSoft else WebColors.header,contentColor=if(selected) WebColors.blue else WebColors.muted) {
        if(compact) Column(Modifier.padding(vertical=9.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(5.dp)) {
            WebIcon(target,Modifier.size(21.dp),if(selected) WebColors.blue else WebColors.muted)
            Text(label,fontSize=10.sp,fontWeight=FontWeight.SemiBold,maxLines=1)
        } else Row(Modifier.padding(horizontal=15.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            WebIcon(target,tint=if(selected) WebColors.blue else WebColors.muted);Text(label,fontWeight=FontWeight.SemiBold)
        }
    }
}

internal data class EntryChoice(val kind: String,val icon: String,val title: String,val description: String)
internal val webEntryChoices = listOf(
    EntryChoice("Purchase","purchase","Bought materials","Enter the total directly or calculate by quantity"),
    EntryChoice("Payment","money","Paid someone","Bill settlement or supplier advance"),
    EntryChoice("Labour","labour","Labour or work","Wages, contractor or service"),
    EntryChoice("Other cost","other","Other expense","Transport, fees, hire, utilities"),
    EntryChoice("Received bill","file","Received a bill","Record now, pay later"),
    EntryChoice("Plan","calendar","Plan a payment","Instalment, due date, reminder"),
    EntryChoice("Contract","contract","Contract & instalments","Engineer and contractor agreements"),
    EntryChoice("Allocate advance","allocate","Apply an advance","Use existing advance on a bill"),
    EntryChoice("Work log","time","Work log / timer","Site visit, notes, clocked time"))

@Composable
private fun EntryChoices(vm: LedgerViewModel,open: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=16.dp),contentPadding=PaddingValues(vertical=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { PageHeading("Add Entry","Choose what happened. Only the relevant fields will appear.") }
        items(webEntryChoices.chunked(2)) { pair -> Row(horizontalArrangement=Arrangement.spacedBy(10.dp),modifier=Modifier.height(IntrinsicSize.Max)) {
            pair.forEach { choice -> Surface(onClick={ vm.openNewEntry(choice.kind) { open(choice.kind) } },modifier=Modifier.weight(1f).fillMaxHeight(),shape=RoundedCornerShape(12.dp),color=Color.White,border=BorderStroke(1.dp,WebColors.line)) {
                Column(Modifier.padding(16.dp).heightIn(min=if(choice.description.length>35) 116.dp else 98.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
                    WebIcon(choice.icon,Modifier.size(18.dp))
                    Text(choice.title,fontSize=14.sp,fontWeight=FontWeight.Bold)
                    Text(choice.description,color=WebColors.muted,fontSize=13.sp,lineHeight=19.sp)
                }
            } }
            if(pair.size==1) Spacer(Modifier.weight(1f))
        } }
        item { WebCard(color=WebColors.soft) { Text("Keeping figures correct:",fontWeight=FontWeight.Bold);Text("A new bill records a cost. A payment records money leaving. A planned instalment is not money paid. The application keeps them separate.",color=WebColors.muted) } }
    }
}
