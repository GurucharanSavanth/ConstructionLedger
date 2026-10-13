package com.constructioncost.nativeapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.domain.*
import com.constructioncost.nativeapp.platform.UiActions
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuildLedgerApp(repository: LedgerRepository, actions: UiActions, preferences: DataStore<Preferences>? = null) {
    val context = LocalContext.current.applicationContext
    val vm: LedgerViewModel = viewModel(factory = remember(repository) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                LedgerViewModel(context, repository, extras.createSavedStateHandle(), preferences ?: context.ledgerPreferences) as T
        }
    })
    val state by vm.state.collectAsStateWithLifecycle()
    val projects by repository.projects.collectAsStateWithLifecycle(emptyList())
    val nav = rememberNavController()
    val route = nav.currentBackStackEntryAsState().value?.destination?.route.orEmpty()
    val snackbar = remember { SnackbarHostState() }
    var activityFilter by rememberSaveable(stateSaver = listSaver(
        save = { listOf(it.query, it.fromDate, it.toDate, it.classificationId.orEmpty(), it.vendorId.orEmpty(), it.status, it.sort, it.directClassification.toString()) },
        restore = { ExpenseFilter(it[0], it[1], it[2], it[3].ifBlank { null }, it[4].ifBlank { null }, it[5], it[6], it.getOrNull(7) == "true") }
    )) { mutableStateOf(ExpenseFilter()) }
    fun activity(filter: ExpenseFilter = ExpenseFilter()) { activityFilter = filter; nav.navigate("activity") { launchSingleTop = true } }
    fun detail(id: String, type: String) { nav.navigate("detail/$type/$id") }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.dismissMessage() }
    }
    MaterialTheme(colorScheme = lightColorScheme(
        primary = Color(0xFF164A69), onPrimary = Color.White, secondary = Color(0xFF365D43),
        background = Color(0xFFFAFCFE), surface = Color.White, onSurface = Color(0xFF16232B),
        onBackground = Color(0xFF16232B), error = Color(0xFFAA2020)
    )) {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
            TopAppBar(title = {
                Column {
                    Text("BuildLedger", style = MaterialTheme.typography.titleLarge)
                    Text(projects.firstOrNull { it.id == state.projectId }?.name ?: "Local construction ledger", style = MaterialTheme.typography.bodyMedium)
                }
            }, navigationIcon = {
                if (route !in listOf("home", "entry", "activity", "insights", "more")) TextButton({ nav.popBackStack() }) { Text("Back") }
            }, actions = { TextButton({ nav.navigate("projects") }) { Text("Project") } })
        }, bottomBar = {
            NavigationBar {
                listOf("home" to "Home", "entry" to "Add Entry", "activity" to "Activity", "insights" to "Insights", "more" to "More").forEach { (target, label) ->
                    NavigationBarItem(selected = route == target, onClick = {
                        nav.navigate(target) {
                            popUpTo("home") { saveState = true }
                            launchSingleTop = true; restoreState = true
                        }
                    }, icon = { Text(label.take(1), Modifier.clearAndSetSemantics {}) }, label = { Text(label) })
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (!state.ready) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Opening local ledger…", Modifier.padding(16.dp))
                } else if (state.projectId.isBlank()) {
                    Text(state.error ?: "No local project is available.", Modifier.padding(16.dp))
                    Button({ nav.navigate("projects") }, Modifier.padding(16.dp)) { Text("Create project") }
                } else {
                    if (state.error != null && route != "entry") Text(state.error.orEmpty(), Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
                    NavHost(nav, "home", Modifier.weight(1f)) {
                        composable("home") { HomeScreen(vm, { activity(it) }, ::detail, { nav.navigate(it) }) }
                        composable("entry") { EntryScreen(vm, actions, { activity() }, { nav.navigate("payments") }) }
                        composable("activity") { ActivityScreen(vm, activityFilter, { activityFilter = it }, ::detail, actions) }
                        composable("insights") { InsightsScreen(vm, { activity(it) }) }
                        composable("more") { MoreScreen { nav.navigate(it) } }
                        composable("payments") { PaymentsScreen(vm, { nav.navigate("entry") }, ::detail) }
                        composable("calendar") { CalendarScreen(vm, { nav.navigate("entry") }, ::detail) }
                        composable("documents") { DocumentsScreen(vm, actions, ::detail) }
                        composable("vendors") { VendorsScreen(vm, { activity(it) }, ::detail) }
                        composable("projects") { ProjectsScreen(vm) }
                        composable("categories") { CategoriesScreen(vm) }
                        composable("backup") { BackupScreen(vm, actions) }
                        composable("settings") { SettingsScreen(vm, actions) }
                        composable("detail/{type}/{id}") { entry ->
                            DetailScreen(vm, entry.arguments?.getString("id").orEmpty(), entry.arguments?.getString("type").orEmpty(),
                                actions, { nav.navigate("entry") }, ::detail)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(vm: LedgerViewModel, activity: (ExpenseFilter) -> Unit, detail: (String, String) -> Unit, navigate: (String) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val project = state.projectId
    val summary by remember(project) { vm.repository.summary(project) }.collectAsStateWithLifecycle(LedgerSummary())
    val expenses by remember(project) { vm.repository.expenses(project, ExpenseFilter(status = "OUTSTANDING", sort = "AMOUNT_DESC"), 6) }.collectAsStateWithLifecycle(emptyList())
    val plans by remember(project) { vm.repository.instalments(project) }.collectAsStateWithLifecycle(emptyList())
    val recent by remember(project) { vm.repository.activity(project, 6, sortByRecordedTime = true) }.collectAsStateWithLifecycle(emptyList())
    val stages by remember(project) { vm.repository.insights(project, dimension = "WORK") }.collectAsStateWithLifecycle(emptyList())
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Title("Home") }
        item { RecordCard("Recorded cost", "Costs already incurred", Money.format(summary.recordedPaise)) { activity(ExpenseFilter()) } }
        item { RecordCard("Cash paid", "Payments less reversals and refunds", Money.format(summary.paidPaise)) { navigate("payments") } }
        item { RecordCard("Outstanding", "Unpaid incurred costs", Money.format(summary.outstandingPaise)) { activity(ExpenseFilter(status = "OUTSTANDING")) } }
        item { RecordCard("Upcoming 30 days", "Future schedules; separate from costs", Money.format(summary.upcomingPaise)) { navigate("payments") } }
        item {
            val budget = summary.budgetPaise
            RecordCard("Budget", if (budget == null) "Not set · choose a project budget" else
                "${if (summary.recordedPaise <= budget) "Remaining" else "Over budget"}: ${Money.format(kotlin.math.abs(budget - summary.recordedPaise))}",
                budget?.let(Money::format)) { navigate("projects") }
        }
        if (summary.advancePaise > 0) item { RecordCard("Available advances", "Apply existing cash to unpaid expenses", Money.format(summary.advancePaise)) { navigate("payments") } }
        item { RecordCard("Agreed commitments", "Future agreements; excluded from recorded cost", Money.format(summary.commitmentPaise)) { navigate("payments") } }
        if (stages.any { it.id == null && it.amountPaise > 0 }) item {
            RecordCard("Review unclassified costs", "Unclassified costs stay included in totals") { activity(ExpenseFilter(status = "UNCLASSIFIED_WORK")) }
        }
        item { Title("Payments to watch") }
        items(plans.filter { !it.cancelled && it.outstandingPaise > 0 }.sortedBy { it.dueDate }.take(6), key = { "plan-${it.id}" }) { plan ->
            RecordCard(plan.title, "${if (plan.dueDate < LocalDate.now().toString()) "Overdue · " else ""}${plan.dueDate}", Money.format(plan.outstandingPaise)) { detail(plan.id, "INSTALMENT") }
        }
        items(expenses, key = { "expense-${it.id}" }) { expense -> RecordCard(expense.description, "${expense.vendorName} · unpaid", Money.format(expense.outstandingPaise)) { detail(expense.id, "EXPENSE") } }
        item { Title("Work stages") }
        items(stages.take(8), key = { it.id ?: "unclassified" }) { stage ->
            RecordCard(stage.name, "${stage.expenseCount} expenses", Money.format(stage.amountPaise)) { activity(ExpenseFilter(classificationId = stage.id, status = if (stage.id == null) "UNCLASSIFIED_WORK" else "ALL")) }
        }
        item { Title("Recent activity") }
        items(recent, key = { "${it.type}-${it.id}" }) { row -> RecordCard(row.title, "${row.type} · ${row.date}", row.amountPaise?.let(Money::format)) { detail(row.id, if (row.type == "ATTACHMENT") "DOCUMENT" else row.type) } }
        if (recent.isEmpty()) item { Text("Your ledger is empty. Record a three-field expense in Add Entry.") }
    }
}

@Composable
private fun MoreScreen(navigate: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Title("More") }
        items(listOf("payments" to "Payments and schedules", "calendar" to "Calendar", "documents" to "Documents", "vendors" to "Vendors", "projects" to "Projects", "categories" to "Categories", "backup" to "Backup and recovery", "settings" to "Local settings")) { (route, title) ->
            RecordCard(title, "Open $title") { navigate(route) }
        }
    }
}
