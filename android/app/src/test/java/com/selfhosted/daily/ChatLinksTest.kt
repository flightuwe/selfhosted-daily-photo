package com.selfhosted.daily

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatLinksTest {
    @Test fun `removes YouTube si parameter while preserving video id`() = assertEquals(
        "https://music.youtube.com/watch?v=cc90XENe2Bw",
        cleanChatLinks("https://music.youtube.com/watch?v=cc90XENe2Bw&si=bOfP6V75tIF6lKGK")
    )

    @Test fun `removes si parameter wherever it appears in a link`() = assertEquals(
        "Siehe https://www.youtube.com/watch?v=oT-0HHd-9Fw&t=15#details.",
        cleanChatLinks("Siehe https://www.youtube.com/watch?v=oT-0HHd-9Fw&si=tracking&t=15#details.")
    )

    @Test fun `keeps non tracking parameters and ordinary text`() = assertEquals(
        "Mehr unter https://example.org/a?foo=bar.",
        cleanChatLinks("Mehr unter https://example.org/a?foo=bar.")
    )
}
