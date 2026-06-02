package dast

/** Runs all deterministic (Tier 1) checks over a captured snapshot.
  *
  * These always run, never call a model, and produce reproducible findings.
  * The Tier 2 LLM classifier (a later slice) only runs on values a cheap
  * static prefilter flags as ambiguous, so the model is never on the hot path.
  */
object Tier1:

  def run(snapshot: ClientStateSnapshot): Seq[Finding] =
    CookieFlagCheck.check(snapshot) ++ SecretInStorageCheck.check(snapshot)
