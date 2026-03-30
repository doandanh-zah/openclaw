package ai.openclaw.app

import java.net.URL
import java.net.URLDecoder
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAICodexOAuthTest {
  @Test
  fun createAuthorizationFlow_buildsExpectedOpenAIUrl() {
    val flow = OpenAICodexOAuth.createAuthorizationFlow()
    val url = URL(flow.authorizationUrl)

    assertEquals(OpenAICodexOAuth.AUTHORIZE_URL, "${url.protocol}://${url.host}${url.path}")
    assertEquals(OpenAICodexOAuth.CLIENT_ID, queryField(url.query, "client_id"))
    assertEquals(OpenAICodexOAuth.REDIRECT_URI, queryField(url.query, "redirect_uri"))
    assertEquals(OpenAICodexOAuth.SCOPE, queryField(url.query, "scope"))
    assertEquals(flow.state, queryField(url.query, "state"))
    assertEquals("code", queryField(url.query, "response_type"))
    assertEquals("S256", queryField(url.query, "code_challenge_method"))
    assertEquals("true", queryField(url.query, "id_token_add_organizations"))
    assertEquals("true", queryField(url.query, "codex_cli_simplified_flow"))
    assertEquals("pi", queryField(url.query, "originator"))
    assertTrue(queryField(url.query, "code_challenge").isNotBlank())
    assertNotEquals("", flow.verifier)
  }

  @Test
  fun parseAuthorizationInput_supportsRedirectUrlsAndManualCode() {
    val redirectInput =
      "${OpenAICodexOAuth.REDIRECT_URI}?code=auth-code-123&state=oauth-state-456"
    val parsedRedirect = OpenAICodexOAuth.parseAuthorizationInput(redirectInput)
    assertEquals("auth-code-123", parsedRedirect.code)
    assertEquals("oauth-state-456", parsedRedirect.state)

    val parsedManual = OpenAICodexOAuth.parseAuthorizationInput("auth-code-789#oauth-state-000")
    assertEquals("auth-code-789", parsedManual.code)
    assertEquals("oauth-state-000", parsedManual.state)
  }

  @Test
  fun extractAccountIdFromJwt_readsChatgptAccountIdClaim() {
    val jwt =
      listOf(
        encodeBase64Url("""{"alg":"none","typ":"JWT"}"""),
        encodeBase64Url("""{"https://api.openai.com/auth":{"chatgpt_account_id":"acct_123456"}}"""),
        "signature",
      ).joinToString(".")

    assertEquals("acct_123456", OpenAICodexOAuth.extractAccountIdFromJwt(jwt))
  }

  private fun queryField(query: String, key: String): String {
    val prefix = "$key="
    return query
      .split("&")
      .first { it.startsWith(prefix) }
      .substringAfter('=')
      .let { URLDecoder.decode(it, "UTF-8") }
  }

  private fun encodeBase64Url(value: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
}
