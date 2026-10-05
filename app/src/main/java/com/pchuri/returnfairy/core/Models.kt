package com.pchuri.returnfairy.core

object SplibConfig {
    const val BASE = "https://splib.or.kr"
    const val LOGIN_URL = "$BASE/intro/program/memberLoginProc.do"
    const val INDEX_URL = "$BASE/intro/index.do"
    const val LOAN_URL = "$BASE/intro/program/mypage/loanStatusList.do"
    const val INTERLIBRARY_LOAN_URL = "$BASE/intro/program/mypage/dooraeLillStatusList.do"
    const val LOAN_PATH = "/intro/program/mypage/loanStatusList.do"
    const val INTERLIBRARY_PATH = "/intro/program/mypage/dooraeLillStatusList.do"
}

object DooraeStatus {
    const val SENDING = "발송"
    const val OBTAINED = "입수"
    const val RETURNING = "복귀중"
    const val REQUESTED_RAW = "요청중신청취소"
    const val REQUESTED = "요청중"
}

data class Book(
    val title: String,
    val dueDate: String,
    val isInterlibrary: Boolean,
    val library: String,
    val loanDate: String = "",
)

data class UserInfo(
    val name: String,
    val books: List<Book>,
    val totalBorrowedBooks: Int,
    val activeInterlibraryLoans: Int,
    val pendingInterlibraryPickups: Int,
    val error: SplibErrorKind? = null,
)

enum class SplibErrorKind { LOGIN_FAILED, SESSION_EXPIRED, NETWORK, TIMEOUT }

class AuthException(val kind: SplibErrorKind, message: String) : Exception(message)
class FetchException(message: String, cause: Throwable? = null) : Exception(message, cause)
