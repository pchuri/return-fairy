package com.pchuri.returnfairy.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ShortNameTest {
    @Test
    fun onlyThreeLetterKoreanNamesLoseTheFamilyName() {
        assertEquals("길동", shortName("홍길동"))
        assertEquals("엄마", shortName("엄마"))
        assertEquals("Tom", shortName("Tom"))
        assertEquals("남궁민수", shortName("남궁민수"))
    }
}
