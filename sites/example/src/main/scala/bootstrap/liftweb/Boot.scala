/*
 * Copyright 2007-2008 WorldWide Conferencing, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 */
package bootstrap.liftweb

import net.liftweb.util.{Helpers, Can, Full, Empty, Failure, Log}
import net.liftweb.http._
import Helpers._
import net.liftweb.mapper.{DB, ConnectionManager, Schemifier, DefaultConnectionIdentifier, ConnectionIdentifier}
import java.sql.{Connection, DriverManager}
import net.liftweb.example.comet.WebServices
import javax.servlet.http.{HttpServlet, HttpServletRequest , HttpServletResponse, HttpSession}
import net.liftweb.example.model._
import net.liftweb.example.snippet.definedLocale

/**
* A class that's instantiated early and run.  It allows the application
* to modify lift's environment
*/
class Boot {
  def boot {
    DB.defineConnectionManager(DefaultConnectionIdentifier, DBVendor)
    LiftRules.addToPackages("net.liftweb.example")

    LiftRules.localeCalculator = r => definedLocale.openOr(LiftRules.defaultLocaleCalculator(r))

    Schemifier.schemify(true, Log.infoF _, User, WikiEntry)

    val dispatcher: LiftRules.DispatchPf = {
      // if the url is "showcities" then return the showCities function
      case RequestMatcher(_, ParsePath("showcities":: _, _, _),_,  _) => XmlServer.showCities

      // if the url is "showstates" "curry" the showStates function with the optional second parameter
      case RequestMatcher(_, ParsePath("showstates":: xs, _, _),_,  _) => XmlServer.showStates(if (xs.isEmpty) "default" else xs.head)

      // if it's a web service, pass it to the web services invoker
      case RequestMatcher(r, ParsePath("webservices" :: c :: _, _,_),_, _) => invokeWebService(r, c)
    }
    LiftRules.addDispatchBefore(dispatcher)

    val wiki_rewriter: LiftRules.RewritePf = {
      case RewriteRequest( path @ ParsePath("wiki" :: page :: _, _,_), _, _) =>
      RewriteResponse("wiki" :: Nil,
      Map("wiki_page" -> page :: path.path.drop(2).zipWithIndex.map(p => ("param"+(p._2 + 1)) -> p._1) :_*))
    }

    LiftRules.addRewriteBefore(wiki_rewriter)

    val wikibind_rewriter: LiftRules.RewritePf = {
      case RewriteRequest(path @ ParsePath("wikibind" :: page :: _, _,_), _, _) =>
      RewriteResponse(ParsePath("wikibind" :: Nil, true, false),
      Map("wiki_page" -> page :: path.path.drop(2).zipWithIndex.map(p => ("param"+(p._2 + 1)) -> p._1) :_*))
    }

    LiftRules.appendEarly(makeUtf8)

    LiftRules.addRewriteBefore(wikibind_rewriter)

  }

  private def invokeWebService(request: RequestState, methodName: String)(req: RequestState): Can[ResponseIt] =
  createInvoker(methodName, new WebServices(request)).flatMap(_() match {
    case Full(ret: ResponseIt) => Full(ret)
    case _ => Empty
  })

  private def makeUtf8(req: HttpServletRequest): Unit = {req.setCharacterEncoding("UTF-8")}
}

object XmlServer {
  def showStates(which: String)(req: RequestState): Can[XmlResponse] = Full(XmlResponse(
  <states renderedAt={timeNow.toString}>{
    which match {
      case "red" => <state name="Ohio"/><state name="Texas"/><state name="Colorado"/>

      case "blue" => <state name="New York"/><state name="Pennsylvania"/><state name="Vermont"/>

      case _ => <state name="California"/><state name="Rhode Island"/><state name="Maine"/>
      } }</states>))

      def showCities(ignore: RequestState): Can[XmlResponse] = Full(XmlResponse(<cities>
      <city name="Boston"/>
      <city name="New York"/>
      <city name="San Francisco"/>
      <city name="Dallas"/>
      <city name="Chicago"/>
      </cities>))

    }

    object DBVendor extends ConnectionManager {
      def newConnection(name: ConnectionIdentifier): Can[Connection] = {
        try {
          Class.forName("org.h2.Driver")
          val dm =  DriverManager.getConnection("jdbc:h2:mem:lift;DB_CLOSE_DELAY=-1")
          Full(dm)
        } catch {
          case e : Exception => e.printStackTrace; Empty
        }
      }
      def releaseConnection(conn: Connection) {conn.close}
    }
