package dast

import dast.FormParse.FormInfo

/** The deterministic safety floor for LLM-driven form submission
  * (instruction.md §5 navigation-action carve-out).
  *
  * GET submissions are read-semantic and allowed. A POST is allowed only when
  * the model classified it non-state-changing AND it survives the destructive-
  * pattern deny-list (action URL + field names + submit text). The model's
  * verdict is necessary, never sufficient: this deny-list is the hard floor, so
  * a model misjudgement still cannot fire a destructive action. PUT/DELETE/etc.
  * and file uploads are always refused. Pure and unit tested.
  */
object ActionGuard:

  /** Substrings that mark a likely state-changing or destructive action. */
  private val denyPatterns: Seq[String] = Seq(
    "delete",
    "remove",
    "destroy",
    "drop",
    "pay",
    "payment",
    "checkout",
    "purchase",
    "buy",
    "transfer",
    "withdraw",
    "logout",
    "signout",
    "sign-out",
    "password",
    "passwd",
    "email",
    "sendmail",
    "confirm",
    "cancel",
    "deactivate",
    "unsubscribe",
    "subscribe",
  )

  /** A multipart / file form is never submitted. */
  private def hasFileField(form: FormInfo): Boolean = form.fields
    .exists((_, t) => t == "file")

  private def denyHit(form: FormInfo): Option[String] =
    val haystack =
      (form.action + " " + form.fields.map(_._1).mkString(" ") + " " +
        form.submitText).toLowerCase
    denyPatterns.find(haystack.contains)

  /** `Right` to submit, `Left(reason)` to refuse. */
  def allow(form: FormInfo, modelSaysSafe: Boolean): Either[String, Unit] =
    if hasFileField(form) then Left("file upload form")
    else
      denyHit(form) match
        case Some(p) => Left(s"matches destructive pattern '$p'")
        case None => form.method match
            case "get" => Right(())
            case "post" if modelSaysSafe => Right(())
            case "post" => Left("POST not classified non-state-changing")
            case other => Left(s"method '$other' is never submitted")
