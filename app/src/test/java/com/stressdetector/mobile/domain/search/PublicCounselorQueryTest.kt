package com.stressdetector.mobile.domain.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PublicCounselorQueryTest {
    @Test
    fun `automatic query contains topic only never the personal input`() {
        assertEquals("cara mengelola stres sehari-hari", PublicCounselorQuery.fromMessage("Saya Adrian, stres; sessionId=private"))
        assertEquals(
            "journaling harian untuk mengelola stres",
            PublicCounselorQuery.fromMessage("Saya mau journaling tentang konflik keluarga saya")
        )
        assertNull(PublicCounselorQuery.fromMessage("Nama saya Adrian dan tinggal di Yogyakarta"))
        assertNull(PublicCounselorQuery.fromMessage("unstressed unrelated topic"))
    }

    @Test
    fun `negated and ambiguous topics cannot select an unrelated search`() {
        assertEquals(
            "kebiasaan tidur sehat mengelola stres",
            PublicCounselorQuery.fromMessage("Jangan bahas journaling. Berikan sumber tentang insomnia.")
        )
        assertNull(PublicCounselorQuery.fromMessage("Berikan sumber tentang journaling dan insomnia"))
        assertNull(PublicCounselorQuery.fromMessage("Jangan bahas stres"))
    }
}
