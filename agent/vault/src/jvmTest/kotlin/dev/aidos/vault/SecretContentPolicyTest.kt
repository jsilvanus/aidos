package dev.aidos.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** RFC-0035 "Secrets found in content": one test per case of the rule. */
class SecretContentPolicyTest {

    private val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"
    private val ghToken = "ghp_" + "A1".repeat(18)
    private val envValue = "abcdEFGH1234ijklMNOP5678"
    private val pem = "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASC\n-----END PRIVATE KEY-----"

    private fun policy(redactor: Redactor = Redactor()) = SecretContentPolicy(redactor)

    @Test
    fun cleanTextIsUnchanged() {
        assertEquals(
            SecretScreening.Clean,
            policy().screen("fun main() {\n    println(\"hello\")\n}"),
        )
    }

    @Test
    fun sourceFileWithOneTokenKeepsEveryOtherLineAndNoPartOfTheToken() {
        val source = "class Client {\n    val token = \"$jwt\"\n    fun connect() = Unit\n}"
        val screened = policy().screen(source)
        assertIs<SecretScreening.Redacted>(screened)
        assertTrue(screened.text.contains("class Client {"))
        assertTrue(screened.text.contains("fun connect() = Unit"))
        assertFalse(screened.text.contains("eyJhbGci"))
        assertFalse(screened.text.contains("dozjgNryP4J3"))
        assertEquals(listOf("pattern:jwt"), screened.kinds)
        assertEquals(1, screened.count)
    }

    @Test
    fun threeDetectionsAreRedactedAndCountedInTheNote() {
        val text = listOf(
            "fun one() = call(\"$ghToken\")",
            "fun two() = call(\"$ghToken\")",
            "fun three() = call(\"$ghToken\")",
        ).joinToString("\n")
        val screened = policy().screen(text)
        assertIs<SecretScreening.Redacted>(screened)
        assertEquals(3, screened.count)
        assertFalse(screened.text.contains("ghp_"))
        assertTrue(
            screened.text.endsWith("[3 credential-like value(s) in this result were replaced with redaction markers.]"),
            screened.text,
        )
    }

    @Test
    fun fourDetectionsWithholdTheWholeText() {
        val text = (1..4).joinToString("\n") { "fun f$it() = call(\"$ghToken\")" }
        assertIs<SecretScreening.Withheld>(policy().screen(text))
    }

    @Test
    fun pemPrivateKeyIsWithheldEvenAlone() {
        val screened = policy().screen("here is a key:\n$pem\nand more text")
        assertIs<SecretScreening.Withheld>(screened)
        assertTrue("pattern:pem-private-key" in screened.kinds)
    }

    @Test
    fun sixLineEnvFileWithOneDetectedValueIsWithheld() {
        val env = listOf(
            "APP_NAME=demo",
            "PORT=8080",
            "DEBUG=false",
            "LOG_LEVEL=info",
            "DB_HOST=localhost",
            "API_TOKEN=$envValue",
        ).joinToString("\n")
        assertIs<SecretScreening.Withheld>(policy().screen(env))
    }

    @Test
    fun twoLineSnippetWithOneAssignmentIsRedactedNotWithheld() {
        val snippet = "val x = 1\nAPI_TOKEN=$envValue"
        val screened = policy().screen(snippet)
        assertIs<SecretScreening.Redacted>(screened)
        assertFalse(screened.text.contains("abcdEFGH"))
        assertTrue(screened.text.startsWith("val x = 1\n"))
    }

    @Test
    fun registeredVaultValueIsReplacedAndNotCountedTowardFour() {
        val redactor = Redactor()
        redactor.register(id = "db", name = "DB", value = "s3cr3t-value-xyz".toCharArray())
        val text = listOf(
            "fun one() = call(\"$ghToken\")",
            "fun two() = call(\"$ghToken\")",
            "fun three() = call(\"$ghToken\")",
            "fun four() = call(\"s3cr3t-value-xyz\")",
        ).joinToString("\n")
        val screened = policy(redactor).screen(text)
        assertIs<SecretScreening.Redacted>(screened)
        assertEquals(4, screened.count)
        assertTrue("known_value" in screened.kinds)
        assertFalse(screened.text.contains("s3cr3t-value-xyz"))
        assertFalse(screened.text.contains("ghp_"))
    }
}
