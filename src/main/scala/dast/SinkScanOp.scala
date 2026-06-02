package dast

import scala.jdk.CollectionConverters.*

import com.microsoft.playwright.Page
import com.microsoft.playwright.options.LoadState
import com.microsoft.playwright.options.WaitUntilState

import crawler.BrowserResource

/** DOM-based XSS detection by taint observation (the `sink-scan` PageOp of
  * CLAUDE.md section 3). Instead of executing a payload, it delivers a unique,
  * benign marker through a source (e.g. the URL fragment) and instruments the
  * dangerous client-side sinks; if the page's own JS routes the marker into a
  * sink, that source-to-sink flow is a reproducible DOM-XSS indicator.
  *
  * The marker is alphanumeric and inert: this neither injects executable markup
  * nor changes server state. The instrumentation and the source-delivery still
  * count as active testing, so the orchestrator gates it like a probe.
  *
  * The readback parsing and finding logic are pure and unit tested; the
  * page-driving [[scan]] is run-only.
  */
object SinkScanOp:

  /** Installed before navigation: wraps the dangerous sinks and records, in
    * `window.__dastSinks`, the name of any sink that receives the marker.
    */
  def sinkScanJs(marker: String): String =
    val m = PayloadLibrary.escapeJsString(marker)
    s"""(function(){
       |  var M='$m';
       |  window.__dastSinks=window.__dastSinks||[];
       |  function hit(n){ if(window.__dastSinks.indexOf(n)===-1) window.__dastSinks.push(n); }
       |  function tainted(v){ try { return typeof v==='string' && v.indexOf(M)!==-1; } catch(e){ return false; } }
       |  try { var _e=window.eval; window.eval=function(s){ if(tainted(s)) hit('eval'); return _e.apply(this,arguments); }; } catch(e){}
       |  try { var _F=window.Function; window.Function=function(){ for(var i=0;i<arguments.length;i++){ if(tainted(arguments[i])) hit('Function'); } return _F.apply(this,arguments); }; } catch(e){}
       |  try { var _w=document.write; document.write=function(s){ if(tainted(s)) hit('document.write'); return _w.apply(document,arguments); }; } catch(e){}
       |  try { var _st=window.setTimeout; window.setTimeout=function(f){ if(tainted(f)) hit('setTimeout(string)'); return _st.apply(this,arguments); }; } catch(e){}
       |  try { var _si=window.setInterval; window.setInterval=function(f){ if(tainted(f)) hit('setInterval(string)'); return _si.apply(this,arguments); }; } catch(e){}
       |  try {
       |    var p=Element.prototype, d=Object.getOwnPropertyDescriptor(p,'innerHTML');
       |    if(d&&d.set){ var _s=d.set; Object.defineProperty(p,'innerHTML',{configurable:true,enumerable:d.enumerable,get:d.get,set:function(v){ if(tainted(v)) hit('innerHTML'); return _s.call(this,v); }}); }
       |  } catch(e){}
       |  try { var _a=window.location.assign.bind(window.location); window.location.assign=function(u){ if(tainted(u)) hit('location.assign'); return _a(u); }; } catch(e){}
       |})();""".stripMargin

  /** Pure: parse the `__dastSinks` readback into a set of sink names. */
  def parseSinkHits(raw: Any): Set[String] = raw match
    case l: java.util.List[?] => l.asScala.iterator
        .collect { case s if s != null => s.toString }.toSet
    case _ => Set.empty

  /** Pure: a reproducible DOM-XSS finding per sink the marker reached. */
  def toFindings(source: InjectionPoint, sinks: Set[String]): Seq[Finding] =
    sinks.toSeq.sorted.map { sink =>
      Finding(
        kind = FindingKind.Xss,
        severity = Severity.High,
        evidence = s"marker reached DOM sink '$sink' via ${source.describe}",
        reproducible = true,
        replay = s"domxss ${source.describe} sink=$sink",
      )
    }

  /** Run on the pinned thread: instrument sinks, deliver the marker via
    * `source`, read back which sinks it reached. Not unit tested (needs a live
    * page).
    */
  def scan(
      resource: BrowserResource,
      baseUrl: String,
      source: InjectionPoint,
      marker: String,
      navTimeoutMs: Int = 15000,
  ): Set[String] = resource.withFreshPage { page =>
    page.addInitScript(sinkScanJs(marker))
    page.navigate(
      source.placeInto(baseUrl, marker),
      new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
        .setTimeout(navTimeoutMs),
    )
    page.waitForLoadState(LoadState.DOMCONTENTLOADED)
    parseSinkHits(page.evaluate("() => window.__dastSinks || []"))
  }
