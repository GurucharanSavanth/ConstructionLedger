package com.constructioncost.nativeapp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.constructioncost.nativeapp.domain.*
import com.constructioncost.nativeapp.platform.UiActions
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.catch
import java.time.LocalDate

@Composable
internal fun WebHomeScreen(vm: LedgerViewModel,activity: (ExpenseFilter) -> Unit,detail: (String,String) -> Unit,navigate: (String) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val project=state.projectId
    val date = rememberLedgerDate()
    val loadedSummary by key(project, date) { remember(project, date) { vm.repository.summary(project, date.today).catch { vm.storageFailure(it) } }.collectAsStateWithLifecycle<LedgerSummary?>(null) }
    val loadedBills by remember(project) { vm.repository.expenses(project,ExpenseFilter(status="OUTSTANDING",sort="AMOUNT_DESC"),4).catch { vm.storageFailure(it) } }.collectAsStateWithLifecycle<List<ExpenseRow>?>(null)
    val loadedBillTotals by remember(project) { vm.repository.filteredExpenseSummary(project,ExpenseFilter()).catch { vm.storageFailure(it) } }.collectAsStateWithLifecycle<ExpenseSummary?>(null)
    val loadedUnpaidTotals by remember(project) { vm.repository.filteredExpenseSummary(project,ExpenseFilter(status="OUTSTANDING")).catch { vm.storageFailure(it) } }.collectAsStateWithLifecycle<ExpenseSummary?>(null)
    val loadedPlans by remember(project) { vm.repository.instalments(project).catch { vm.storageFailure(it) } }.collectAsStateWithLifecycle<List<InstalmentRow>?>(null)
    val loadedRecent by remember(project) { vm.repository.activity(project,6,sortByRecordedTime=true).catch { vm.storageFailure(it) } }.collectAsStateWithLifecycle<List<ActivityRow>?>(null)
    val loadedCosts by remember(project) { vm.repository.insights(project,dimension="COST").catch { vm.storageFailure(it) } }.collectAsStateWithLifecycle<List<InsightRow>?>(null)
    val loadedCash by remember(project) { vm.repository.monthlyCash(project).catch { vm.storageFailure(it) } }.collectAsStateWithLifecycle<List<MonthlyRow>?>(null)
    val summary = loadedSummary ?: return StartupScreen("Loading project finances")
    val bills = loadedBills ?: return StartupScreen("Loading project finances")
    val billTotals = loadedBillTotals ?: return StartupScreen("Loading project finances")
    val unpaidTotals = loadedUnpaidTotals ?: return StartupScreen("Loading project finances")
    val plans = loadedPlans ?: return StartupScreen("Loading project finances")
    val recent = loadedRecent ?: return StartupScreen("Loading project finances")
    val costs = loadedCosts ?: return StartupScreen("Loading project finances")
    val cash = loadedCash ?: return StartupScreen("Loading project finances")
    val unclassified=costs.firstOrNull { it.id==null }?.amountPaise ?: 0L
    val upcoming=plans.filter { !it.cancelled && it.outstandingPaise>0 }.sortedBy { it.dueDate }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=16.dp),contentPadding=PaddingValues(vertical=24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        item { PageHeading("Project overview","Your construction finances, clearly accounted for.")
            Row(Modifier.padding(top=15.dp),horizontalArrangement=Arrangement.spacedBy(9.dp)) {
                OutlinedButton({ navigate("calendar") }) { Text("Payment calendar") }
                Button({ navigate("entry") }) { Text("+ New entry") }
            }
        }
        item {
            Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp),modifier=Modifier.height(IntrinsicSize.Max)) {
                    Kpi("Money paid",summary.paidPaise,"Actual cash outflow ↗",true,Modifier.weight(1f).fillMaxHeight()) { activity(ExpenseFilter(status="PAYMENT")) }
                    Kpi("Recorded cost",summary.recordedPaise,"${billTotals.count} supplier bills",false,Modifier.weight(1f).fillMaxHeight()) { activity(ExpenseFilter(status="BILLS")) }
                }
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp),modifier=Modifier.height(IntrinsicSize.Max)) {
                    Kpi("Outstanding bills",summary.outstandingPaise,"${unpaidTotals.count} awaiting settlement",false,Modifier.weight(1f).fillMaxHeight()) { activity(ExpenseFilter(status="OUTSTANDING")) }
                    Kpi("Next 30 days",summary.upcomingPaise,"Scheduled payments ↗",false,Modifier.weight(1f).fillMaxHeight()) { navigate("calendar") }
                }
            }
        }
        item { Text("Bills recorded: ${Money.format(summary.recordedPaise)} · Unapplied advances: ${Money.format(summary.advancePaise)}. Plans may overlap unpaid bills; do not add them together.",color=WebColors.muted,style=MaterialTheme.typography.bodyMedium) }
        if(unclassified>0) item { WebCard(color=WebColors.soft) {
            Text("${Money.format(unclassified)} needs a cost category",fontWeight=FontWeight.SemiBold)
            Text("These expenses remain included in all project totals. Classify them when you are ready.",color=WebColors.muted)
            OutlinedButton({ activity(ExpenseFilter(status="UNCLASSIFIED")) }) { Text("Review bills") }
        } }
        item { WebCard { Eyebrow("CASH FLOW"); MonthlyCashChart(cash) } }
        item { WebCard {
            Eyebrow("PROJECT BUDGET")
            Text(summary.budgetPaise?.takeIf {it>0}?.let(Money::format) ?: "Set your budget",fontSize=30.sp,fontWeight=FontWeight.SemiBold)
            Text("Recorded bills against your project budget.",color=WebColors.muted,style=MaterialTheme.typography.bodyMedium)
            val budget=summary.budgetPaise ?: 0L
            LinearProgressIndicator(progress={ if(budget>0) (summary.recordedPaise.toDouble()/budget).toFloat().coerceIn(0f,1f) else 0f },modifier=Modifier.fillMaxWidth().height(8.dp),color=WebColors.teal,trackColor=WebColors.soft)
            val percentage=if(budget>0) java.math.BigDecimal.valueOf(summary.recordedPaise).multiply(java.math.BigDecimal(100)).divide(java.math.BigDecimal.valueOf(budget),0,java.math.RoundingMode.HALF_UP).toPlainString()+"%" else "—"
            Text(percentage,fontSize=21.sp,fontWeight=FontWeight.Bold)
            Text(if(budget>0) Money.format(kotlin.math.abs(budget-summary.recordedPaise))+if(summary.recordedPaise>budget) " over budget" else " remaining" else "Add a budget to track remaining funds",color=WebColors.muted)
            OutlinedButton({ navigate("settings") }) { Text("Edit project budget") }
        } }
        item { WebCard {
            Eyebrow("COST DISTRIBUTION")
            Text("Where the cost went",style=MaterialTheme.typography.titleLarge)
            TextButton({ navigate("explore") },contentPadding=PaddingValues(0.dp)) { Text("Explore costs →") }
            if(costs.none { it.amountPaise>0 }) EmptyPanel("Nothing recorded yet","Add a purchase or bill to see the cost breakdown.")
            costs.filter { it.amountPaise>0 }.sortedByDescending { it.amountPaise }.take(6).forEachIndexed { i,row ->
                CostBar(row.name,row.amountPaise,summary.recordedPaise,i) { activity(if(row.id==null) ExpenseFilter(status="UNCLASSIFIED") else ExpenseFilter(classificationId=row.id)) }
            }
        } }
        item { WebCard {
            Eyebrow("UP NEXT");Text("Payments to watch",style=MaterialTheme.typography.titleLarge)
            TextButton({ navigate("calendar") },contentPadding=PaddingValues(0.dp)) { Text("View calendar →") }
            upcoming.take(3).forEach { plan -> WebListRow(plan.title,"${plan.dueDate}${if(plan.dueDate<date.today.toString()) " · Overdue" else ""}",Money.format(plan.outstandingPaise)) { detail(plan.id,"INSTALMENT") } }
            bills.take(maxOf(1,4-upcoming.take(3).size)).forEach { bill -> WebListRow(bill.vendorName.ifBlank { bill.description },"${bill.description} · Unpaid bill",Money.format(bill.outstandingPaise)) { detail(bill.id,"EXPENSE") } }
            if(upcoming.isEmpty() && bills.isEmpty()) EmptyPanel("Everything is up to date","No unpaid bills or scheduled payments.")
        } }
        item { WebCard {
            Eyebrow("PROJECT JOURNAL");Text("Recent activity & changes",style=MaterialTheme.typography.titleLarge)
            TextButton({ activity(ExpenseFilter()) },contentPadding=PaddingValues(0.dp)) { Text("View all activity →") }
            if(recent.isEmpty()) EmptyPanel("No matching activity","Add an entry to start your project journal.")
            recent.forEach { row -> EventRow(row) { detail(row.id,if(row.type=="ATTACHMENT") "DOCUMENT" else row.type) } }
        } }
    }
}

@Composable
private fun Kpi(label:String,amount:Long,description:String,hero:Boolean,modifier:Modifier,click:()->Unit) {
    Surface(onClick=click,modifier=modifier,shape=RoundedCornerShape(12.dp),color=if(hero) WebColors.teal else Color.White,contentColor=if(hero) Color.White else WebColors.ink,border=BorderStroke(1.dp,if(hero) WebColors.teal else WebColors.line)) {
        Column(Modifier.padding(horizontal=15.dp,vertical=18.dp).heightIn(min=105.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(label,color=if(hero) Color(0xFFD1E6DC) else WebColors.muted,fontSize=12.sp,fontWeight=FontWeight.SemiBold)
            Text(Money.format(amount),fontSize=22.sp,fontWeight=FontWeight.Bold,letterSpacing=(-0.7).sp)
            Spacer(Modifier.weight(1f));Text(description,color=if(hero) Color(0xFFD1E6DC) else WebColors.muted,fontSize=13.sp)
        }
    }
}

@Composable
internal fun Eyebrow(label:String) { Text(label,color=WebColors.muted,fontSize=10.sp,letterSpacing=1.2.sp,fontWeight=FontWeight.Bold) }

@Composable
internal fun CostBar(label:String,amount:Long,total:Long,index:Int,click:()->Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick=click).padding(vertical=6.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(label,Modifier.weight(1f),fontSize=13.sp);Text(Money.format(amount),fontSize=13.sp,fontWeight=FontWeight.SemiBold)
        }
        LinearProgressIndicator(progress={ if(total>0) (amount.toDouble()/total).toFloat().coerceIn(0f,1f) else 0f },modifier=Modifier.fillMaxWidth().height(7.dp),color=WebColors.chart[index%WebColors.chart.size],trackColor=WebColors.soft)
    }
}

@Composable
private fun EventRow(row:ActivityRow,click:()->Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick=click).padding(vertical=14.dp),verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(31.dp).background(WebColors.blueSoft,RoundedCornerShape(9.dp)),contentAlignment=Alignment.Center) { WebIcon(when(row.type){"EXPENSE"->"file";"PAYMENT","REFUND","REVERSAL"->"money";"INSTALMENT"->"calendar";"ATTACHMENT"->"documents";"COMMITMENT"->"contract";else->"time"},Modifier.size(17.dp)) }
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(row.title,style=MaterialTheme.typography.titleMedium)
            Text(row.party.ifBlank { eventLabel(row.type) },fontSize=12.sp,color=WebColors.muted)
            Text(row.date,fontSize=11.sp,color=WebColors.muted)
        }
        Column(horizontalAlignment=Alignment.End,verticalArrangement=Arrangement.spacedBy(6.dp)) {
            row.amountPaise?.let { Text(Money.format(it),fontSize=13.sp,fontWeight=FontWeight.SemiBold) }
            Text(eventLabel(row.type),fontSize=10.sp,color=WebColors.teal)
        }
    }
    HorizontalDivider(color=WebColors.line)
}

private fun eventLabel(type:String)=when(type){"EXPENSE"->"Bill recorded";"PAYMENT"->"Paid";"INSTALMENT"->"Scheduled";"COMMITMENT"->"Contract";"NOTE"->"Work log";"ATTACHMENT"->"File added";"AUDIT"->"Change log";else->type.lowercase().replaceFirstChar { it.uppercase() }}

@Composable
internal fun WebActivityScreen(vm:LedgerViewModel,filter:ExpenseFilter,setFilter:(ExpenseFilter)->Unit,detail:(String,String)->Unit,actions:UiActions) {
    val state by vm.state.collectAsStateWithLifecycle()
    val project=state.projectId
    var type by rememberSaveable(project) { mutableStateOf("ALL") }
    val eventStatuses=setOf("PAYMENT","BILLS","OVERDUE","SETTLED")
    val expenseFilter=filter.copy(status=if(filter.status=="SETTLED") "PAID" else if(filter.status in eventStatuses) "ALL" else filter.status,postedOnly=true)
    val error=when {
        filter.query.length>200 -> "Search must be 200 characters or less."
        filter.fromDate.isNotBlank() && runCatching { LocalDate.parse(filter.fromDate) }.isFailure -> "Enter a valid from date."
        filter.toDate.isNotBlank() && runCatching { LocalDate.parse(filter.toDate) }.isFailure -> "Enter a valid to date."
        filter.fromDate.isNotBlank() && filter.toDate.isNotBlank() && filter.fromDate>filter.toDate -> "From date must be before to date."
        else -> null
    }
    val classes = remember(project) { vm.repository.classifications(project).catch { vm.storageFailure(it) } }.readLedger(vm) ?: return StartupScreen("Loading saved records")
    val totals by key(project,expenseFilter,error) {
        remember(project,expenseFilter,error) { if(error==null) vm.repository.filteredExpenseSummary(project,expenseFilter).catch { vm.storageFailure(it) } else flowOf(null) }.collectAsStateWithLifecycle<ExpenseSummary?>(null)
    }
    val billOnly=filter.status !in setOf("ALL","PAYMENT","OVERDUE","SETTLED") || filter.classificationId!=null || filter.vendorId!=null || filter.missingVendor || filter.materialName!=null || filter.costNature!=null
    val selectedType=if(filter.status=="PAYMENT") "PAYMENT" else if(filter.status=="OVERDUE") "INSTALMENT" else if(billOnly) "EXPENSE" else type
    val date = rememberLedgerDate()
    val firstPage = remember(project, filter, error, selectedType, date) {
        if (error == null) vm.repository.journal(project, expenseFilter, selectedType,
            includeAudits = selectedType == "AUDIT" || selectedType == "ALL" && filter.status == "ALL",
            settlementStatus = filter.status.takeIf { it in setOf("OVERDUE", "SETTLED") } ?: "ALL") else flowOf(emptyList())
    }.readLedger(vm)
    val revision = remember(vm.repository) { vm.repository.journalRevision() }.readLedger(vm)
    var previousPages by remember(project, filter, selectedType, date, revision) { mutableStateOf(emptyList<JournalRow>()) }
    var before by remember(project, filter, selectedType, date, revision) { mutableStateOf<ActivityCursor?>(null) }
    LaunchedEffect(firstPage) { previousPages = emptyList(); before = null }
    val page = if (before == null) firstPage else remember(project, filter, error, selectedType, before, date) {
        vm.repository.journal(project, expenseFilter, selectedType,
            includeAudits = selectedType == "AUDIT" || selectedType == "ALL" && filter.status == "ALL",
            settlementStatus = filter.status.takeIf { it in setOf("OVERDUE", "SETTLED") } ?: "ALL", before = before)
    }.readLedger(vm)
    val loading = page == null
    val journalRows = (previousPages + page.orEmpty().take(75)).distinctBy { it.event.type to it.event.id }
    val rows = journalRows.map { it.event }
    var selectedAudit by remember { mutableStateOf<AuditInfo?>(null) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=16.dp),contentPadding=PaddingValues(vertical=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item { PageHeading("Activity","Find bills, payments and plans. Open any record to inspect or edit it.") }
        item { OutlinedButton({ actions.exportCsv(project) }) { Text("Export all CSV") } }
        item { Field("Search supplier, item, category, invoice or room...",filter.query,{ setFilter(filter.copy(query=it)) },showLabel=false) }
        item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Choice("Filter activities",selectedType,listOf("ALL" to "All activity","EXPENSE" to "Bills and purchases","PAYMENT" to "Payments","INSTALMENT" to "Planned payments","COMMITMENT" to "Contracts","NOTE" to "Work log","ATTACHMENT" to "Files","AUDIT" to "Change logs"),{ type=it;setFilter(filter.copy(status="ALL")) },allowNone = false,modifier = Modifier.weight(1f),showLabel=false)
            Choice("Filter settlement status",filter.status.takeUnless{it in setOf("PAYMENT","BILLS")} ?: "ALL",listOf("ALL" to "Any status","OUTSTANDING" to "Unpaid bills","UNCLASSIFIED" to "Unclassified bills","OVERDUE" to "Overdue planned payments","SETTLED" to "Settled bills / plans"),{ setFilter(filter.copy(status=it)) },allowNone = false,modifier = Modifier.weight(1f),showLabel=false)
        } }
        item { Choice("Cost head",filter.classificationId,classes.filter { it.kind=="COST_HEAD" }.map { it.id to it.name },{ setFilter(filter.copy(classificationId=it.ifBlank { null })) },modifier=Modifier.fillMaxWidth(.7f),showLabel=false,emptyLabel="All cost heads") }
        item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.Bottom) {
            Box(Modifier.weight(1f)) { DateField("From",filter.fromDate,{ setFilter(filter.copy(fromDate=it)) },compact=true) }
            Box(Modifier.weight(1f)) { DateField("To",filter.toDate,{ setFilter(filter.copy(toDate=it)) },compact=true) }
            OutlinedButton({ setFilter(ExpenseFilter());type="ALL" }) { Text("Clear filters") }
        } }
        error?.let { item { Text(it,color=MaterialTheme.colorScheme.error) } }
        item { WebCard(color=WebColors.soft) {
            Text(if (loading) "Loading activity" else "Showing ${rows.size} records",fontWeight=FontWeight.SemiBold)
            if(selectedType in setOf("ALL","EXPENSE")) {
                val verifiedTotals = totals
                if (error == null && verifiedTotals != null) {
                    Text("Bill cost ${Money.format(verifiedTotals.recordedPaise)}",color=WebColors.muted,style=MaterialTheme.typography.bodyMedium)
                    Text("Bill unpaid ${Money.format(verifiedTotals.outstandingPaise)}",color=WebColors.muted,style=MaterialTheme.typography.bodyMedium)
                } else if (error == null) Text("Loading bill totals", color=WebColors.muted)
            }
        } }
        if(!loading && rows.isEmpty()) item { EmptyPanel("No matching activity","Try different dates or a different filter.") }
        items(rows,key={ "${it.type}-${it.id}" }) { row -> EventRow(row) {
            if(row.type=="AUDIT") selectedAudit=journalRows.firstOrNull { it.event.id==row.id && it.event.type=="AUDIT" }?.audit
            else detail(row.id,if(row.type=="ATTACHMENT") "DOCUMENT" else row.type)
        } }
        if (loading && previousPages.isNotEmpty()) item { Text("Loading more activity") }
        if (page.orEmpty().size > 75) item { OutlinedButton({
            val last = page.orEmpty()[74].event
            previousPages = journalRows
            before = ActivityCursor(last.recordedAt, last.type, last.id)
        }, Modifier.fillMaxWidth()) { Text("Show 75 more") } }
    }
    selectedAudit?.let { audit -> AlertDialog(onDismissRequest={selectedAudit=null},title={Text("Change log")},text={
        androidx.compose.foundation.rememberScrollState().let { scroll -> Column(Modifier.heightIn(max=450.dp).then(Modifier.verticalScroll(scroll)),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            AuditChanges(audit)
        } }
    },confirmButton={TextButton({selectedAudit=null}){Text("Close")}}) }
}

@Composable
internal fun WebExploreScreen(vm:LedgerViewModel,activity:(ExpenseFilter)->Unit,detail:(String,String)->Unit,navigate:(String)->Unit,initialTab:String="cost",enableReminders:(()->Unit)?=null) {
    val state by vm.state.collectAsStateWithLifecycle()
    val project=state.projectId
    var tab by rememberSaveable(project,initialTab) { mutableStateOf(initialTab) }
    var dimension by rememberSaveable(project) { mutableStateOf("COST") }
    var parent by rememberSaveable(project) { mutableStateOf<String?>(null) }
    var levelQuery by rememberSaveable(project,tab,parent) { mutableStateOf("") }
    @Composable fun Header() {
        Column(Modifier.padding(horizontal=16.dp,vertical=24.dp),verticalArrangement=Arrangement.spacedBy(15.dp)) {
            PageHeading("Cost explorer","A clear path from project totals to the original invoice.")
            OutlinedButton({ tab="calendar" }) { Text("Payment calendar") }
            WebTabs(listOf("cost" to "Cost Heads","nature" to "Cost Nature","hierarchy" to "Stages & materials","work" to "Work stage","place" to "Location","vendor" to "Supplier","material" to "Material","calendar" to "Calendar"),tab) {
                tab=it;parent=null;dimension=when(it){"cost"->"COST";"place"->"PLACE";"vendor"->"SUPPLIER";"material"->"MATERIAL";else->"WORK"}
            }
        }
    }
    if(tab=="calendar") {
        CalendarScreen(vm,{navigate("entry-form")},detail,{ Header() },enableReminders)
        return
    }
    val rows = remember(project,parent,dimension) { vm.repository.insights(project,parent,dimension) }.readLedger(vm) ?: return StartupScreen("Loading saved records")
    val classes = remember(project) { vm.repository.classifications(project) }.readLedger(vm) ?: return StartupScreen("Loading saved records")
    val monthly = remember(project) { vm.repository.monthlyCash(project) }.readLedger(vm) ?: return StartupScreen("Loading saved records")
    val nature = remember(project) { vm.repository.costNature(project) }.readLedger(vm) ?: return StartupScreen("Loading saved records")
    val comparison=insightComparison(rows)
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item { Header() }
            if(tab=="nature") item { WebCard {
                Text("Cost by nature",style=MaterialTheme.typography.titleLarge)
                if(nature.isEmpty()) EmptyPanel("Nothing recorded yet","Add a purchase or bill to see the cost breakdown.")
                nature.forEachIndexed { i,row -> CostBar(row.nature.ifBlank { "Unclassified / Legacy" },row.amountPaise,Money.sum(nature.map{it.amountPaise}),i) { activity(ExpenseFilter(costNature=row.nature.ifBlank {"Unclassified / Legacy"},postedOnly=true)) } }
            } }
            else {
                if(tab !in listOf("cost","hierarchy") && comparison.totalPaise>0) item { WebCard { Eyebrow("RECORDED BILL COSTS");InsightDonut(comparison) { row ->
                    activity(when(dimension){"SUPPLIER"->ExpenseFilter(vendorId=row.id,missingVendor=row.id==null,postedOnly=true);"MATERIAL"->ExpenseFilter(materialName=row.name,postedOnly=true);else->ExpenseFilter(classificationId=row.id,postedOnly=true)})
                } } }
                item { WebCard {
                    Text(if(tab=="hierarchy") "Construction cost explorer" else if(tab=="cost") "Cost head explorer" else "Category ranking",style=MaterialTheme.typography.titleLarge)
                    Text(if(tab=="cost") "Cost Head → Subhead → Item / Service → Original Bill" else if(tab=="hierarchy") "Select a stage to see its substages, materials, and original bills." else "Recorded invoices only. Payments, advances and future plans are kept separate.",color=WebColors.muted,style=MaterialTheme.typography.bodyMedium)
                    if(tab in listOf("cost","hierarchy")) OutlinedButton({navigate("categories")}) {Text("+ Categories")}
                    if(tab in listOf("cost","hierarchy")) {
                        OutlinedButton({parent=classes.firstOrNull { it.id==parent }?.parentId}) { Text(classes.firstOrNull { it.id==parent }?.name ?: "All cost heads") }
                        WebCard(color=WebColors.soft) {
                            Text(Money.format(comparison.totalPaise),fontSize=20.sp,fontWeight=FontWeight.Bold)
                            Text("Recorded bill cost · Payments and plans excluded",color=WebColors.muted,style=MaterialTheme.typography.bodyMedium)
                        }
                        Field("Filter this level",levelQuery,{levelQuery=it},modifier=Modifier.fillMaxWidth(.55f),showLabel=false)
                    } else Text(Money.format(comparison.totalPaise),fontSize=28.sp,fontWeight=FontWeight.Bold)
                    if(rows.none {it.amountPaise>0}) EmptyPanel("No recorded costs","Add a bill or classify an existing bill.")
                    rows.filter {(it.amountPaise>0 || tab=="cost") && it.name.contains(levelQuery,true)}.sortedByDescending { it.amountPaise }.forEachIndexed { i,row ->
                        CostBar(row.name,row.amountPaise,comparison.totalPaise,i) {
                            if(row.id!=null && row.kind!="DIRECT_ALLOCATION" && dimension !in listOf("SUPPLIER","MATERIAL") && classes.any { it.parentId==row.id }) parent=row.id
                            else activity(when(dimension){"SUPPLIER"->ExpenseFilter(vendorId=row.id,missingVendor=row.id==null,postedOnly=true);"MATERIAL"->ExpenseFilter(materialName=row.name,postedOnly=true);else->if(row.id==null) ExpenseFilter(status=when(dimension){"WORK"->"UNCLASSIFIED_WORK";"PLACE"->"UNCLASSIFIED_PLACE";else->"UNCLASSIFIED"}) else ExpenseFilter(classificationId=row.id,directClassification=row.kind=="DIRECT_ALLOCATION")})
                        }
                    }
                } }
                if(tab in listOf("cost","hierarchy")) item { OutlinedButton({ navigate("payments") },Modifier.fillMaxWidth()) { Text("Planned payments and agreements") } }
                if(tab !in listOf("cost","hierarchy")) item { WebCard { MonthlyCashChart(monthly) } }
            }
        }
}
