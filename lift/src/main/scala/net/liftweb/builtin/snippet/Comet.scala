/*
 * Copyright 2007-2009 WorldWide Conferencing, LLC
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
package net.liftweb.builtin.snippet

import scala.xml._
import net.liftweb.http._
import net.liftweb.util._
import Helpers._
import Box._

object Comet extends DispatchSnippet {

  def dispatch : DispatchIt = {
    case _ => render _
  }

  def render(kids: NodeSeq) : NodeSeq = {

    Props.inGAE match {
      case true => Text("Comet Disabled in Google App Engine")
      case _ =>  buildComet(kids)
    }
  }

  private def buildComet(kids: NodeSeq) : NodeSeq = {

    def accumulate(e: NodeSeq): NodeSeq = {
      val elem : Node = e first

      for {id <- elem.attribute("id")
    	   when <- elem.attribute(null, "when")} yield {
    	   CVPVar(CVPVar.get ::: List(CVP(id.text, toLong(when.text))))
      }
      e
    }

    (for {ctx <- S.session} yield {
       val theType: Box[String] = S.attr.~("type").map(_.text)
       val name: Box[String] = S.attr.~("name").map(_.text)
       try {
         ctx.findComet(theType, name, kids, S.attrsFlattenToMap).map(c =>

            (c !? (26600, AskRender)) match {
              case Some(AnswerRender(response, _, when, _)) if c.hasOuter =>
                <span id={c.uniqueId+"_outer"}>{accumulate(c.buildSpan(when, response.inSpan))}{response.outSpan}</span>

              case Some(AnswerRender(response, _, when, _)) =>
                accumulate(c.buildSpan(when, response.inSpan))

              case _ => <span id={c.uniqueId} lift:when="0">{Comment("FIXME comet type "+theType+" name "+name+" timeout") ++ kids}</span>
            }) openOr Comment("FIXME - comet type: "+theType+" name: "+name+" Not Found ") ++ kids
          } catch {
            case e => Log.error("Failed to find a comet actor", e); kids
          }
    }) openOr Comment("FIXME: session or request are invalid")
  }
}
