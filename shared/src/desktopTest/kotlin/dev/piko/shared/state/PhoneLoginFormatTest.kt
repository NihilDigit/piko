package dev.piko.shared.state

import dev.piko.data.auth.AppLanguage
import dev.piko.shared.data.SavedAccount
import dev.piko.shared.i18n.PikoLocale
import io.github.nihildigit.pikpak.UserProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhoneLoginFormatTest {
    @Test
    fun `splitInternationalPhone handles formatted numbers, contacts bidi marks, fullwidth chars, 00 prefix, and non-phone strings`() {
        assertEquals(
            ParsedPhone("86", "13800138000"),
            PhoneLoginFormat.splitInternationalPhone("+86 138-0013-8000"),
        )
        assertEquals(
            ParsedPhone("852", "91234567"),
            PhoneLoginFormat.splitInternationalPhone("\u202A+852 9123 4567\u202C"),
        )
        assertEquals(
            ParsedPhone("886", "912345678"),
            PhoneLoginFormat.splitInternationalPhone("＋８８６ ０９１２-３４５-６７８"),
        )
        assertEquals(
            ParsedPhone("81", "9012345678"),
            PhoneLoginFormat.splitInternationalPhone("0081-90-1234-5678"),
        )
        assertEquals(
            ParsedPhone("1", "4155552671"),
            PhoneLoginFormat.splitInternationalPhone("+1 (415) 555-2671"),
        )
        assertEquals(
            ParsedPhone("971", "501234567"),
            PhoneLoginFormat.splitInternationalPhone("+971 50 123 4567"),
        )
        assertEquals(
            ParsedPhone("7", "9161234567"),
            PhoneLoginFormat.splitInternationalPhone("+7 916 123-45-67"),
        )
        assertNull(PhoneLoginFormat.splitInternationalPhone("alice@gmail.com"))
        assertNull(PhoneLoginFormat.splitInternationalPhone("13800138000"))
        assertNull(PhoneLoginFormat.splitInternationalPhone("+86"))
    }

    @Test
    fun `formatE164 strips domestic trunk 0 only for trunk-zero dial codes`() {
        assertEquals("+886912345678", PhoneLoginFormat.formatE164("886", "0912-345-678"))
        assertEquals("+819012345678", PhoneLoginFormat.formatE164("81", "090 1234 5678"))
        assertEquals("+821012345678", PhoneLoginFormat.formatE164("82", "010-1234-5678"))
        assertEquals("+447700900123", PhoneLoginFormat.formatE164("44", "07700 900123"))
        // Italy preserves leading 0 on national numbers in E.164
        assertEquals("+390612345678", PhoneLoginFormat.formatE164("39", "06 1234 5678"))
    }

    @Test
    fun `isValidPhone validates country-specific and general E164 lengths`() {
        assertTrue(PhoneLoginFormat.isValidPhone("86", "13800138000"))
        assertFalse(PhoneLoginFormat.isValidPhone("86", "23800138000"))
        assertFalse(PhoneLoginFormat.isValidPhone("86", "1380013800"))
        assertFalse(PhoneLoginFormat.isValidPhone("86", "138001380001"))

        assertTrue(PhoneLoginFormat.isValidPhone("852", "91234567"))
        assertFalse(PhoneLoginFormat.isValidPhone("852", "9123456"))

        assertTrue(PhoneLoginFormat.isValidPhone("886", "0912345678"))
        assertTrue(PhoneLoginFormat.isValidPhone("886", "912345678"))
        assertFalse(PhoneLoginFormat.isValidPhone("886", "91234567"))

        assertTrue(PhoneLoginFormat.isValidPhone("1", "4155552671"))
        assertFalse(PhoneLoginFormat.isValidPhone("1", "415555267"))

        assertTrue(PhoneLoginFormat.isValidPhone("81", "09012345678"))
        assertFalse(PhoneLoginFormat.isValidPhone("81", "1234"))
        assertFalse(PhoneLoginFormat.isValidPhone("31", "12345678901234"))
    }

    @Test
    fun `defaultDialCode prioritizes region subtag then effective language`() {
        assertEquals("86", PhoneLoginFormat.defaultDialCode("zh-CN", AppLanguage.ZH_CN))
        assertEquals("852", PhoneLoginFormat.defaultDialCode("zh-HK", AppLanguage.ZH_HANT))
        assertEquals("853", PhoneLoginFormat.defaultDialCode("zh-MO", AppLanguage.ZH_HANT))
        assertEquals("886", PhoneLoginFormat.defaultDialCode("zh-TW", AppLanguage.ZH_HANT))
        assertEquals("81", PhoneLoginFormat.defaultDialCode("ja-JP", AppLanguage.JA))
        assertEquals("82", PhoneLoginFormat.defaultDialCode("ko-KR", AppLanguage.KO))
        assertEquals("65", PhoneLoginFormat.defaultDialCode("en-SG", AppLanguage.EN))
        assertEquals("44", PhoneLoginFormat.defaultDialCode("en-GB", AppLanguage.EN))
        assertEquals("1", PhoneLoginFormat.defaultDialCode("en-US", AppLanguage.EN))
        assertEquals("1", PhoneLoginFormat.defaultDialCode("en-CA", AppLanguage.EN))
        assertEquals("86", PhoneLoginFormat.defaultDialCode("fr-CH", AppLanguage.ZH_CN))
        assertEquals("886", PhoneLoginFormat.defaultDialCode("zh-Hant", AppLanguage.ZH_HANT))
        assertEquals("81", PhoneLoginFormat.defaultDialCode("ja", AppLanguage.JA))
        assertEquals("82", PhoneLoginFormat.defaultDialCode("ko", AppLanguage.KO))
        assertEquals("1", PhoneLoginFormat.defaultDialCode("fr-CH", AppLanguage.EN))
    }

    @Test
    fun `region filtering and custom dial code candidate work across locales`() {
        val previousSystem = PikoLocale.systemLanguageTag
        val previousLang = PikoLocale.effectiveLanguage
        try {
            PikoLocale.apply(AppLanguage.ZH_CN)
            assertEquals("86", PhoneRegions.filter("中国大陆").first().dialCode)
            assertEquals("81", PhoneRegions.filter("+81").first().dialCode)
            assertEquals("44", PhoneRegions.filter("UK").first().dialCode)
            assertEquals("44", PhoneRegions.filter("GB").first().dialCode)
            assertEquals("82", PhoneRegions.filter("hanguk").first().dialCode)
            assertEquals("852", PhoneRegions.filter("hong kong").first().dialCode)
            assertTrue(PhoneRegions.filter("31").none { it.dialCode == "31" })
            assertEquals("31", PhoneRegions.customDialCodeCandidate("+31"))
            assertEquals("31", PhoneRegions.customDialCodeCandidate("00 31"))
            assertEquals("31", PhoneRegions.customDialCodeCandidate("３１"))
            assertNull(PhoneRegions.customDialCodeCandidate("031"))
            assertNull(PhoneRegions.customDialCodeCandidate("12345"))
            assertNull(PhoneRegions.customDialCodeCandidate("netherlands"))
        } finally {
            PikoLocale.bindSystemLocale(previousSystem)
            PikoLocale.apply(previousLang)
        }
    }

    @Test
    fun `saved account maskedContact and displayName fall back to phone number when email is blank`() {
        val saved = SavedAccount(
            account = "pikpak:sub-phone-1",
            sub = "sub-phone-1",
            email = "",
            loginAccount = "+8613800138000",
            userProfile = UserProfile(
                sub = "sub-phone-1",
                email = "",
                phoneNumber = "+86 138****8000",
            ),
        )
        assertEquals("+86 138****8000", saved.maskedContact)
        assertEquals("+86 138****8000", saved.displayName)
    }
}
