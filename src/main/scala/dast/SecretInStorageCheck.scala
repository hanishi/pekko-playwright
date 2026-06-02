package dast

/** Deterministic Tier 1 check: secrets sitting in local/session storage.
  *
  * Pure over a [[ClientStateSnapshot]]. Uses [[SecretClassifier]] for the
  * actual decision so this stays a thin mapping from a classifier hit to a
  * [[Finding]]. Evidence references the store and key, never the raw value, so
  * findings do not themselves leak the secret. An observed auth flow raises a
  * strong hit to Critical but never manufactures a finding on its own.
  */
object SecretInStorageCheck:

  def check(snapshot: ClientStateSnapshot): Seq[Finding] =
    val entries =
      snapshot.localStorage.iterator.map(kv => ("localStorage", kv._1, kv._2)) ++
        snapshot.sessionStorage.iterator.map(kv => ("sessionStorage", kv._1, kv._2))

    entries.flatMap { case (store, key, value) =>
      SecretClassifier.classify(value).map { hit =>
        val base = hit.kind match
          case SecretClassifier.Kind.Jwt | SecretClassifier.Kind.KnownCredential => Severity.High
          case SecretClassifier.Kind.HighEntropyToken                            => Severity.Medium
        val severity =
          if snapshot.observedAuthFlow && base == Severity.High then Severity.Critical else base
        Finding(
          kind = FindingKind.SecretInStorage,
          severity = severity,
          evidence = s"$store['$key'] looks like a secret (${hit.detail})",
          reproducible = true,
          replay = s"$store['$key']",
        )
      }
    }.toSeq
