package dast

import scala.jdk.CollectionConverters.*
import scala.util.Try

import com.microsoft.playwright.Page
import com.microsoft.playwright.options.LoadState

import crawler.BrowserResource

/** Browser-driven login to mint a session for an authenticated scan.
  *
  * This is the one form the scanner may submit (instruction.md §5
  * authenticated-scan carve-out): an explicitly-configured login, operator
  * credentials, gated host. Field detection is deterministic (password input +
  * the nearest non-password text input + the submit control); the model is not
  * consulted. Returns the resulting cookies as a `Cookie` header value.
  *
  * Browser work, so it runs on the pinned thread via `pool.submit`. Not unit
  * tested (needs a live login page); exercised live. `Left` is a reason it
  * could not log in; `Right` is the captured cookie header.
  */
object LoginOp:

  def login(
      resource: BrowserResource,
      loginUrl: String,
      username: String,
      password: String,
  ): Either[String, String] = resource.withPage(loginUrl) { (page, _) =>
    Option(page.querySelector("input[type=password]")) match
      case None => Left("no password field on the login page")
      case Some(pwd) => usernameField(page) match
          case None => Left("no username field on the login page")
          case Some(user) =>
            user.fill(username)
            pwd.fill(password)
            // Submit: click the submit control if present, else press Enter in
            // the password field. The browser performs the submission; the
            // model never authors it (§0.2).
            Option(page.querySelector(
              "button[type=submit], input[type=submit], form button",
            )) match
              case Some(btn) => Try(btn.click())
              case None => Try(pwd.press("Enter"))
            // A login normally navigates; tolerate XHR logins that do not.
            Try(page.waitForLoadState(LoadState.LOAD))
            val cookies = page.context().cookies().asScala.toSeq
            if cookies.isEmpty then Left("login produced no cookies")
            else Right(cookies.map(c => s"${c.name}=${c.value}").mkString("; "))
  }

  /** First plausible username input: an email/text field, else a conventionally
    * named one, else any visible non-password/hidden/control input.
    */
  private def usernameField(page: Page) = Option(
    page.querySelector("input[type=email]"),
  ).orElse(Option(page.querySelector("input[type=text]"))).orElse(Option(
    page.querySelector("input[name='username'], input[name='user'], input[name='email'], input[name='login']"),
  )).orElse(Option(
    page.querySelector("input:not([type='password']):not([type='hidden']):not([type='submit']):not([type='checkbox']):not([type='radio'])"),
  ))
