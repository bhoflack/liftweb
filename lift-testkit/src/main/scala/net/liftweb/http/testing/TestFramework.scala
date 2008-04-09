package net.liftweb.http.testing

import net.liftweb.util.Helpers._
import net.liftweb.util._
import scala.xml.{NodeSeq, Text, XML, Elem}
import java.net._
import java.util.{Map => JavaMap, Set => JavaSet, Iterator => JavaIterator, List => JavaList}
import java.util.regex.Pattern

trait TestFramework {
  def baseUrl: String
  // def runner: TestRunner
  def tests: List[Item]
  def buildRunner: TestRunner
  
  private var assertFunc: (String, () => ReqRes) => ReqRes = _
  def runTests = synchronized {
    val (runner, tAssert) = buildRunner.setup[ReqRes](tests)
    try {
      assertFunc = tAssert
      runner()
    } finally {
      assertFunc = null
    }
  }
  
  class TestHandler(res: ReqRes) {
    def then (f: ReqRes => ReqRes): ReqRes = f(res)
    def also (f: ReqRes => Any): ReqRes = {f(res); res}
  }
  implicit def reqToHander(in: ReqRes): TestHandler = new TestHandler(in)
  
  
  def get(url: String, params: (String, Any)*): ReqRes = get(url, Nil, params :_*)
  def post(url: String, params: (String, Any)*): ReqRes = post(url, Nil, params :_*)
  
  def getCookie(headers: List[(String, String)], respHeaders: Map[String, List[String]]): Can[String] = {
    val ret = (headers.filter{case ("Cookie", _) => true; case _ => false}.map(_._2) ::: respHeaders.get("Set-Cookie").toList.flatMap(x => x)) match {
      case Nil => Empty
      case "" :: Nil => Empty
      case "" :: xs => Full(xs.mkString(","))
      case xs => Full(xs.mkString(","))
    }
    
    ret
  }
  
  def get(url: String,headers: List[(String, String)], faux_params: (String, Any)*): ReqRes = {
    val params = faux_params.toList.map(x => (x._1, x._2.toString))
    val fullUrl = url + (params.map(v => urlEncode(v._1)+"="+urlEncode(v._2)).mkString("&") match {case s if s.length == 0 => ""; case s => "?"+s})  
    val ret = (baseUrl + fullUrl, new URL(baseUrl + fullUrl).openConnection) match {
      case (_, u: HttpURLConnection) =>
      
      headers.foreach(h => u.setRequestProperty(h._1, h._2))
      val respHeaders = snurpHeaders(u.getHeaderFields)
      new HttpReqRes(u.getResponseCode, u.getResponseMessage, 
      respHeaders, readWholeStream(u.getInputStream), Map.empty, getCookie(headers, respHeaders))
      case (server, z) => Log.error("Tried to open an HTTP connection and got "+z); new CompleteFailure(server) 
    }
    ret
  }
  def post(url: String,headers: List[(String, String)], faux_params: (String, Any)*): ReqRes = {
    val params = faux_params.toList.map(x => (x._1, x._2.toString))
    val paramStr = params.map(v => urlEncode(v._1)+"="+urlEncode(v._2)).mkString("&")
    val paramByte = paramStr.getBytes("UTF-8")
    val ret = (baseUrl + url, new URL(baseUrl + url).openConnection) match {
      case (_, u: HttpURLConnection) =>
      headers.foreach(h => u.setRequestProperty(h._1, h._2))
      u.setDoOutput(true)
      u.setRequestMethod("POST")
      u.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
      u.setRequestProperty("Content-Length", paramByte.length.toString)
      u.getOutputStream.write( paramByte)
      val respHeaders = snurpHeaders(u.getHeaderFields)
      new HttpReqRes(u.getResponseCode, u.getResponseMessage, 
      respHeaders, readWholeStream(u.getInputStream), Map.empty, getCookie(headers, respHeaders))
      case (server, z) => Log.error("Tried to open an HTTP connection and got "+z); new CompleteFailure(server) 
    }
    ret
  }
  
  def post(url: String,body: NodeSeq, headers: List[(String, String)]): ReqRes = {
    val paramByte = body.toString.getBytes("UTF-8")
    val ret = (baseUrl + url, new URL(baseUrl + url).openConnection) match {
      case (_, u: HttpURLConnection) =>
      headers.foreach(h => u.setRequestProperty(h._1, h._2))
      u.setDoOutput(true)
      u.setRequestMethod("POST")
      u.setRequestProperty("Content-Type", "text/xml")
      u.setRequestProperty("Content-Length", paramByte.length.toString)
      u.getOutputStream.write( paramByte)
      val respHeaders = snurpHeaders(u.getHeaderFields)
      new HttpReqRes(u.getResponseCode, u.getResponseMessage, 
      respHeaders, readWholeStream(u.getInputStream), Map.empty, getCookie(headers, respHeaders))
      case (server, z) => Log.error("Tried to open an HTTP connection and got "+z); new CompleteFailure(server) 
    }
    ret
  }
  
  abstract class ReqRes {
    def assertSuccess = this
    def xml: Elem = <xml:group />
    
    def assert(f: => Boolean, msg: String): ReqRes = {
      TestFramework.this.assertFunc(msg,{() =>
        if (!f) throw new TestFailureError(msg)
        this
      })
    }
    def headers: Map[String, List[String]] = Map.empty
    def values: Map[String, String] = Map.empty
    def assertTag(tag: String, msg:String) = assert((xml \\ tag).length > 0, msg)
    def code: Int = -1
    def msg: String = ""
    def cookie: Can[String] = Empty
    val body: Array[Byte] = new Array(0)
    lazy val bodyAsString = new String(body, "UTF-8")
    def get(url: String, params: (String, Any)*) = TestFramework.this.get(url, cookie.map( ("Cookie", _) ).toList , params:_*)
    
    def post(url: String, params: (String, Any)*) = TestFramework.this.post(url, cookie.map( ("Cookie", _) ).toList, params:_*)
    
    def post(url: String, body: NodeSeq) = TestFramework.this.post(url, body, cookie.map( ("Cookie", _) ).toList)
  }
  
  class HttpReqRes(override val code: Int,override val msg: String,override val headers: Map[String, List[String]],
  override val body: Array[Byte],override val values: Map[String, String],override val cookie: Can[String]) extends ReqRes {
    
    override def assertSuccess = assert(code == 200, "Not an HTTP success")
    override lazy val xml = XML.load(new java.io.ByteArrayInputStream(body))
  }
  
  class CompleteFailure(val serverName: String) extends ReqRes {
    override def assertSuccess = assert(false, "Failed to connect to server: "+serverName)
  }
  
  def fork(cnt: Int)(f: Int => Any) {
    val threads = for (t <- (1 to cnt).toList) yield {
      val th = new Thread(new Runnable{def run {f(t)}})
      th.start
      th
    }
    
    def waitAll(in: List[Thread]) {
      in match {
        case Nil =>
        case x :: xs => x.join; waitAll(xs)
      }
    }
    
    waitAll(threads)
  }  
  
  type CRK = JavaList[String]
  implicit def jitToIt[T](in: JavaIterator[T]): Iterator[T] = new Iterator[T] {
    def next: T = in.next
    def hasNext = in.hasNext
  }
  
  private def snurpHeaders(in: JavaMap[String, CRK]): Map[String, List[String]] = {
    def morePulling(e: JavaMap.Entry[String, CRK]): (String, List[String]) = {
      e.getValue match {
        case null => (e.getKey, Nil)
        case a => (e.getKey, a.iterator.toList)
      }
    }
    
    Map(in.entrySet.iterator.toList.filter(e => (e ne null) && (e.getKey != null)).map(e => morePulling(e)) :_*)
  }
}

object TestHelpers {
  /**
  * Get the function name given a particular comet actor name
  *
  * @param cometName the name (default prefix) for the comet actor
  * @param body the body of the response
  *
  * @return the name of the JSON function associated with the Comet actor
  */
  def jsonFuncForCometName(cometName: String, body: String): Can[String] = {
    val p = Pattern.compile("""JSON Func """+cometName+""" \$\$ (F[^ ]*)""")
    val m = p.matcher(body)
    if (m.find) Full(m.group(1))
    else Empty
  }
  
  
  /**
  * Given an HTML page, find the list of "lift_toWatch" names and values
  * These can be fed back into a comet request
  *
  * @param body the page body returned from an HTTP request
  *
  * @return a list of the "to watch" tokens and the last update tokens
  */
  def toWatchFromPage(body: String): List[(String, String)] = {
    val p = Pattern.compile("""lift_toWatch[ ]*\=[ ]*\{([^}]*)\}""")
    val rp = new REMatcher(body, p)
    val p2 = Pattern.compile("""(L[^\:]*)\: \'([0-9]*)""")
    
    for (it <- rp.capture;
    val q = new REMatcher(it, p2);
    em <- q.eachFound) yield (em(1), em(2))
  }
  
  /**
  * Given the body of a Comet response, parse for updates "lift_toWatch" values
  * and update the current sequence to reflect any updated values
  *
  * @param old the old toWatch sequence
  * @param body the body of the comet response
  *
  * @return the updated sequences
  */
  def toWatchUpdates(old: Seq[(String, String)], body: String): Seq[(String, String)] = {
      val p = Pattern.compile("""lift_toWatch\[\'(L[^\']*)\'] \= \'([0-9]*)""")
    val re = new REMatcher(body, p)
    val np = re.eachFound.foldLeft(Map(old :_*))((a, b) => a + ( (b(1), b(2))) )
    np.elements.toList
  }
}
