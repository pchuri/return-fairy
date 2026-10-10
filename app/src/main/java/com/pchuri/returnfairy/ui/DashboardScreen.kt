package com.pchuri.returnfairy.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pchuri.returnfairy.R
import com.pchuri.returnfairy.core.AccountStatus
import com.pchuri.returnfairy.core.BookStatus
import com.pchuri.returnfairy.core.LibraryBook
import com.pchuri.returnfairy.core.Reservation
import com.pchuri.returnfairy.core.Snapshot
import com.pchuri.returnfairy.core.SplibErrorKind
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Days left at or under which a loan is shown as "soon". Same default as `songpa --due-soon`. */
private const val DUE_SOON_DAYS = 3

private val TAB_ROW_HEIGHT = 48.dp

private val FETCHED_FORMAT = DateTimeFormatter.ofPattern("MM/dd HH:mm")
private val SHORT_DATE = DateTimeFormatter.ofPattern("MM.dd")
private val FULL_DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd")

/** "세 글자 한글 이름은 성을 뗀다" (홍길동 → 길동), only for the narrow tab pills. */
internal fun shortName(label: String): String =
    if (label.length == 3 && label.all { it in '가'..'힣' }) label.substring(1) else label

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(viewModel: AppViewModel, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.dashboard.collectAsStateWithLifecycle()
    val colors = dashColors()

    Box(modifier = modifier.fillMaxSize().background(colors.bg)) {
        when {
            !state.hasAccounts -> NoAccounts(onOpenSettings)
            state.snapshot == null -> Centered(stringResource(R.string.dashboard_first_lookup), colors.sub)
            else -> PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                Dashboard(state.snapshot!!, state.refreshing, state.stale)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Dashboard(snapshot: Snapshot, refreshing: Boolean, stale: Boolean) {
    val colors = dashColors()
    val today = LocalDate.now()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val accounts = snapshot.accounts
    // Stop a tapped card just below the pinned tab row instead of underneath it.
    val tabRowHeight = with(LocalDensity.current) { TAB_ROW_HEIGHT.roundToPx() }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 40.dp),
    ) {
        item { Header(snapshot, refreshing, stale) }
        stickyHeader {
            LazyRow(
                modifier = Modifier.fillMaxWidth().height(TAB_ROW_HEIGHT).background(colors.bg.copy(alpha = 0.94f)),
                verticalAlignment = Alignment.CenterVertically,
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(accounts) { index, account ->
                    TabPill(account) { scope.launch { listState.animateScrollToItem(index + 2, -tabRowHeight) } }
                }
            }
        }
        itemsIndexed(accounts, key = { index, account -> account.userId.ifEmpty { "#$index" } }) { _, account ->
            AccountCard(account, today)
        }
    }
}

@Composable
private fun Header(snapshot: Snapshot, refreshing: Boolean, stale: Boolean) {
    val colors = dashColors()
    val accounts = snapshot.accounts
    val total = accounts.sumOf { it.books.size }
    val inter = accounts.sumOf { it.interlibraryCount }
    val pickups = accounts.sumOf { it.pickupBookCount + it.readyReservationCount }
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, start = 12.dp, end = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.dashboard_title), color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(4.dp))
        Text(
            text = when {
                refreshing -> stringResource(R.string.dashboard_refreshing)
                stale -> stringResource(R.string.dashboard_stale, snapshot.fetchedAt.format(FETCHED_FORMAT))
                else -> stringResource(R.string.dashboard_fetched_at, snapshot.fetchedAt.format(FETCHED_FORMAT))
            },
            color = colors.yellow,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(colors.chip).padding(horizontal = 10.dp, vertical = 3.dp),
        )
        Spacer(Modifier.height(4.dp))
        val summary = stringResource(R.string.dashboard_summary, accounts.size, total, inter) +
            if (pickups > 0) stringResource(R.string.dashboard_summary_pickup, pickups) else ""
        Text(summary, color = colors.sub, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun TabPill(account: AccountStatus, onClick: () -> Unit) {
    val colors = dashColors()
    Row(
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(colors.chip).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(shortName(account.label), color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "${account.books.size}", color = colors.text, fontSize = 11.sp,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(colors.chipCount).padding(horizontal = 6.dp, vertical = 1.dp),
        )
        Text("(${account.interlibraryCount})", color = colors.red, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountCard(account: AccountStatus, today: LocalDate) {
    val colors = dashColors()
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp).fillMaxWidth().clip(shape)
            .background(colors.card).border(1.dp, colors.border, shape).padding(horizontal = 14.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("👤 ${account.label}", color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            Text(
                stringResource(R.string.card_books, account.books.size), color = colors.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(colors.badgeBg).padding(horizontal = 9.dp, vertical = 3.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatText(stringResource(R.string.stat_loans, account.books.size), colors.text)
            StatText(stringResource(R.string.stat_interlibrary, account.interlibraryCount), colors.green)
            if (account.pickupBookCount > 0) StatText(stringResource(R.string.stat_pickup, account.pickupBookCount), colors.yellow)
            if (account.readyReservationCount > 0) StatText(stringResource(R.string.stat_reservation_ready, account.readyReservationCount), colors.yellow)
        }

        val error = account.error
        if (account.isStale) {
            Text(
                stringResource(R.string.card_stale, account.lastSuccessfulAt!!.format(FETCHED_FORMAT)),
                color = colors.yellow, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp),
            )
        }
        when {
            error != null && !account.isStale -> Text(
                stringResource(R.string.card_error, stringResource(errorText(error))),
                color = colors.red, fontSize = 13.sp,
                modifier = Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.tintRed)
                    .border(1.dp, colors.tintRedBorder, RoundedCornerShape(10.dp)).padding(10.dp),
            )
            account.books.isEmpty() -> Text(
                stringResource(R.string.card_empty), color = colors.sub, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
            )
            else -> BookTable(account.sortedBooks(today), today)
        }

        if ((error == null || account.isStale) && account.reservations.isNotEmpty()) {
            Text(
                stringResource(R.string.reservations_title, account.reservations.size),
                color = colors.sub, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp),
            )
            account.reservations.forEach { ReservationRow(it) }
        }
    }
}

@Composable
private fun StatText(text: String, color: Color) {
    Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
}

private fun errorText(kind: SplibErrorKind) = when (kind) {
    SplibErrorKind.LOGIN_FAILED -> R.string.error_login
    SplibErrorKind.SESSION_EXPIRED -> R.string.error_session
    SplibErrorKind.NETWORK -> R.string.error_network
    SplibErrorKind.TIMEOUT -> R.string.error_timeout
    SplibErrorKind.SITE_CHANGED -> R.string.error_site_changed
}

private val NUM_WIDTH = 20.dp
private val LIB_WIDTH = 64.dp
private val DUE_WIDTH = 76.dp

@Composable
private fun BookTable(books: List<LibraryBook>, today: LocalDate) {
    val colors = dashColors()
    Spacer(Modifier.height(10.dp))
    Row(modifier = Modifier.padding(vertical = 6.dp)) {
        HeaderCell("#", Modifier.width(NUM_WIDTH))
        HeaderCell(stringResource(R.string.col_library), Modifier.width(LIB_WIDTH))
        HeaderCell(stringResource(R.string.col_title), Modifier.weight(1f))
        HeaderCell(stringResource(R.string.col_status), Modifier.width(DUE_WIDTH), TextAlign.End)
    }
    HorizontalDivider(color = colors.border)
    books.forEachIndexed { index, book ->
        BookRow(index + 1, book, today)
        if (index < books.lastIndex) HorizontalDivider(color = colors.border)
    }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier, align: TextAlign = TextAlign.Start) {
    Text(text, color = dashColors().sub, fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = align, modifier = modifier)
}

@Composable
private fun BookRow(number: Int, book: LibraryBook, today: LocalDate) {
    val colors = dashColors()
    val greenLibrary = book.isInterlibrary || book.status != BookStatus.LOANED
    val libSub = when {
        book.status == BookStatus.READY_FOR_PICKUP -> R.string.lib_sub_pickup
        book.status == BookStatus.IN_TRANSIT -> R.string.lib_sub_transit
        book.isInterlibrary -> R.string.lib_sub_interlibrary
        else -> R.string.lib_sub_loan
    }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Text("$number", color = colors.sub, fontSize = 13.sp, modifier = Modifier.width(NUM_WIDTH))
        Column(modifier = Modifier.width(LIB_WIDTH)) {
            Text(
                book.library, color = if (greenLibrary) colors.green else colors.text, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(stringResource(libSub), color = colors.sub, fontSize = 11.sp, maxLines = 1, softWrap = false)
        }
        Column(modifier = Modifier.weight(1f).padding(end = 4.dp)) {
            Text(book.title, color = colors.text, fontSize = 13.sp)
            BookLocation(book)
        }
        Column(modifier = Modifier.width(DUE_WIDTH), horizontalAlignment = Alignment.End) {
            DueCell(book, today)
        }
    }
}

@Composable
private fun BookLocation(book: LibraryBook) {
    val colors = dashColors()
    val holding = book.providingLibrary
    when (book.status) {
        BookStatus.READY_FOR_PICKUP -> SmallText(buildAnnotatedString {
            append(stringResource(R.string.loc_arrived_prefix))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(book.library) }
            if (holding.isNotEmpty()) append(stringResource(R.string.loc_holding_paren, holding))
        })
        BookStatus.IN_TRANSIT -> SmallText(buildAnnotatedString {
            append("📍")
            if (holding.isNotEmpty()) append("$holding ➔ ")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(book.library) }
            append(" (${book.transitStatus.orEmpty()})")
        })
        BookStatus.LOANED -> if (book.isInterlibrary) {
            Row(modifier = Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.tag_interlibrary), color = colors.green, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(colors.tintGreen).padding(horizontal = 5.dp, vertical = 1.dp),
                )
                if (holding.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.loc_holding, holding), color = colors.sub, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun SmallText(text: androidx.compose.ui.text.AnnotatedString) {
    Text(text, color = dashColors().sub, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
}

@Composable
private fun DueCell(book: LibraryBook, today: LocalDate) {
    val colors = dashColors()
    when (book.status) {
        BookStatus.READY_FOR_PICKUP -> Pill(stringResource(R.string.badge_pickup), colors.yellowBg, Color.Black)
        BookStatus.IN_TRANSIT -> Pill(stringResource(R.string.badge_transit), colors.tintSky, colors.sky)
        BookStatus.LOANED -> {
            val left = book.daysLeft(today)
            if (left == null) {
                Text(book.rawDue.ifBlank { stringResource(R.string.due_unknown) }, color = colors.text, fontSize = 13.sp, textAlign = TextAlign.End)
                return
            }
            Text(book.dueDate!!.format(SHORT_DATE), color = colors.text, fontSize = 13.sp)
            val (label, color) = when {
                left < 0 -> stringResource(R.string.badge_overdue, -left) to colors.red
                left == 0L -> stringResource(R.string.badge_today) to colors.red
                left <= DUE_SOON_DAYS -> stringResource(R.string.badge_dday, left) to colors.orange
                else -> stringResource(R.string.badge_dday, left) to colors.green
            }
            Text(label, color = color, fontSize = 13.sp, fontWeight = if (left <= DUE_SOON_DAYS) FontWeight.ExtraBold else FontWeight.Bold)
        }
    }
}

@Composable
private fun Pill(text: String, background: Color, color: Color, size: TextUnit = 11.sp) {
    Text(
        text, color = color, fontSize = size, fontWeight = FontWeight.ExtraBold,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(background).padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
private fun ReservationRow(reservation: Reservation) {
    val colors = dashColors()
    val deadline = reservation.pickupDeadline
    if (deadline != null) {
        Text(
            stringResource(R.string.reservation_ready, reservation.title, reservation.library, deadline.format(FULL_DATE)),
            color = colors.yellow, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(vertical = 4.dp),
        )
    } else {
        Text(
            stringResource(
                R.string.reservation_waiting, reservation.title, reservation.library,
                if (reservation.rank > 0) reservation.rank.toString() else "?",
            ),
            color = colors.text, fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp),
        )
    }
}

@Composable
private fun NoAccounts(onOpenSettings: () -> Unit) {
    val colors = dashColors()
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("📚", fontSize = 40.sp)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.dashboard_no_accounts_title), color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.dashboard_no_accounts_body), color = colors.sub, fontSize = 14.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onOpenSettings) { Text(stringResource(R.string.dashboard_open_settings)) }
    }
}

@Composable
private fun Centered(text: String, color: Color) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = color, fontSize = 14.sp)
    }
}
