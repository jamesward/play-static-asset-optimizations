package controllers

import play.api.mvc.{Action, Controller}
import play.api.libs.json.Json

object Application extends Controller {
  
  def index = Action {
    Ok(views.html.index("Hello Play Framework"))
  }
  
  def foo = Action {
    Ok(Json.obj("message" -> "hello, foo"))
  }
  
}