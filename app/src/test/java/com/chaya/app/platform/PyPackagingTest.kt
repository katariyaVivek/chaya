package com.chaya.app.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The parts of Python's packaging rules EngineUpdater relies on to read PyPI's answers. */
class PyPackagingTest {

    @Test
    fun `versions are ordered by number, not as text`() {
        assertTrue(PyVersion.compare("2026.8.19", "2026.10.1") < 0)
        assertTrue(PyVersion.compare("1.32.16", "1.32.17") < 0)
        assertTrue(PyVersion.compare("1.32.16", "1.4.0") > 0)
        assertTrue(PyVersion.same("2026.08.19", "2026.8.19"))
        assertTrue(PyVersion.same("1.0", "1.0.0"))
    }

    @Test
    fun `pre-releases come before their release and post-releases after it`() {
        assertTrue(PyVersion.compare("1.0rc1", "1.0") < 0)
        assertTrue(PyVersion.compare("1.0a2", "1.0b1") < 0)
        assertTrue(PyVersion.compare("1.0.dev3", "1.0a1") < 0)
        assertTrue(PyVersion.compare("1.0", "1.0.post1") < 0)
        assertTrue(PyVersion.compare("2026.8.19", "2026.8.19.post1") < 0)
        assertTrue(PyVersion.isPrerelease("2026.9.1.dev20260901"))
        assertTrue(PyVersion.isPrerelease("0.9.0rc1"))
        assertFalse(PyVersion.isPrerelease("2026.8.19.post1"))
        assertFalse(PyVersion.isValid("not-a-version"))
    }

    @Test
    fun `requirement lines are read as PyPI lists them`() {
        val ejs = PyRequirement.parse("yt-dlp-ejs==0.8.0; extra == \"default\"")!!
        assertEquals("yt-dlp-ejs", ejs.name)
        assertEquals(listOf("==" to "0.8.0"), ejs.specifiers)
        assertTrue(ejs.isOptional)

        val requests = PyRequirement.parse("requests<3,>=2.32.2; extra == 'default'")!!
        assertEquals(listOf("<" to "3", ">=" to "2.32.2"), requests.specifiers)

        val old = PyRequirement.parse("Requests (>=2.11.0)")!!
        assertEquals("requests", old.name)
        assertNull(old.marker)
        assertFalse(old.isOptional)

        assertEquals("charset-normalizer", PyRequirement.parse("charset_normalizer>=2")!!.name)
        assertEquals("requests", PyRequirement.parse("requests[socks]; extra == \"extra\"")!!.name)
    }

    @Test
    fun `specifiers allow the versions they should`() {
        val requests = PyRequirement.parse("requests<3,>=2.32.2")!!
        assertTrue(requests.allows("2.32.5"))
        assertTrue(requests.allows("2.34.2"))
        assertFalse(requests.allows("2.31.0"))
        assertFalse(requests.allows("3.0.0"))
        assertFalse(requests.allows("3.0rc1"))

        val curl = PyRequirement.parse("curl-cffi!=0.6.*,!=0.7.*,<0.17,>=0.5.10")!!
        assertTrue(curl.allows("0.5.10"))
        assertFalse(curl.allows("0.6.2"))
        assertTrue(curl.allows("0.10.0"))
        assertFalse(curl.allows("0.17.0"))

        val compatible = PyRequirement.parse("x~=2.2")!!
        assertTrue(compatible.allows("2.9"))
        assertFalse(compatible.allows("3.0"))
        assertFalse(compatible.allows("2.1"))

        assertTrue(PyRequirement.parse("python>=3.10")!!.allows("3.13.0"))
        assertFalse(PyRequirement.parse("python>=3.14")!!.allows("3.13.0"))
        assertTrue(PyRequirement.parse("python!=3.0.*,>=2.7")!!.allows("3.13.0"))
    }

    @Test
    fun `markers decide whether a requirement applies to Python 3_13 on Android`() {
        val android = PyEnvironment.ANDROID
        fun applies(line: String, extras: Set<String> = emptySet()) = PyRequirement.parse(line)!!.appliesTo(android, extras)

        assertTrue(applies("requests>=2.11.0"))
        assertFalse(applies("certifi; extra == 'default'"))
        assertTrue(applies("certifi; extra == 'default'", setOf("default")))
        assertFalse(applies("brotlicffi; (implementation_name != 'cpython') and extra == 'default'", setOf("default")))
        assertTrue(applies("brotli; (implementation_name == 'cpython' and sys_platform != 'ios') and extra == 'default'", setOf("default")))
        assertFalse(applies("typing-extensions; (python_full_version < '3.11') and extra == 'curl-cffi'", setOf("curl-cffi")))
        assertTrue(applies("websockets==17.0.1; (python_full_version >= '3.11') and extra == 'pin'", setOf("pin")))
        assertFalse(applies("secretstorage; sys_platform == \"linux\" and extra == \"extra\"", setOf("extra")))
        assertFalse(applies("colorama; sys_platform == \"win32\""))
        assertTrue(applies("toml; python_version < \"3.11\" or os_name == \"posix\""))
        // A name it does not know: the requirement is treated as one that applies.
        assertTrue(applies("x; platform_release >= '5'"))
    }
}
